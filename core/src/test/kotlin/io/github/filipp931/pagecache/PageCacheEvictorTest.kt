package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class PageCacheEvictorTest {
    @TempDir
    lateinit var tempDir: Path

    private val ops = FakePageCacheOps()

    private fun file(name: String, ageMinutes: Long, bytes: Int = 8, dir: Path = tempDir): Path {
        val path = dir.resolve(name)
        Files.write(path, ByteArray(bytes))
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofMinutes(ageMinutes))))
        return path
    }

    private fun evictor(configure: PageCacheEvictor.Builder.() -> Unit = {}): PageCacheEvictor = PageCacheEvictor
        .builder(ops)
        .directory(tempDir)
        .keepRecent(Duration.ofMinutes(2))
        .apply(configure)
        .build()

    @Test
    fun `evicts old files and keeps fresh ones`() {
        val old = file("old.rec", ageMinutes = 60, bytes = 100)
        file("fresh.rec", ageMinutes = 0)

        val stats = evictor().runOnce()

        assertThat(ops.advised).containsExactly(old to Advice.DONTNEED)
        assertThat(stats.scanned).isEqualTo(2)
        assertThat(stats.evicted).isEqualTo(1)
        assertThat(stats.bytesEvicted).isEqualTo(100)
        assertThat(stats.keptRecent).isEqualTo(1)
        assertThat(stats.failed).isZero()
        assertThat(stats.skipped).isFalse()
    }

    @Test
    fun `suffix filter narrows the sweep`() {
        val rec = file("seg.rec", ageMinutes = 60)
        file("noise.tmp", ageMinutes = 60)

        val stats = evictor { fileSuffix(".rec") }.runOnce()

        assertThat(ops.advised.map { it.first }).containsExactly(rec)
        assertThat(stats.scanned).isEqualTo(1)
    }

    @Test
    fun `multiple suffixes all match`() {
        val rec = file("a.rec", ageMinutes = 60)
        val wal = file("b.wal", ageMinutes = 60)
        file("c.tmp", ageMinutes = 60)

        evictor {
            fileSuffix(".rec")
            fileSuffix(".wal")
        }.runOnce()

        assertThat(ops.advised.map { it.first }).containsExactlyInAnyOrder(rec, wal)
    }

    @Test
    fun `sweeps multiple directories`() {
        val other = Files.createDirectory(tempDir.resolve("other"))
        val a = file("a.rec", ageMinutes = 60)
        val b = file("b.rec", ageMinutes = 60, dir = other)

        val stats =
            PageCacheEvictor
                .builder(ops)
                .directory(tempDir)
                .directory(other)
                .keepRecent(Duration.ofMinutes(2))
                .fileSuffix(".rec")
                .build()
                .runOnce()

        assertThat(ops.advised.map { it.first }).containsExactlyInAnyOrder(a, b)
        assertThat(stats.evicted).isEqualTo(2)
    }

    @Test
    fun `does not recurse into subdirectories`() {
        val sub = Files.createDirectory(tempDir.resolve("sub"))
        file("nested.rec", ageMinutes = 60, dir = sub)

        val stats = evictor().runOnce()

        assertThat(ops.advised).isEmpty()
        assertThat(stats.scanned).isZero()
    }

    @Test
    fun `failures are counted and do not abort the sweep`() {
        val bad = file("bad.rec", ageMinutes = 60)
        val good = file("good.rec", ageMinutes = 60)
        ops.failFor = { it == bad }

        val stats = evictor().runOnce()

        assertThat(ops.advised.map { it.first }).containsExactly(good)
        assertThat(stats.evicted).isEqualTo(1)
        assertThat(stats.failed).isEqualTo(1)
    }

    @Test
    fun `dry run measures residency instead of advising`() {
        file("old.rec", ageMinutes = 60)
        ops.residencyFor = { Residency(pagesTotal = 16, pagesResident = 4, pageSize = 4096) }

        val stats = evictor { dryRun(true) }.runOnce()

        assertThat(ops.advised).isEmpty()
        assertThat(ops.residencyCalls).hasSize(1)
        assertThat(stats.evicted).isEqualTo(1)
        assertThat(stats.bytesEvicted).isEqualTo(4 * 4096L)
    }

    @Test
    fun `missing directory counts as one failure`() {
        val gone = tempDir.resolve("no-such-dir")
        val old = file("old.rec", ageMinutes = 60)

        val stats =
            PageCacheEvictor
                .builder(ops)
                .directory(gone)
                .directory(tempDir)
                .keepRecent(Duration.ofMinutes(2))
                .build()
                .runOnce()

        assertThat(stats.failed).isEqualTo(1)
        assertThat(ops.advised.map { it.first }).containsExactly(old)
    }

    @Test
    fun `reentrant runOnce is skipped, not run`() {
        file("old.rec", ageMinutes = 60)
        lateinit var evictor: PageCacheEvictor
        var innerStats: CycleStats? = null
        ops.adviseHook = { innerStats = evictor.runOnce() }
        evictor = evictor()

        val stats = evictor.runOnce()

        assertThat(stats.skipped).isFalse()
        assertThat(stats.evicted).isEqualTo(1)
        assertThat(innerStats).isNotNull()
        assertThat(innerStats!!.skipped).isTrue()
        assertThat(innerStats!!.evicted).isZero()
    }

    @Test
    fun `interrupted sweep stops early and keeps the interrupt flag`() {
        val first = file("a.rec", ageMinutes = 60)
        file("b.rec", ageMinutes = 60)
        file("c.rec", ageMinutes = 60)
        ops.adviseHook = { Thread.currentThread().interrupt() }

        val stats = evictor().runOnce()

        try {
            // first advise interrupts the thread; the sweep must stop before the rest
            assertThat(ops.advised.map { it.first }).hasSize(1)
            assertThat(stats.evicted).isEqualTo(1)
            assertThat(stats.skipped).isFalse()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted() // clear for other tests
        }
        // suppress unused warning
        assertThat(first).exists()
    }

    @Test
    fun `zero keepRecent evicts everything already modified in the past`() {
        file("just-now.rec", ageMinutes = 1)

        val stats = evictor { keepRecent(Duration.ZERO) }.runOnce()

        assertThat(stats.evicted).isEqualTo(1)
    }
}
