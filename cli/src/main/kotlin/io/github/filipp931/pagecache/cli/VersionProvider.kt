package io.github.filipp931.pagecache.cli

import picocli.CommandLine.IVersionProvider

/** Reads the version stamped into resources by the Gradle build. */
class VersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> {
        val version =
            VersionProvider::class.java
                .getResourceAsStream("/pagecache-version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?: "unknown"
        return arrayOf("pagecache $version")
    }
}
