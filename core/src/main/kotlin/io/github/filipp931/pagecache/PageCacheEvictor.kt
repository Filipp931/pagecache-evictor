package io.github.filipp931.pagecache

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Age-based page-cache sweeper: walks the configured directories
 * (non-recursively), and advises `DONTNEED` on every matching regular file
 * older than `keepRecent`. Fresh files stay cached for readers; cold ones stop
 * competing with your working set. Symbolic links are never followed — a stray
 * link must not let the sweep evict files outside the configured directories.
 *
 * There is deliberately **no scheduler inside**: call [runOnce] from whatever
 * already schedules things in your process. Overlapping calls are guarded —
 * if a cycle is still running, the new call returns immediately with
 * [CycleStats.skipped] set.
 *
 * ```java
 * PageCacheEvictor evictor = PageCacheEvictor.builder(ops)
 *     .directory(archiveDir)
 *     .fileSuffix(".rec")
 *     .keepRecent(Duration.ofMinutes(2))
 *     .throttleBetweenFiles(Duration.ofMillis(15))
 *     .build();
 * CycleStats stats = evictor.runOnce();
 * ```
 */
public class PageCacheEvictor private constructor(
    private val ops: PageCacheOps,
    private val directories: List<Path>,
    private val suffixes: List<String>,
    private val keepRecent: Duration,
    private val throttle: Duration,
    private val dryRun: Boolean,
) {
    private val running = AtomicBoolean(false)

    /**
     * Runs one sweep. Never throws for per-file problems — they are counted in
     * [CycleStats.failed]. Returns immediately with [CycleStats.skipped] if a
     * previous cycle is still in flight (re-entrancy guard for schedulers that
     * fire faster than a sweep completes).
     */
    public fun runOnce(): CycleStats {
        if (!running.compareAndSet(false, true)) {
            return CycleStats.SKIPPED
        }
        try {
            return ops.openSession().use { session -> sweep(session) }
        } finally {
            running.set(false)
        }
    }

    private fun sweep(session: PageCacheSession): CycleStats {
        // File age is lastModified vs a fixed cutoff computed once per cycle,
        // so a long sweep doesn't shift its own goalposts.
        val cutoff = Instant.now().minus(keepRecent)
        var scanned = 0
        var evicted = 0
        var bytesEvicted = 0L
        var keptRecent = 0
        var failed = 0
        // set after each fadvise/mincore call so the throttle only separates
        // native bursts, never plain directory metadata reads
        var throttlePending = false

        for (directory in directories) {
            val stream =
                try {
                    Files.newDirectoryStream(directory)
                } catch (_: IOException) {
                    // unreadable or vanished directory: count once, move on
                    failed++
                    continue
                }
            stream.use {
                try {
                    for (entry in it) {
                        // An interrupted thread must neither keep sweeping at full
                        // speed (parkNanos returns immediately once interrupted, so
                        // the throttle is gone) nor swallow the interrupt: stop and
                        // report what was done so far. The flag stays set.
                        if (Thread.currentThread().isInterrupted) {
                            return CycleStats(scanned, evicted, bytesEvicted, keptRecent, failed, skipped = false)
                        }

                        // suffix match on the file name via endsWith: allocation-free,
                        // no PathMatcher machinery on the sweep path
                        val name = entry.fileName.toString()
                        if (suffixes.isNotEmpty() && suffixes.none(name::endsWith)) {
                            continue
                        }

                        // one stat per file instead of isRegularFile+mtime+size;
                        // NOFOLLOW so a stray symlink cannot make the sweep evict
                        // an arbitrary file outside the configured directories
                        val attributes =
                            try {
                                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            } catch (_: IOException) {
                                failed++
                                continue
                            }
                        if (!attributes.isRegularFile) {
                            continue
                        }
                        scanned++

                        if (attributes.lastModifiedTime().toInstant() >= cutoff) {
                            keptRecent++
                            continue
                        }

                        // Throttle BETWEEN native calls, not around metadata reads.
                        // A burst of ~1500 open/fadvise/close calls in ~50ms holds
                        // the kernel page-LRU and mapping locks long enough to stall
                        // concurrent skb allocation on a network thread's sendto
                        // path — visible as cron-aligned 2-10ms latency tails.
                        // Spreading the calls keeps each lock acquisition
                        // sub-millisecond.
                        if (throttlePending && !throttle.isZero) {
                            LockSupport.parkNanos(throttle.toNanos())
                        }
                        throttlePending = true

                        if (dryRun) {
                            // measure what WOULD be evicted: resident bytes right now
                            try {
                                bytesEvicted += session.residency(entry).bytesResident()
                                evicted++
                            } catch (_: PageCacheException) {
                                failed++
                            }
                        } else {
                            if (session.tryAdvise(entry, Advice.DONTNEED)) {
                                evicted++
                                bytesEvicted += attributes.size()
                            } else {
                                failed++
                            }
                        }
                    }
                } catch (_: DirectoryIteratorException) {
                    // readdir failed mid-listing (stale NFS handle, dir removed):
                    // count it and keep the "never throws per-file" contract
                    failed++
                }
            }
        }
        return CycleStats(scanned, evicted, bytesEvicted, keptRecent, failed, skipped = false)
    }

    /** Builder with a Java-friendly fluent API. */
    public class Builder internal constructor(private val ops: PageCacheOps) {
        private val directories = ArrayList<Path>()
        private val suffixes = ArrayList<String>()
        private var keepRecent: Duration = Duration.ofMinutes(2)
        private var throttle: Duration = Duration.ZERO
        private var dryRun = false

        /** Adds a directory to sweep (files directly inside it; not recursive). */
        public fun directory(directory: Path): Builder = apply { directories.add(directory) }

        /** Adds all given directories. */
        public fun directories(directories: Collection<Path>): Builder = apply { this.directories.addAll(directories) }

        /**
         * Restricts the sweep to file names ending with [suffix] (e.g. `".rec"`).
         * Repeatable; no suffixes at all means every regular file matches.
         */
        public fun fileSuffix(suffix: String): Builder = apply {
            require(suffix.isNotBlank()) { "file suffix must not be blank" }
            suffixes.add(suffix)
        }

        /** Adds all given suffixes. */
        public fun fileSuffixes(suffixes: Collection<String>): Builder = apply { suffixes.forEach { fileSuffix(it) } }

        /** Files modified within this window are never touched. Default: 2 minutes. */
        public fun keepRecent(keepRecent: Duration): Builder = apply {
            require(!keepRecent.isNegative) { "keepRecent must not be negative" }
            this.keepRecent = keepRecent
        }

        /** Pause between files to avoid kernel lock bursts (see class docs). Default: none. */
        public fun throttleBetweenFiles(throttle: Duration): Builder = apply {
            require(!throttle.isNegative) { "throttle must not be negative" }
            this.throttle = throttle
        }

        /** When `true`, measures residency via `mincore` instead of evicting. Default: `false`. */
        public fun dryRun(dryRun: Boolean): Builder = apply { this.dryRun = dryRun }

        /**
         * @throws IllegalArgumentException if no directory was configured
         */
        public fun build(): PageCacheEvictor {
            require(directories.isNotEmpty()) { "at least one directory is required" }
            return PageCacheEvictor(
                ops = ops,
                directories = directories.toList(),
                suffixes = suffixes.toList(),
                keepRecent = keepRecent,
                throttle = throttle,
                dryRun = dryRun,
            )
        }
    }

    public companion object {
        /** Starts building an evictor on top of [ops]. */
        @JvmStatic
        public fun builder(ops: PageCacheOps): Builder = Builder(ops)
    }
}
