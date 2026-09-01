package io.github.filipp931.pagecache.cli

import io.github.filipp931.pagecache.PageCacheOps
import picocli.CommandLine
import java.io.PrintWriter
import java.time.Duration

/** Output format shared by all subcommands. */
@Suppress("EnumEntryName", "ktlint:standard:enum-entry-name-case")
enum class OutputFormat { text, json }

/** Parses human-friendly durations: `500ms`, `10s`, `5m`, `1h`, or bare seconds. */
class DurationConverter : CommandLine.ITypeConverter<Duration> {
    override fun convert(value: String): Duration = parseDuration(value)
}

internal fun parseDuration(value: String): Duration {
    val text = value.trim().lowercase()
    val match =
        Regex("^(\\d+)(ms|s|m|h)?$").matchEntire(text)
            ?: throw IllegalArgumentException("invalid duration \"$value\" (use e.g. 500ms, 10s, 5m, 1h)")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "ms" -> Duration.ofMillis(amount)
        "m" -> Duration.ofMinutes(amount)
        "h" -> Duration.ofHours(amount)
        else -> Duration.ofSeconds(amount)
    }
}

private val BYTE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/** `1536 -> "1.5 KiB"`, `64 shl 20 -> "64.0 MiB"`. */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) {
        return "$bytes B"
    }
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < BYTE_UNITS.size - 1) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(java.util.Locale.ROOT, value, BYTE_UNITS[unit])
}

internal fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

/**
 * Resolves the FFM-backed ops, or prints why this platform cannot run them and
 * returns `null` (the caller exits with 1 — an execution-environment problem,
 * not a usage error).
 */
internal fun opsOrReport(err: PrintWriter, palette: Palette): PageCacheOps? {
    val ops = PageCacheOps.tryCreate()
    if (ops == null) {
        err.println(
            palette.red("error:") +
                " pagecache needs Linux (posix_fadvise/mincore via FFM) — this is ${System.getProperty("os.name")}",
        )
    }
    return ops
}
