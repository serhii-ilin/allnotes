package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment

object VaultAttachments {
    private val imageLinkRegex = Regex("""!\[[^\]]*]\(([^)]+)\)""")
    private val fileLinkRegex = Regex("""\[[^\]]+]\(([^)]+)\)""")

    fun parseFromBody(body: String, noteRelativePath: String): List<Attachment> {
        val found = linkedMapOf<String, Attachment>()
        fun ingest(target: String) {
            if (target.startsWith("#") || target.startsWith("mailto:", ignoreCase = true)) return
            if (target.contains("://")) return
            val vaultPath = runCatching {
                VaultLinkResolver.resolve(noteRelativePath, normalizeLinkTarget(target))
            }.getOrNull() ?: return
            val stored = VaultPaths.vaultRelative(vaultPath)
            if (stored in found) return
            val type = guessType(vaultPath)
            found[stored] = Attachment(type = type, path = stored, description = VaultPaths.fileName(vaultPath))
        }
        forEachImageTarget(body) { ingest(it) }
        fileLinkRegex.findAll(body).forEach { ingest(it.groupValues[1]) }
        return found.values.toList()
    }

    fun relativeLinkFromNote(noteRelativePath: String, attachmentVaultPath: String): String {
        val att = VaultPaths.normalize(attachmentVaultPath)
        val parent = VaultPaths.parentPath(noteRelativePath)
        return if (parent.isEmpty()) {
            att
        } else if (att.startsWith("$parent/")) {
            att.removePrefix("$parent/")
        } else {
            val noteDir = VaultPaths.parentPath(noteRelativePath)
            val fromRoot = att
            if (noteDir.isEmpty()) fromRoot else fromRoot.removePrefix("$noteDir/")
        }
    }

    fun encodeLinkTarget(relativeTarget: String): String =
        relativeTarget.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }

    fun markdownImageLink(relativeTarget: String, description: String): String {
        val alt = description.ifBlank { "image" }
        val encoded = encodeLinkTarget(relativeTarget)
        return if (relativeTarget.contains(' ') || relativeTarget.contains('%')) {
            "![$alt](<$encoded>)"
        } else {
            "![$alt]($encoded)"
        }
    }

    /**
     * Removes only **image** markdown links for [removedVaultRelPaths].
     * Skips fenced code blocks and inline `` `code` `` spans.
     */
    fun removeVaultLinksForPaths(
        body: String,
        noteRelativePath: String,
        removedVaultRelPaths: Set<String>,
    ): String {
        if (removedVaultRelPaths.isEmpty()) return body
        val out = StringBuilder()
        var inFence = false
        body.lineSequence().forEach { line ->
            if (line.trim().startsWith("```")) {
                inFence = !inFence
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
                return@forEach
            }
            val processed = if (inFence) line else removeImageLinksOutsideInlineCode(line, noteRelativePath, removedVaultRelPaths)
            if (out.isNotEmpty()) out.append('\n')
            out.append(processed)
        }
        return out.toString()
    }

    private fun removeImageLinksOutsideInlineCode(
        line: String,
        noteRelativePath: String,
        removedVaultRelPaths: Set<String>,
    ): String {
        val parts = line.split('`')
        return buildString {
            parts.forEachIndexed { idx, part ->
                if (idx > 0) append('`')
                if (idx % 2 == 1) {
                    append(part)
                } else {
                    append(
                        imageLinkRegex.replace(part) { match ->
                            val target = normalizeLinkTarget(match.groupValues[1])
                            if (isRemovedVaultImageTarget(noteRelativePath, target, removedVaultRelPaths)) {
                                ""
                            } else {
                                match.value
                            }
                        },
                    )
                }
            }
        }
    }

    private fun isRemovedVaultImageTarget(
        noteRelativePath: String,
        rawTarget: String,
        removedVaultRelPaths: Set<String>,
    ): Boolean {
        val resolved = VaultLinkResolver.resolve(noteRelativePath, rawTarget) ?: return false
        return VaultPaths.vaultRelative(resolved) in removedVaultRelPaths
    }

  /** Path only — strips optional `"title"` / `(title)` after the URL target. */
    fun normalizeLinkTarget(raw: String): String {
        var t = raw.trim()
        if (t.startsWith('<')) {
            val close = t.indexOf('>')
            if (close > 0) {
                return t.substring(1, close).trim()
            }
        }
        t = t.substringBefore('"').trim()
        t = t.substringBefore('(').trim()
        return t
    }

    private inline fun forEachImageTarget(body: String, consume: (String) -> Unit) {
        imageLinkRegex.findAll(body).forEach { consume(normalizeLinkTarget(it.groupValues[1])) }
    }

    /**
     * Removes markdown links that resolve to paths under [assetDirRelative] (e.g. after a note rename).
     */
    fun stripLinksUnderAssetDirectory(
        body: String,
        notePathForResolve: String,
        assetDirRelative: String,
    ): String {
        val prefix = VaultPaths.normalize(assetDirRelative)
        val parsed = parseFromBody(body, notePathForResolve)
        val toRemove = parsed
            .filter { VaultPaths.toVaultRelative(it.path).startsWith("$prefix/") || 
                VaultPaths.toVaultRelative(it.path) == prefix }
            .map { it.path }
            .toSet()
        return if (toRemove.isEmpty()) body else removeVaultLinksForPaths(body, notePathForResolve, toRemove)
    }

    fun mergeAttachmentLinks(body: String, noteRelativePath: String, attachments: List<Attachment>): String {
        val existing = parseFromBody(body, noteRelativePath).map { it.path }.toSet()
        val sb = StringBuilder(body.trimEnd())
        attachments.filter { VaultPaths.isVaultRelativeAttachment(it.path) }.forEach { att ->
            if (att.path in existing) return@forEach
            val vaultPath = VaultPaths.toVaultRelative(att.path)
            val rel = relativeLinkFromNote(noteRelativePath, vaultPath)
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(markdownImageLink(rel, att.description))
        }
        return sb.toString()
    }

    private fun guessType(path: String): Attachment.Type {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") -> Attachment.Type.IMAGE
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> Attachment.Type.VIDEO
            lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg") -> Attachment.Type.AUDIO
            else -> Attachment.Type.GENERIC
        }
    }
}
