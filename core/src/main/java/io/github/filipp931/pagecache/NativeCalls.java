package io.github.filipp931.pagecache;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.function.BiConsumer;

/**
 * FFM downcall bindings for the handful of libc symbols this library needs.
 *
 * <p>Deliberately written in Java: {@code MethodHandle.invokeExact} is a
 * signature-polymorphic call, and Java is the one JVM language where its
 * call-site typing is guaranteed to be exact. Everything above this class is
 * Kotlin.
 *
 * <p>All wrappers are thin and allocation-free; callers own the errno capture
 * segment (see {@link Linker.Option#captureCallState}) so a sweep can reuse
 * one segment for thousands of calls.
 */
final class NativeCalls {

    static final int O_RDONLY = 0;
    // generic Linux ABI values (x86-64, aarch64)
    static final int O_CLOEXEC = 0x80000;
    // O_NONBLOCK guards the public advise/residency API against pathological
    // non-regular files: open(2) on a reader-side FIFO with no writer blocks
    // forever otherwise. On regular files the flag is a no-op.
    static final int O_NONBLOCK = 0x800;
    static final int PROT_NONE = 0;
    static final int MAP_SHARED = 1;
    private static final long MAP_FAILED = -1L;

    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final Linker.Option[] NO_OPTIONS = {};

