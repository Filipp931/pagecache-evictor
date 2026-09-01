import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    application
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_22
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    kapt(libs.picocli.codegen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

kapt {
    arguments {
        arg("project", "pagecache")
    }
}

application {
    mainClass = "io.github.filipp931.pagecache.cli.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.processResources {
    val projectVersion = version.toString()
    inputs.property("version", projectVersion)
    filesMatching("pagecache-version.txt") {
        expand("version" to projectVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // keep test files off tmpfs — see the core build file for the rationale
    val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
}

tasks.shadowJar {
    archiveBaseName = "pagecache"
    archiveClassifier = ""
    manifest {
        // Lets `java -jar pagecache.jar` perform FFM downcalls on JDK 24+ without warnings.
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

graalvmNative {
    toolchainDetection = false
    binaries {
        named("main") {
            imageName = "pagecache"
            mainClass = application.mainClass
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
    }
}
