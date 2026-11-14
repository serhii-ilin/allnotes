package org.qosp.notes.data.sync.fs.vault

/** Parses YAML inline lists such as `["a, b", c]` without splitting on commas inside quotes. */
object VaultYamlTags {
    fun parseInlineList(inner: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            while (i < inner.length && (inner[i].isWhitespace() || inner[i] == ',')) i++
            if (i >= inner.length) break
            when (inner[i]) {
                '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < inner.length) {
                        when (val c = inner[i]) {
                            '\\' -> {
                                if (i + 1 < inner.length) {
                                    sb.append(inner[i + 1])
                                    i += 2
                                } else {
                                    i++
                                }
                            }
                            '"' -> {
                                i++
                                break
                            }
                            else -> {
                                sb.append(c)
                                i++
                            }
                        }
                    }
                    if (sb.isNotEmpty()) out.add(sb.toString())
                }
                else -> {
                    val start = i
                    while (i < inner.length && inner[i] != ',') i++
                    out.add(inner.substring(start, i).trim().trim('"'))
                }
            }
        }
        return out
    }
}
