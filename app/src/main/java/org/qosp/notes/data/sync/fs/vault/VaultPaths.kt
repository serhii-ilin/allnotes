package org.qosp.notes.data.sync.fs.vault

import java.text.Normalizer

object VaultPaths {
    private val illegalChars = Regex("""[\\/:*?"<>|\x00-\x1f]""")
    /** Max UTF-8 bytes per path segment (room for `-2.md`, conflict suffixes on SAF). */
    private const val MAX_SEGMENT_BYTES = 200

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

    /** True when [storageUri] is a vault-relative path, not a SAF `content://` URI. */
    fun isVaultStoragePath(storageUri: String?): Boolean =
        !storageUri.isNullOrBlank() && !storageUri.contains("://")

    fun vaultMappingPath(storageUri: String?): String? =
        storageUri?.takeIf { isVaultStoragePath(it) }?.let { normalizeNotePath(it) }

    fun uniqueSiblingPath(path: String, exists: (String) -> Boolean): String {
        if (!exists(path)) return normalizeNotePath(path)
        val parent = parentPath(path)
        val base = noteBaseName(path)
        val ext = when {
            path.endsWith(".txt", ignoreCase = true) -> ".txt"
            path.contains('.') -> ".${fileName(path).substringAfterLast('.')}"
            else -> ".md"
        }
        var i = 2
        while (true) {
            val candidate = normalize(
                if (parent.isEmpty()) "$base-$i$ext" else "$parent/$base-$i$ext",
            )
            if (!exists(candidate)) return candidate
            i++
        }
    }

    fun toVaultRelative(storedPath: String): String =
        storedPath.removePrefix(VAULT_REL_PREFIX).let { normalize(it) }

    fun vaultRelative(storedPath: String): String = VAULT_REL_PREFIX + normalize(storedPath)

    fun sanitizeFileName(original: String, fallback: String = "file"): String {
        val nfd = Normalizer.normalize(original, Normalizer.Form.NFC)
        var name = illegalChars.replace(nfd, "_").trim().trimEnd('.')
        if (name.isBlank()) name = fallback
        name = truncateUtf8BytesPreservingExtension(name, MAX_SEGMENT_BYTES)
        return name
    }

    fun truncateUtf8BytesPreservingExtension(name: String, maxBytes: Int): String {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        if (utf8.size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot)
            val extBytes = ext.toByteArray(Charsets.UTF_8).size
            val stemBudget = (maxBytes - extBytes).coerceAtLeast(1)
            return truncateUtf8Bytes(name.substring(0, dot), stemBudget) + ext
        }
        return truncateUtf8Bytes(name, maxBytes)
    }

    private fun truncateUtf8Bytes(s: String, maxBytes: Int): String {
        var used = 0
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
            if (used + chunkBytes > maxBytes) break
            used += chunkBytes
            out.append(chunk)
            i += Character.charCount(cp)
        }
        return out.toString().ifBlank { "file" }
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

    /**
     * Folder a note belongs in for its notebook: the root without one, else the sanitized notebook name. A
     * [currentParent] whose last segment already names the notebook is kept, so nested folders are not flattened.
     */
    fun parentForNotebook(notebookName: String?, currentParent: String): String {
        val name = notebookName?.trim().orEmpty()
        if (name.isEmpty()) return ""
        val sanitized = sanitizeFileName(name)
        val lastSegment = currentParent.substringAfterLast('/')
        if (lastSegment.equals(name, ignoreCase = true) || lastSegment.equals(sanitized, ignoreCase = true)) {
            return currentParent
        }
        return sanitized
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
    /** The vault's bin. A dot-folder, so scans never import from it. */
    const val TRASH_DIR = ".trash"
    const val STAGING_DIR = ".quillpad-staging"
    const val PENDING_DIR = ".quillpad-staging/pending"
    const val RECOVERY_DIR = ".quillpad-staging/recovery"
    const val BACKUP_DIR = ".quillpad-staging/recovery/backups"
    const val JOURNAL_RELATIVE = ".quillpad-staging/recovery/journal.json"
}
