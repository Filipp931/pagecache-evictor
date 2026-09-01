package io.github.filipp931.pagecache.cli

import picocli.CommandLine
import kotlin.system.exitProcess

/** Builds the command line exactly as `main` runs it; tests use the same factory. */
fun newCommandLine(): CommandLine = CommandLine(PagecacheCli()).setCaseInsensitiveEnumValuesAllowed(true)

fun main(args: Array<String>) {
    exitProcess(newCommandLine().execute(*args))
}
