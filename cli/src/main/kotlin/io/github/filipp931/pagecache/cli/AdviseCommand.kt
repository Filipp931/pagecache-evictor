package io.github.filipp931.pagecache.cli

import io.github.filipp931.pagecache.Advice
import io.github.filipp931.pagecache.PageCacheException
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "advise",
    description = ["Apply a raw posix_fadvise to files."],
)
class AdviseCommand : Callable<Int> {
    @Option(
        names = ["--advice"],
        required = true,
        description = ["one of: \${COMPLETION-CANDIDATES} (case-insensitive)"],
    )
    lateinit var advice: Advice

    @Option(names = ["--format"], description = ["output format: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})"])
    var format: OutputFormat = OutputFormat.text

    @Parameters(arity = "1..*", paramLabel = "<path>", description = ["files to advise"])
    lateinit var paths: List<Path>

    @ParentCommand
    private lateinit var parent: PagecacheCli

    @Spec
    private lateinit var spec: CommandSpec

    override fun call(): Int {
        val palette = parent.palette()
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val ops = opsOrReport(err, palette) ?: return 1

        var failures = 0
        for (path in paths) {
            try {
                ops.advise(path, advice)
            } catch (e: PageCacheException) {
                err.println(palette.red("error:") + " ${e.message}")
                failures++
            }
        }
        val applied = paths.size - failures
        when (format) {
            OutputFormat.text ->
                out.println(
                    "applied ${advice.name} to $applied ${plural(applied, "file")}" +
                        if (failures > 0) ", $failures failed" else "",
                )
            OutputFormat.json ->
                out.println(
                    Json.obj(
                        "advice" to Json.str(advice.name),
                        "applied" to Json.num(applied),
                        "failed" to Json.num(failures),
                    ),
                )
        }
        return if (failures > 0) 1 else 0
    }
}
