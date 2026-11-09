package org.qosp.notes.data.sync.fs.vault

/**
 * Resolves a Markdown link target relative to the directory containing [noteRelativePath].
 * Example: note `Work/Meeting.md`, target `photo.jpg` → `Work/photo.jpg`;
 * target `Meeting/photo.jpg` → `Work/Meeting/photo.jpg`.
 */
object VaultLinkResolver {
    fun resolve(noteRelativePath: String, rawTarget: String): String? {
        val target = decodePathSegment(rawTarget.trim()) ?: return null
        if (target.isEmpty()) return null
        if (target.contains("://")) return null
        if (target.startsWith('/')) return null

        val notePath = VaultPaths.normalizeNotePath(noteRelativePath)
        val baseDir = VaultPaths.parentPath(notePath)
        val combined = if (baseDir.isEmpty()) target else "$baseDir/$target"
        return normalizeWithinVault(combined)
    }

    fun isVaultResolvableDestination(destination: String): Boolean {
        val d = destination.trim()
        if (d.contains("://")) return false
        return !d.startsWith('/')
    }

    private fun normalizeWithinVault(path: String): String? {
        val segments = path.replace('\\', '/').split('/')
        val out = ArrayDeque<String>()
        for (seg in segments) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg == ".." -> {
                    if (out.isEmpty()) return null
                    out.removeLast()
                }
                else -> {
                    if (seg.contains("..")) return null
                    out.addLast(seg)
                }
            }
        }
        if (out.isEmpty()) return null
        return out.joinToString("/")
    }

    /**
     * Decode `%HH` only; keep literal `+`. Return null on malformed escapes.
     */
    fun decodePathSegment(raw: String): String? {
        if (!raw.contains('%')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16) ?: return null
                out.append(value.toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
