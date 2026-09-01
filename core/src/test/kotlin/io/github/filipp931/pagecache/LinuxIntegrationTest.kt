package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * Real-syscall integration tests; they run in CI on a Linux runner and double
 * as the demo for the README numbers.
 */
@EnabledOnOs(OS.LINUX)
class LinuxIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val ops: PageCacheOps by lazy {
        PageCacheOps.tryCreate() ?: error("expected FFM page-cache support on the Linux CI runner")
    }

    /**
     * Writes [megabytes] MiB, fsyncs, and reads the file back to warm the cache.
     * The fsync matters: DONTNEED silently skips dirty pages, so without it an
     * eviction test would fail for the wrong reason.
     */
    private fun writeAndWarm(path: Path, megabytes: Int): Path {
        Files.newByteChannel(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val chunk = ByteBuffer.allocate(1 shl 20)
            repeat(megabytes) {
                chunk.clear()
                while (chunk.hasRemaining()) {
                    channel.write(chunk)
                }
            }
            (channel as java.nio.channels.FileChannel).force(true)
        }
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (input.read(buffer) >= 0) {
                // reading warms the page cache
            }
        }
        return path
    }

    @Test
    fun `warm file is resident and eviction drops it`() {
        val file = writeAndWarm(tempDir.resolve("data.bin"), megabytes = 64)

        val warm = ops.residency(file)
        assertThat(warm.pagesTotal).isEqualTo((64L shl 20) / ops.pageSize())
        assertThat(warm.ratio()).isGreaterThan(0.5)

        ops.evict(file)

        val cold = ops.residency(file)
        // a shared runner may re-fault a few pages; assert with margin, not zero
        assertThat(cold.ratio()).isLessThan(0.2)
        assertThat(cold.pagesResident).isLessThan(warm.pagesResident)
    }

    @Test
    fun `empty file has zero pages`() {
        val file = Files.createFile(tempDir.resolve("empty.bin"))
        val residency = ops.residency(file)
        assertThat(residency.pagesTotal).isZero()
        assertThat(residency.ratio()).isZero()
    }

    @Test
    fun `missing file surfaces errno`() {
        val missing = tempDir.resolve("no-such-file")
        // residency stats via Files.size first, so the failure carries no errno —
        // but it must still be the library's exception type
        assertThatThrownBy { ops.residency(missing) }
            .isInstanceOf(PageCacheException::class.java)
        // advise goes straight to open(2): ENOENT must surface
        assertThatThrownBy { ops.advise(missing, Advice.DONTNEED) }
            .isInstanceOf(PageCacheException::class.java)
            .matches { (it as PageCacheException).errno != 0 }
        assertThat(ops.tryAdvise(missing, Advice.DONTNEED)).isFalse()
    }

    @Test
    fun `prefetch and ranged advise succeed`() {
        val file = writeAndWarm(tempDir.resolve("small.bin"), megabytes = 1)
        assertThatCode {
            ops.prefetch(file)
            ops.advise(file, 0, 4096, Advice.SEQUENTIAL)
            ops.advise(file, Advice.NORMAL)
        }.doesNotThrowAnyException()
        assertThat(ops.tryAdvise(file, Advice.RANDOM)).isTrue()
    }

    @Test
    fun `evictor end to end evicts old segments and spares fresh ones`() {
        val old = writeAndWarm(tempDir.resolve("old.rec"), megabytes = 8)
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))))
        val fresh = writeAndWarm(tempDir.resolve("fresh.rec"), megabytes = 8)

        val stats =
            PageCacheEvictor
                .builder(ops)
                .directory(tempDir)
                .fileSuffix(".rec")
                .keepRecent(Duration.ofMinutes(2))
                .build()
                .runOnce()

        assertThat(stats.scanned).isEqualTo(2)
        assertThat(stats.evicted).isEqualTo(1)
        assertThat(stats.keptRecent).isEqualTo(1)
        assertThat(stats.failed).isZero()
        assertThat(ops.residency(old).ratio()).isLessThan(0.2)
        assertThat(ops.residency(fresh).ratio()).isGreaterThan(0.5)
    }

    @Test
    fun `dry run measures without evicting`() {
        val old = writeAndWarm(tempDir.resolve("old.rec"), megabytes = 4)
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))))

        val stats =
            PageCacheEvictor
                .builder(ops)
                .directory(tempDir)
                .keepRecent(Duration.ofMinutes(2))
                .dryRun(true)
                .build()
                .runOnce()

        assertThat(stats.evicted).isEqualTo(1)
        assertThat(stats.bytesEvicted).isGreaterThan(0)
        // nothing was actually evicted
        assertThat(ops.residency(old).ratio()).isGreaterThan(0.5)
    }
}
