package io.github.filipp931.pagecache.cli

import java.io.PrintWriter
import java.io.StringWriter

internal data class CliResult(val exitCode: Int, val out: String, val err: String)

internal fun runCli(vararg args: String): CliResult {
    val out = StringWriter()
    val err = StringWriter()
    val commandLine = newCommandLine()
    commandLine.out = PrintWriter(out)
    commandLine.err = PrintWriter(err)
    val exitCode = commandLine.execute(*args)
    return CliResult(exitCode, out.toString(), err.toString())
}
