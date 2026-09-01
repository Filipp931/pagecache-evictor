package io.github.filipp931.pagecache

/**
 * How much of a file currently sits in the page cache, as reported by `mincore`.
 *
 * @property pagesTotal number of pages the file spans (size rounded up to page granularity)
 * @property pagesResident pages currently resident in the page cache
 * @property pageSize the system page size in bytes
 */
public data class Residency(val pagesTotal: Long, val pagesResident: Long, val pageSize: Long) {
    /** Resident bytes (`pagesResident * pageSize`). */
    public fun bytesResident(): Long = pagesResident * pageSize

    /** Total bytes at page granularity (`pagesTotal * pageSize`). */
    public fun bytesTotal(): Long = pagesTotal * pageSize

    /** Resident fraction in `[0.0, 1.0]`; an empty file is 0.0. */
    public fun ratio(): Double = if (pagesTotal == 0L) 0.0 else pagesResident.toDouble() / pagesTotal

    override fun toString(): String = "Residency($pagesResident/$pagesTotal pages, ${bytesResident()} bytes)"
}
