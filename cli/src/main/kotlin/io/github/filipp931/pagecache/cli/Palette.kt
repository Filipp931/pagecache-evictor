package io.github.filipp931.pagecache.cli

/** Tiny ANSI color helper; a disabled palette passes text through untouched. */
class Palette(val enabled: Boolean) {
    private fun wrap(code: String, text: String): String = if (enabled) "\u001B[${code}m$text\u001B[0m" else text

    fun bold(text: String): String = wrap("1", text)

    fun red(text: String): String = wrap("31", text)

    fun cyan(text: String): String = wrap("36", text)
}
