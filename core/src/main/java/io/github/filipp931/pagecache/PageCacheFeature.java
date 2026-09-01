package io.github.filipp931.pagecache;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

/**
 * GraalVM Native Image support: registers this library's libc downcalls so the
 * image can link them at run time (FFM downcall stubs must be known at build
 * time).
 *
 * <p>Activated automatically via {@code META-INF/native-image/.../native-image.properties}
 * whenever this jar is on the image classpath — applications embedding
 * pagecache-evictor need no extra configuration on GraalVM for JDK 25+.
 *
 * <p>The GraalVM API is a {@code compileOnly} dependency: on a regular JVM this
 * class is never loaded and the library stays dependency-free.
 */
public final class PageCacheFeature implements Feature {

    @Override
    public void duringSetup(DuringSetupAccess access) {
        NativeCalls.forEachDowncall(
                (descriptor, options) -> RuntimeForeignAccess.registerForDowncall(descriptor, (Object[]) options));
    }

    @Override
    public String getDescription() {
        return "Registers pagecache-evictor libc downcalls (posix_fadvise, mincore, ...) for FFM";
    }
}
