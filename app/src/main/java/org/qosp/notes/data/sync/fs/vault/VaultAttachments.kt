package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment

object VaultAttachments {
    private val imageLinkRegex = Regex("""!\[[^\]]*]\(([^)\s"]+)(?:\s+"[^"]*")?\)""")
    private val fileLinkRegex = Regex("""\[[^\]]+]\(([^)\s"]+)(?:\s+"[^"]*")?\)""")

    fun parseFromBody(body: String, noteRelativePath: String): List<Attachment> {
        val found = linkedMapOf<String, Attachment>()
        fun ingest(target: String) {
            if (target.contains("://")) return
            val vaultPath = VaultLinkResolver.resolve(noteRelativePath, target) ?: return
            val stored = VaultPaths.vaultRelative(vaultPath)
            if (stored in found) return
            val type = guessType(vaultPath)
            found[stored] = Attachment(type = type, path = stored, description = VaultPaths.fileName(vaultPath))
        }
        imageLinkRegex.findAll(body).forEach { ingest(it.groupValues[1]) }
        fileLinkRegex.findAll(body).forEach { ingest(it.groupValues[1]) }
        return found.values.toList()
    }

    fun relativeLinkFromNote(noteRelativePath: String, attachmentVaultPath: String): String {
        val att = VaultPaths.normalize(attachmentVaultPath)
        val parent = VaultPaths.parentPath(noteRelativePath)
        return if (parent.isEmpty()) {
            att.substringAfterLast('/')
        } else if (att.startsWith("$parent/")) {
            att.removePrefix("$parent/")
        } else {
            // path relative from note directory
            val noteDir = VaultPaths.parentPath(noteRelativePath)
            val fromRoot = att
            if (noteDir.isEmpty()) fromRoot else fromRoot.removePrefix("$noteDir/")
        }
    }

    fun markdownImageLink(relativeTarget: String, description: String): String {
        val alt = description.ifBlank { "image" }
        return "![$alt]($relativeTarget)"
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
