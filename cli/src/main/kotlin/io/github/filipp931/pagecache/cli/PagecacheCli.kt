package io.github.filipp931.pagecache.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.ScopeType
import picocli.CommandLine.Spec

/** Root command: `pagecache <subcommand>`. */
@Command(
    name = "pagecache",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    description = [
        "Surgical Linux page-cache control: residency stats, targeted eviction " +
            "and raw fadvise — posix_fadvise/mincore via FFM, no JNI.",
    ],
    subcommands = [
        StatCommand::class,
        EvictCommand::class,
        AdviseCommand::class,
    ],
)
class PagecacheCli : Runnable {
    @Option(
        names = ["--no-color"],
        scope = ScopeType.INHERIT,
        description = ["disable ANSI colors (also honours the NO_COLOR env var)"],
    )
    var noColor: Boolean = false

    @Spec
    lateinit var spec: CommandSpec

    /** Colors only on interactive terminals, always overridable. */
    fun palette(): Palette = Palette(!noColor && System.getenv("NO_COLOR") == null && System.console() != null)

    override fun run() {
        // No subcommand: picocli prints the message plus usage and exits with code 2.
        throw ParameterException(spec.commandLine(), "specify a subcommand")
    }
}
