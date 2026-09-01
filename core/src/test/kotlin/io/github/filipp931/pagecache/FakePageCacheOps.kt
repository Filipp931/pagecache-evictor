package io.github.filipp931.pagecache

import java.nio.file.Path

/** In-memory [PageCacheOps] for platform-independent evictor tests. */
internal class FakePageCacheOps : PageCacheOps() {
    val advised = mutableListOf<Pair<Path, Advice>>()
    val residencyCalls = mutableListOf<Path>()
    var adviseHook: (Path) -> Unit = {}
    var failFor: (Path) -> Boolean = { false }
    var residencyFor: (Path) -> Residency = { Residency(pagesTotal = 16, pagesResident = 8, pageSize = 4096) }

    override fun pageSize(): Long = 4096

    override fun advise(file: Path, offset: Long, length: Long, advice: Advice) {
        adviseHook(file)
        if (failFor(file)) {
            throw PageCacheException("fake failure for $file", 22)
        }
        advised.add(file to advice)
    }

    override fun residency(file: Path): Residency {
        residencyCalls.add(file)
        if (failFor(file)) {
            throw PageCacheException("fake failure for $file", 22)
        }
        return residencyFor(file)
    }
}
