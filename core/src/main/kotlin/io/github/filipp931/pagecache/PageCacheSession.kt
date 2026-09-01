package io.github.filipp931.pagecache

import java.nio.file.Path

/**
 * A sweep-scoped view of [PageCacheOps]: same operations, but the
 * implementation may pool native resources for the lifetime of the session.
 * Not thread-safe; close when the sweep ends.
 */
internal interface PageCacheSession : AutoCloseable {
    /** Whole-file advise; `false` on failure. */
    fun tryAdvise(file: Path, advice: Advice): Boolean

    /** @throws PageCacheException on failure */
    fun residency(file: Path): Residency

    override fun close()
}
