package io.github.filipp931.pagecache.cli

import io.github.filipp931.pagecache.PageCacheException
import io.github.filipp931.pagecache.Residency
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "stat",
    description = ["Show how much of each file is resident in the page cache (mincore)."],
)
class StatCommand : Callable<Int> {
    @Parameters(arity = "1..*", paramLabel = "<path>", description = ["files to measure"])
    lateinit var paths: List<Path>

    @Option(names = ["--format"], description = ["output format: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})"])
    var format: OutputFormat = OutputFormat.text

    @ParentCommand
    private lateinit var parent: PagecacheCli

    @Spec
    private lateinit var spec: CommandSpec

    private data class Row(val path: Path, val sizeBytes: Long, val residency: Residency)

    override fun call(): Int {
        val palette = parent.palette()
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val ops = opsOrReport(err, palette) ?: return 1

        val rows = ArrayList<Row>()
        var failures = 0
        for (path in paths) {
            try {
                rows.add(Row(path, Files.size(path), ops.residency(path)))
            } catch (e: PageCacheException) {
                err.println(palette.red("error:") + " ${e.message}")
                failures++
            } catch (e: IOException) {
                err.println(palette.red("error:") + " cannot stat $path: ${e.message}")
                failures++
            }
        }

        when (format) {
            OutputFormat.text -> printText(out, rows, palette)
            OutputFormat.json -> printJson(out, rows, ops.pageSize())
        }
        return if (failures > 0) 1 else 0
    }

    private fun printText(out: java.io.PrintWriter, rows: List<Row>, palette: Palette) {
        if (rows.isEmpty()) {
            return
        }
        val pathWidth = rows.maxOf { it.path.toString().length }.coerceAtLeast(4)
        out.println(palette.bold("%-${pathWidth}s  %10s  %10s  %7s".format("FILE", "SIZE", "RESIDENT", "RATIO")))
        for (row in rows) {
            out.println(
                "%-${pathWidth}s  %10s  %10s  %6.1f%%".format(
                    java.util.Locale.ROOT,
                    row.path.toString(),
                    humanBytes(row.sizeBytes),
                    humanBytes(row.residency.bytesResident()),
                    row.residency.ratio() * 100,
                ),
            )
        }
        if (rows.size > 1) {
            val totalSize = rows.sumOf { it.sizeBytes }
            val totalResident = rows.sumOf { it.residency.bytesResident() }
            val totalPages = rows.sumOf { it.residency.pagesTotal }
            val residentPages = rows.sumOf { it.residency.pagesResident }
            val ratio = if (totalPages == 0L) 0.0 else residentPages.toDouble() / totalPages
            out.println(
                palette.bold(
                    "total: ${rows.size} files, ${humanBytes(totalSize)}, " +
                        "${humanBytes(totalResident)} resident (${"%.1f".format(java.util.Locale.ROOT, ratio * 100)}%)",
                ),
            )
        }
    }

    private fun printJson(out: java.io.PrintWriter, rows: List<Row>, pageSize: Long) {
        out.println(
            Json.obj(
                "pageSize" to Json.num(pageSize),
                "files" to
                    Json.arr(
                        rows.map { row ->
                            Json.obj(
                                "path" to Json.str(row.path.toString()),
                                "sizeBytes" to Json.num(row.sizeBytes),
                                "bytesResident" to Json.num(row.residency.bytesResident()),
                                "pagesTotal" to Json.num(row.residency.pagesTotal),
                                "pagesResident" to Json.num(row.residency.pagesResident),
                                "ratio" to Json.num(row.residency.ratio()),
                            )
                        },
                    ),
            ),
        )
    }
}
