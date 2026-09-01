package io.github.filipp931.pagecache

import java.nio.file.Path

/**
 * Page-cache operations backed by `posix_fadvise` and `mincore` via the Java
 * FFM API — no JNI, no helper binaries.
 *
 * Obtain an instance with [tryCreate]:
 * ```java
 * PageCacheOps ops = PageCacheOps.tryCreate();  // null -> not supported here
 * if (ops != null) {
 *     ops.evict(coldSegment);
 * }
 * ```
 *
 * [tryCreate] returns `null` on non-Linux platforms and whenever the libc
 * symbols cannot be linked. There is no silent no-op mode: deciding what to do
 * on an unsupported platform is the caller's business, not this library's.
 *
 * Instances are immutable and safe to share across threads.
 */
public abstract class PageCacheOps protected constructor() {
    /** The system page size in bytes. */
    public abstract fun pageSize(): Long

    /**
     * `posix_fadvise(fd, offset, length, advice)` on the file, opened read-only
     * for the duration of the call. A [length] of 0 means "to the end of file".
     *
     * @throws PageCacheException with the errno if the syscall fails
     */
    public abstract fun advise(file: Path, offset: Long, length: Long, advice: Advice)

    /**
     * How much of the file is currently resident in the page cache
     * (open → mmap `PROT_NONE` → `mincore` → munmap).
     *
     * @throws PageCacheException with the errno if any syscall fails
     */
    public abstract fun residency(file: Path): Residency

    /** [advise] for the whole file. */
    public fun advise(file: Path, advice: Advice): Unit = advise(file, 0, 0, advice)

    /** Exception-free [advise] for sweeps: returns `false` instead of throwing. */
    public fun tryAdvise(file: Path, advice: Advice): Boolean = tryAdvise(file, 0, 0, advice)

    /** Exception-free ranged [advise]: returns `false` instead of throwing. */
    public fun tryAdvise(file: Path, offset: Long, length: Long, advice: Advice): Boolean = try {
        advise(file, offset, length, advice)
        true
    } catch (_: PageCacheException) {
        false
    } catch (_: IllegalArgumentException) {
        // e.g. a negative offset computed from a shrinking file — the
        // "exception-free" contract holds for bad ranges too
        false
    }

    /** Drops the file's clean cached pages now (`POSIX_FADV_DONTNEED`). */
    public fun evict(file: Path): Unit = advise(file, Advice.DONTNEED)

    /** Starts asynchronous read-ahead of the file (`POSIX_FADV_WILLNEED`). */
    public fun prefetch(file: Path): Unit = advise(file, Advice.WILLNEED)

    /**
     * Opens a sweep-scoped session. The FFM implementation backs it with one
     * confined [java.lang.foreign.Arena] for the whole sweep so native scratch
     * (path strings, the mincore vector) is reused across files instead of
     * being mapped and unmapped per file.
     */
    internal open fun openSession(): PageCacheSession = object : PageCacheSession {
        override fun tryAdvise(file: Path, advice: Advice): Boolean = this@PageCacheOps.tryAdvise(file, advice)

        override fun residency(file: Path): Residency = this@PageCacheOps.residency(file)

        override fun close() {}
    }

    public companion object {
        /**
         * Creates the FFM-backed implementation, or returns `null` when this
         * platform cannot support it (not Linux, or libc symbols failed to link).
         */
        @JvmStatic
        public fun tryCreate(): PageCacheOps? = FfmPageCacheOps.tryCreate()

        /** True if [tryCreate] would succeed on this platform. */
        @JvmStatic
        public fun isSupported(): Boolean = FfmPageCacheOps.tryCreate() != null
    }
}
