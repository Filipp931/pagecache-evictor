package io.github.filipp931.pagecache

/**
 * Result of one [PageCacheEvictor.runOnce] sweep.
 *
 * A plain class (not a data class) on purpose: fields will grow over releases,
 * and freezing `copy`/`componentN` into the binary API would make every
 * addition a breaking change.
 *
 * @property scanned files that matched the suffix filter and were examined
 * @property evicted files advised out (or, in dry-run mode, files that would have been)
 * @property bytesEvicted in a real run: the sizes of evicted files (an upper bound —
 *   fadvise gives no byte count back); in dry-run mode: measured resident bytes
 * @property keptRecent files skipped because they are younger than `keepRecent`
 * @property failed files (or directories) that could not be processed
 * @property skipped `true` when the sweep was skipped because a previous cycle
 *   was still running; all counters are zero in that case
 */
public class CycleStats internal constructor(
    public val scanned: Int,
    public val evicted: Int,
    public val bytesEvicted: Long,
    public val keptRecent: Int,
    public val failed: Int,
    public val skipped: Boolean,
) {
    override fun toString(): String = if (skipped) {
        "CycleStats(skipped: previous cycle still running)"
    } else {
        "CycleStats(scanned=$scanned, evicted=$evicted, bytesEvicted=$bytesEvicted, keptRecent=$keptRecent, failed=$failed)"
    }

    internal companion object {
        internal val SKIPPED: CycleStats = CycleStats(0, 0, 0, 0, 0, skipped = true)
    }
}
