package io.github.filipp931.pagecache

/**
 * `posix_fadvise` advice values.
 *
 * [nativeValue] follows the generic Linux ABI (x86-64, aarch64, riscv).
 * A few historical architectures (s390x) renumber `DONTNEED`/`NOREUSE`;
 * this library does not special-case them.
 */
public enum class Advice(public val nativeValue: Int) {
    /** POSIX_FADV_NORMAL — reset to the default readahead behavior. */
    NORMAL(0),

    /** POSIX_FADV_RANDOM — expect random access; disables readahead. */
    RANDOM(1),

    /** POSIX_FADV_SEQUENTIAL — expect sequential access; doubles readahead. */
    SEQUENTIAL(2),

    /** POSIX_FADV_WILLNEED — start asynchronous read-ahead into the page cache. */
    WILLNEED(3),

    /** POSIX_FADV_DONTNEED — drop clean cached pages of the range now. */
    DONTNEED(4),

    /** POSIX_FADV_NOREUSE — data is used exactly once (a no-op on many kernels). */
    NOREUSE(5),
}