    // Descriptors are shared with PageCacheFeature, which registers every
    // downcall for GraalVM Native Image with EXACTLY these options.
    private static final FunctionDescriptor OPEN_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    // open(2) is variadic; firstVariadicArg keeps the call ABI-correct on
    // x86-64 (the %al vector-register count) even though we pass no mode arg.
    private static final Linker.Option[] OPEN_OPTIONS = {
        CAPTURE_ERRNO, Linker.Option.firstVariadicArg(2)
    };
    private static final FunctionDescriptor CLOSE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor FADVISE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT);
    private static final FunctionDescriptor MMAP_DESC = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG);
    private static final Linker.Option[] MMAP_OPTIONS = {CAPTURE_ERRNO};
    private static final FunctionDescriptor MINCORE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
    private static final Linker.Option[] MINCORE_OPTIONS = {CAPTURE_ERRNO};
    private static final FunctionDescriptor MUNMAP_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final FunctionDescriptor GETPAGESIZE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT);
    private static final FunctionDescriptor STRERROR_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    private final MethodHandle open;
    private final MethodHandle close;
    private final MethodHandle posixFadvise;
    private final MethodHandle mmap;
    private final MethodHandle mincore;
    private final MethodHandle munmap;
    private final MethodHandle getpagesize;
    private final MethodHandle strerror; // optional; null when not resolvable
    private final StructLayout errnoLayout;
    private final VarHandle errnoHandle;

    private NativeCalls(
            MethodHandle open,
            MethodHandle close,
            MethodHandle posixFadvise,
            MethodHandle mmap,
            MethodHandle mincore,
            MethodHandle munmap,
            MethodHandle getpagesize,
            MethodHandle strerror,
            StructLayout errnoLayout,
            VarHandle errnoHandle) {
        this.open = open;
        this.close = close;
        this.posixFadvise = posixFadvise;
        this.mmap = mmap;
        this.mincore = mincore;
        this.munmap = munmap;
        this.getpagesize = getpagesize;
        this.strerror = strerror;
        this.errnoLayout = errnoLayout;
        this.errnoHandle = errnoHandle;
    }

    /**
     * Hands every (descriptor, linker options) pair to {@code registrar} —
     * the single source of truth shared between {@link #load()} and the
     * GraalVM Native Image registration in {@link PageCacheFeature}.
     */
    static void forEachDowncall(BiConsumer<FunctionDescriptor, Linker.Option[]> registrar) {
        registrar.accept(OPEN_DESC, OPEN_OPTIONS);
        registrar.accept(CLOSE_DESC, NO_OPTIONS);
        registrar.accept(FADVISE_DESC, NO_OPTIONS);
        registrar.accept(MMAP_DESC, MMAP_OPTIONS);
        registrar.accept(MINCORE_DESC, MINCORE_OPTIONS);
        registrar.accept(MUNMAP_DESC, NO_OPTIONS);
        registrar.accept(GETPAGESIZE_DESC, NO_OPTIONS);
        registrar.accept(STRERROR_DESC, NO_OPTIONS);
    }

    /**
     * Resolves every required symbol eagerly so unsupported platforms fail here,
     * once, instead of on the first call.
     *
     * @throws RuntimeException or Error if any required symbol cannot be linked
     */
    static NativeCalls load() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        StructLayout errnoLayout = Linker.Option.captureStateLayout();
        VarHandle errnoHandle = errnoLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        MethodHandle open = linker.downcallHandle(find(lookup, "open"), OPEN_DESC, OPEN_OPTIONS);
        MethodHandle close = linker.downcallHandle(find(lookup, "close"), CLOSE_DESC);
        // posix_fadvise returns the error code directly (no errno involved)
        MethodHandle posixFadvise =
                linker.downcallHandle(find(lookup, "posix_fadvise"), FADVISE_DESC);
        MethodHandle mmap = linker.downcallHandle(find(lookup, "mmap"), MMAP_DESC, MMAP_OPTIONS);
        MethodHandle mincore =
                linker.downcallHandle(find(lookup, "mincore"), MINCORE_DESC, MINCORE_OPTIONS);
        MethodHandle munmap = linker.downcallHandle(find(lookup, "munmap"), MUNMAP_DESC);
        MethodHandle getpagesize =
                linker.downcallHandle(find(lookup, "getpagesize"), GETPAGESIZE_DESC);
        MethodHandle strerror = lookup.find("strerror")
                .map(sym -> linker.downcallHandle(sym, STRERROR_DESC))
                .orElse(null);

        return new NativeCalls(
                open, close, posixFadvise, mmap, mincore, munmap, getpagesize, strerror,
                errnoLayout, errnoHandle);
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("libc symbol not found: " + name));
    }

    MemorySegment allocateErrnoState(SegmentAllocator allocator) {
        return allocator.allocate(errnoLayout);
    }

    int errno(MemorySegment errnoState) {
        return (int) errnoHandle.get(errnoState, 0L);
    }

    /** Returns a file descriptor, or a negative value (errno in {@code errnoState}). */
    int openReadOnly(MemorySegment errnoState, MemorySegment pathCString) {
        try {
            return (int) open.invokeExact(
                    errnoState, pathCString, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int close(int fd) {
        try {
            return (int) close.invokeExact(fd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns 0 on success or the error code (posix_fadvise does not use errno). */
    int posixFadvise(int fd, long offset, long length, int advice) {
        try {
            return (int) posixFadvise.invokeExact(fd, offset, length, advice);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Maps {@code length} bytes of {@code fd} with PROT_NONE; check {@link #isMapFailed}. */
    MemorySegment mmapProtNone(MemorySegment errnoState, long length, int fd) {
        try {
            return (MemorySegment) mmap.invokeExact(
                    errnoState, MemorySegment.NULL, length, PROT_NONE, MAP_SHARED, fd, 0L);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    boolean isMapFailed(MemorySegment address) {
        return address.address() == MAP_FAILED;
    }

    /** Returns 0 on success, -1 on failure (errno in {@code errnoState}). */
    int mincore(MemorySegment errnoState, MemorySegment address, long length, MemorySegment vec) {
        try {
            return (int) mincore.invokeExact(errnoState, address, length, vec);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int munmap(MemorySegment address, long length) {
        try {
            return (int) munmap.invokeExact(address, length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int pageSize() {
        try {
            return (int) getpagesize.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Human-readable errno, e.g. {@code "Bad file descriptor (errno 9)"}. */
    String describeErrno(int errno) {
        if (strerror != null) {
            try {
                MemorySegment message = (MemorySegment) strerror.invokeExact(errno);
                if (message.address() != 0) {
                    return message.reinterpret(4096).getString(0) + " (errno " + errno + ")";
                }
            } catch (Throwable ignored) {
                // fall through to the numeric form
            }
        }
        return "errno " + errno;
    }

    /** Smoke check used by tests: allocates and reads back an errno state segment. */
    int selfTestErrnoState() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = allocateErrnoState(arena);
            return errno(state);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("native call failed", t);
    }
}
