package io.github.filipp931.pagecache.cli

/** Minimal JSON rendering — keeps the CLI dependency-free. Values passed to [obj]/[arr] must already be JSON. */
internal object Json {
    fun str(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun arr(items: Iterable<String>): String = items.joinToString(",", "[", "]")

    fun obj(fields: List<Pair<String, String>>): String = fields.joinToString(",", "{", "}") { (name, value) -> "${str(name)}:$value" }

    fun obj(vararg fields: Pair<String, String>): String = obj(fields.toList())

    fun num(value: Int): String = value.toString()

    fun num(value: Long): String = value.toString()

    fun num(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    fun bool(value: Boolean): String = value.toString()
}
