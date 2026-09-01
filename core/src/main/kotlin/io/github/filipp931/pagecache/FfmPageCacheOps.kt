package io.github.filipp931.pagecache

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/** The real, Linux-only implementation on top of [NativeCalls]. */
internal class FfmPageCacheOps private constructor(private val libc: NativeCalls) : PageCacheOps() {
    private val pageSize: Long = libc.pageSize().toLong()

    override fun pageSize(): Long = pageSize

    override fun advise(file: Path, offset: Long, length: Long, advice: Advice) {
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        require(length >= 0) { "length must be >= 0, got $length" }
        Arena.ofConfined().use { arena ->
            Scratch(arena).advise(file, offset, length, advice)
        }
    }

    override fun residency(file: Path): Residency = Arena.ofConfined().use { arena ->
        Scratch(arena).residency(file)
    }

    override fun openSession(): PageCacheSession = FfmSession()

    /**
     * Reusable native scratch bound to one confined arena: a single errno
     * capture segment plus monotonically-grown path and mincore buffers.
     * A sweep of thousands of files touches the same few native blocks
     * instead of allocating (and, per-call, mapping/unmapping) per file.
     */
    private inner class Scratch(private val arena: Arena) {
        private val errnoState: MemorySegment = libc.allocateErrnoState(arena)

        // Both buffers grow monotonically to the largest need seen; superseded
        // blocks stay allocated until the arena closes, but doubling keeps the
        // total waste under 2x the peak size.
        private var pathBuf: MemorySegment = MemorySegment.NULL
        private var vec: MemorySegment = MemorySegment.NULL

        fun advise(file: Path, offset: Long, length: Long, advice: Advice) {
            val fd = openOrThrow(file)
            try {
                val rc = libc.posixFadvise(fd, offset, length, advice.nativeValue)
                if (rc != 0) {
                    throw PageCacheException("posix_fadvise(${advice.name}) failed for $file: ${libc.describeErrno(rc)}", rc)
                }
            } finally {
                libc.close(fd)
            }
        }

        fun residency(file: Path): Residency {
            val size =
                try {
                    Files.size(file)
                } catch (e: IOException) {
                    throw PageCacheException("cannot stat $file: ${e.message}", 0, e)
                }
            if (size == 0L) {
                return Residency(0, 0, pageSize)
            }
            val fd = openOrThrow(file)
            try {
                val address = libc.mmapProtNone(errnoState, size, fd)
                if (libc.isMapFailed(address)) {
                    throw PageCacheException("mmap failed for $file: ${describeErrnoState()}", libc.errno(errnoState))
                }
                try {
                    val pages = (size + pageSize - 1) / pageSize
                    val vec = vecFor(pages)
                    if (libc.mincore(errnoState, address, size, vec) != 0) {
                        throw PageCacheException("mincore failed for $file: ${describeErrnoState()}", libc.errno(errnoState))
                    }
                    var resident = 0L
                    for (page in 0 until pages) {
                        // LSB of each vector byte = "resident"; other bits are undefined
                        if (vec.get(ValueLayout.JAVA_BYTE, page).toInt() and 1 == 1) {
                            resident++
                        }
                    }
                    return Residency(pages, resident, pageSize)
                } finally {
                    libc.munmap(address, size)
                }
            } finally {
                libc.close(fd)
            }
        }

        private fun openOrThrow(file: Path): Int {
            val fd = libc.openReadOnly(errnoState, pathCString(file))
            if (fd < 0) {
                throw PageCacheException("open failed for $file: ${describeErrnoState()}", libc.errno(errnoState))
            }
            return fd
        }

        private fun pathCString(file: Path): MemorySegment {
            val path = file.toString()
            // worst-case UTF-8 expansion for a Java string is 3 bytes per char, plus NUL
            val needed = path.length.toLong() * 3 + 1
            if (pathBuf.byteSize() < needed) {
                pathBuf = arena.allocate(maxOf(needed, pathBuf.byteSize() * 2, MIN_BUF_BYTES))
            }
            pathBuf.setString(0, path)
            return pathBuf
        }

        private fun vecFor(pages: Long): MemorySegment {
            if (vec.byteSize() < pages) {
                vec = arena.allocate(maxOf(pages, vec.byteSize() * 2, MIN_BUF_BYTES))
            }
            return vec
        }

        private fun describeErrnoState(): String = libc.describeErrno(libc.errno(errnoState))
    }

    /** Sweep-scoped session: one arena and one [Scratch] for the whole sweep. */
    private inner class FfmSession : PageCacheSession {
        private val arena = Arena.ofConfined()
        private val scratch = Scratch(arena)

        override fun tryAdvise(file: Path, advice: Advice): Boolean = try {
            scratch.advise(file, 0, 0, advice)
            true
        } catch (_: PageCacheException) {
            false
        }

        override fun residency(file: Path): Residency = scratch.residency(file)

        override fun close() = arena.close()
    }

    internal companion object {
        private const val MIN_BUF_BYTES = 4096L

        // Resolved once; every PageCacheOps instance shares the same immutable handles.
        private val cachedLibc: NativeCalls? by lazy {
            val os = System.getProperty("os.name") ?: ""
            if (!os.lowercase().contains("linux")) {
                null
            } else {
                try {
                    NativeCalls.load()
                } catch (_: Throwable) {
                    // missing symbols, restricted native access, exotic libc — all mean "unsupported"
                    null
                }
            }
        }

        fun tryCreate(): FfmPageCacheOps? = cachedLibc?.let(::FfmPageCacheOps)
    }
}
