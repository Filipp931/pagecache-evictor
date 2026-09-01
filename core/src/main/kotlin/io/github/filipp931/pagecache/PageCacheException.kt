package io.github.filipp931.pagecache

/**
 * A native page-cache operation failed.
 *
 * @property errno the libc errno (or `posix_fadvise` return code); 0 when the
 *   failure did not come from a syscall (e.g. the file could not be stat'ed)
 */
public class PageCacheException(message: String, public val errno: Int = 0, cause: Throwable? = null) : RuntimeException(message, cause)
