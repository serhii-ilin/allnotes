package org.qosp.notes.data.sync.fs.vault

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves a Markdown link target relative to the directory containing [noteRelativePath].
 * Example: note `Work/Meeting.md`, target `photo.jpg` → `Work/photo.jpg`;
 * target `Meeting/photo.jpg` → `Work/Meeting/photo.jpg`.
 */
object VaultLinkResolver {
    fun resolve(noteRelativePath: String, rawTarget: String): String? {
        val target = URLDecoder.decode(rawTarget.trim(), StandardCharsets.UTF_8.name())
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
}
