package io.github.filipp931.pagecache.cli

import io.github.filipp931.pagecache.CycleStats
import io.github.filipp931.pagecache.PageCacheEvictor
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable

@Command(
    name = "evict",
    description = [
        "Run one age-based eviction sweep: advise DONTNEED on matching files " +
            "older than --keep-recent. --dry-run measures instead of evicting.",
    ],
)
class EvictCommand : Callable<Int> {
    @Option(names = ["--dir"], required = true, description = ["directory to sweep (repeatable; not recursive)"])
    lateinit var directories: List<Path>

    @Option(names = ["--suffix"], description = ["only file names ending with this (repeatable; default: all files)"])
    var suffixes: List<String> = emptyList()

    @Option(
        names = ["--keep-recent"],
        converter = [DurationConverter::class],
        description = ["files modified within this window are kept (default: \${DEFAULT-VALUE})"],
    )
    var keepRecent: Duration = Duration.ofMinutes(2)

    @Option(
        names = ["--throttle"],
        converter = [DurationConverter::class],
        description = ["pause between files to avoid kernel lock bursts (default: \${DEFAULT-VALUE})"],
    )
    var throttle: Duration = Duration.ZERO

    @Option(names = ["--dry-run"], description = ["measure current residency instead of evicting"])
    var dryRun: Boolean = false

    @Option(names = ["--format"], description = ["output format: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})"])
    var format: OutputFormat = OutputFormat.text

    @ParentCommand
    private lateinit var parent: PagecacheCli

    @Spec
    private lateinit var spec: CommandSpec

    override fun call(): Int {
        val palette = parent.palette()
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val ops = opsOrReport(err, palette) ?: return 1

        val builder =
            PageCacheEvictor
                .builder(ops)
                .directories(directories)
                .keepRecent(keepRecent)
                .throttleBetweenFiles(throttle)
                .dryRun(dryRun)
        try {
            suffixes.forEach(builder::fileSuffix)
        } catch (e: IllegalArgumentException) {
            // e.g. a blank --suffix: a usage error, not an execution failure
            err.println(palette.red("error:") + " ${e.message}")
            return 2
        }
        val stats = builder.build().runOnce()

        when (format) {
            OutputFormat.text -> printText(out, stats, palette)
            OutputFormat.json -> printJson(out, stats)
        }
        return if (stats.failed > 0) 1 else 0
    }

    private fun printText(out: java.io.PrintWriter, stats: CycleStats, palette: Palette) {
        val verb = if (dryRun) "would evict" else "evicted"
        val prefix = if (dryRun) palette.cyan("dry run: ") else ""
        val dirWord = if (directories.size == 1) "directory" else "directories"
        out.println(
            prefix +
                "swept ${directories.size} $dirWord: " +
                "scanned ${stats.scanned}, $verb ${stats.evicted} (${humanBytes(stats.bytesEvicted)}), " +
                "kept ${stats.keptRecent} recent, ${stats.failed} failed",
        )
    }

    private fun printJson(out: java.io.PrintWriter, stats: CycleStats) {
        out.println(
            Json.obj(
                "dryRun" to Json.bool(dryRun),
                "scanned" to Json.num(stats.scanned),
                "evicted" to Json.num(stats.evicted),
                "bytesEvicted" to Json.num(stats.bytesEvicted),
                "keptRecent" to Json.num(stats.keptRecent),
                "failed" to Json.num(stats.failed),
            ),
        )
    }
}
