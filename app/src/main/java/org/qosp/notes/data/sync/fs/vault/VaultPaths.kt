package org.qosp.notes.data.sync.fs.vault

import java.text.Normalizer

object VaultPaths {
    private val illegalChars = Regex("""[\\/:*?"<>|\x00-\x1f]""")
    private const val MAX_SEGMENT_LENGTH = 120

    /** Normalizes to forward slashes, no leading slash, collapses repeated slashes. */
    fun normalize(relativePath: String): String {
        val cleaned = relativePath
            .replace('\\', '/')
            .trim()
            .trimStart('/')
        return cleaned
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/") { decodeTraversal(it) }
    }

    fun hasParentSegment(relativePath: String): Boolean =
        normalize(relativePath).split('/').any { it == ".." }

    fun normalizeNotePath(relativePath: String): String {
        val norm = normalize(relativePath)
        require(!hasParentSegment(relativePath)) { "Path traversal rejected: $relativePath" }
        return norm
    }

    fun parentPath(relativePath: String): String {
        val norm = normalizeNotePath(relativePath)
        val idx = norm.lastIndexOf('/')
        return if (idx < 0) "" else norm.substring(0, idx)
    }

    fun fileName(relativePath: String): String {
        val norm = normalizeNotePath(relativePath)
        return norm.substringAfterLast('/')
    }

    fun noteBaseName(relativePath: String): String {
        val name = fileName(relativePath)
        return when {
            name.endsWith(".md", ignoreCase = true) -> name.dropLast(3)
            name.endsWith(".txt", ignoreCase = true) -> name.dropLast(4)
            else -> name
        }
    }

    fun assetDirectoryForNote(noteRelativePath: String): String {
        val parent = parentPath(noteRelativePath)
        val base = noteBaseName(noteRelativePath)
        return normalize(if (parent.isEmpty()) base else "$parent/$base")
    }

    fun resolveRelativeToNote(noteRelativePath: String, linkTarget: String): String {
        val target = linkTarget.trim()
        require(!hasParentSegment(target)) { "Traversal in link: $linkTarget" }
        if (target.startsWith("/")) error("Absolute link rejected: $linkTarget")
        return if ("/" in target || !target.contains(":")) {
            val parent = parentPath(noteRelativePath)
            normalize(if (parent.isEmpty()) target else "$parent/$target")
        } else {
            error("Non-relative link rejected: $linkTarget")
        }
    }

    fun isVaultRelativeAttachment(path: String): Boolean = path.startsWith(VAULT_REL_PREFIX)

    fun toVaultRelative(storedPath: String): String =
        storedPath.removePrefix(VAULT_REL_PREFIX).let { normalize(it) }

    fun vaultRelative(storedPath: String): String = VAULT_REL_PREFIX + normalize(storedPath)

    fun sanitizeFileName(original: String, fallback: String = "file"): String {
        val nfd = Normalizer.normalize(original, Normalizer.Form.NFC)
        var name = illegalChars.replace(nfd, "_").trim().trimEnd('.')
        if (name.isBlank()) name = fallback
        if (name.length > MAX_SEGMENT_LENGTH) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0) {
                name.take(MAX_SEGMENT_LENGTH - (name.length - dot)) + name.substring(dot)
            } else {
                name.take(MAX_SEGMENT_LENGTH)
            }
        }
        return name
    }

    fun uniqueName(base: String, existing: Set<String>): String {
        val sanitized = sanitizeFileName(base)
        if (sanitized !in existing) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val stem = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var i = 2
        while ("$stem-$i$ext" in existing) i++
        return "$stem-$i$ext"
    }

    fun titleToNotePath(title: String, markdown: Boolean, parentDir: String = ""): String {
        val ext = if (markdown) ".md" else ".txt"
        val file = sanitizeFileName(title.ifBlank { "Untitled" } + ext)
        return normalize(if (parentDir.isEmpty()) file else "$parentDir/$file")
    }

    fun conflictPath(originalPath: String, epochSeconds: Long): String {
        val parent = parentPath(originalPath)
        val base = noteBaseName(originalPath)
        val ext = if (originalPath.endsWith(".txt", ignoreCase = true)) ".txt" else ".md"
        val conflictName = sanitizeFileName("$base (conflict $epochSeconds)$ext")
        return normalize(if (parent.isEmpty()) conflictName else "$parent/$conflictName")
    }

    private fun decodeTraversal(segment: String): String {
        require(!segment.equals("..", ignoreCase = true)) { "Path traversal: $segment" }
        return segment
    }

    const val VAULT_REL_PREFIX = "vaultrel:"
    const val STAGING_DIR = ".quillpad-staging"
    const val PENDING_DIR = ".quillpad-staging/pending"
    const val RECOVERY_DIR = ".quillpad-staging/recovery"
    const val BACKUP_DIR = ".quillpad-staging/recovery/backups"
    const val JOURNAL_RELATIVE = ".quillpad-staging/recovery/journal.json"
}
