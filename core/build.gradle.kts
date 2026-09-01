import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// The FFM API is final since JDK 22: build with the current LTS toolchain,
// target 22 so the artifact runs on any modern JVM.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_22
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22
}

dependencies {
    // GraalVM Native Image registration only (PageCacheFeature); never at runtime,
    // so the published artifact stays dependency-free.
    compileOnly(libs.graalvm.nativeimage)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
    // JDK 24+ warns (and will eventually refuse) on native access without this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Route temp files to the repo disk: DONTNEED cannot drop tmpfs pages, and
    // /tmp is tmpfs on a growing number of distros — the residency integration
    // tests would fail there through no fault of the code.
    val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "pagecache-evictor", version.toString())

    pom {
        name = "pagecache-evictor"
        description = "Surgical Linux page-cache control for the JVM — posix_fadvise and mincore via FFM, no JNI"
        url = "https://github.com/Filipp931/pagecache-evictor"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "Filipp931"
                name = "Filipp931"
                url = "https://github.com/Filipp931"
            }
        }
        scm {
            url = "https://github.com/Filipp931/pagecache-evictor"
            connection = "scm:git:git://github.com/Filipp931/pagecache-evictor.git"
            developerConnection = "scm:git:ssh://git@github.com/Filipp931/pagecache-evictor.git"
        }
    }
}
