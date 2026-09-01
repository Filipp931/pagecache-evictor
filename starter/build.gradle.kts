import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.maven.publish)
}

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
    api(project(":core"))

    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.slf4j.api)
    kapt(platform(libs.spring.boot.dependencies))
    kapt(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "pagecache-evictor-spring-boot-starter", version.toString())

    pom {
        name = "pagecache-evictor-spring-boot-starter"
        description = "Spring Boot autoconfiguration for pagecache-evictor: scheduled Linux page-cache eviction sweeps"
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
