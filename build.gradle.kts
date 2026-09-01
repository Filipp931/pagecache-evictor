plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "io.github.filipp931"
    // Releases pass the version from the git tag: ./gradlew -Pversion=X.Y.Z ...
    if (version == Project.DEFAULT_VERSION) {
        version = "0.1.0-SNAPSHOT"
    }
}

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
