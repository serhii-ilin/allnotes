package org.qosp.notes.data.sync.fs.vault

import java.text.Normalizer
import java.util.Locale

object VaultPaths {
    private val illegalChars = Regex("""[\\/:*?"<>|\x00-\x1f]""")
    /** Max UTF-8 bytes per path segment (room for `-2.md`, conflict suffixes on SAF). */
    private const val MAX_SEGMENT_BYTES = 200

    /** Why a [validateSegmentName] segment cannot be used as a note file or notebook folder name. */
    enum class NameProblem { Blank, IllegalChar, LeadingDot, TrailingDotOrSpace, ReservedName, TooLong }

    private val reservedNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach {
            add("COM$it")
            add("LPT$it")
        }
    }

    /**
     * Checks the raw [name] as one path segment; null means valid. The name is never trimmed or altered, so a caller
     * can reject exactly what the user typed. Problem order: blank, illegal character, leading dot, trailing dot or
     * space, Windows reserved name, length.
     */
    fun validateSegmentName(name: String): NameProblem? = when {
        name.isBlank() -> NameProblem.Blank
        illegalChars.containsMatchIn(name) -> NameProblem.IllegalChar
        name.startsWith('.') -> NameProblem.LeadingDot
        name.endsWith('.') || name.endsWith(' ') -> NameProblem.TrailingDotOrSpace
        isReservedStem(name) -> NameProblem.ReservedName
        name.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES -> NameProblem.TooLong
        else -> null
    }

    /** How two names are compared for "the same folder": NFC, then lowercase in the root locale. */
    fun collisionKey(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun isReservedStem(name: String): Boolean {
        val stem = name.substringBefore('.')
        return stem.uppercase(Locale.ROOT) in reservedNames
    }

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

    /** Android's own trash rename: `.trashed-<epoch>-<original name>`. */
    private val androidTrashPrefix = Regex("""^\.trashed-\d+-""", RegexOption.IGNORE_CASE)

    fun isAndroidTrashFileName(name: String): Boolean = androidTrashPrefix.containsMatchIn(name)

    /** Title to show for a file. A `.trashed-<epoch>-` prefix is the bin's filename, not the note's name. */
    fun noteTitleFromPath(relativePath: String): String =
        androidTrashPrefix.replace(noteBaseName(relativePath), "")

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

    /** Turns a free-form title or attachment name into a segment that always satisfies [validateSegmentName]. */
    fun sanitizeFileName(original: String, fallback: String = "file"): String {
        val nfc = Normalizer.normalize(original, Normalizer.Form.NFC)
        var name = illegalChars.replace(nfc, "_").trim()
        if (name.isBlank()) name = fallback.ifBlank { "file" }
        if (name.startsWith('.')) name = "_" + name.substring(1)
        name = name.trimEnd(' ', '.').ifBlank { "file" }
        name = suffixReservedStem(name)
        name = truncateUtf8BytesPreservingExtension(name, MAX_SEGMENT_BYTES)
            .trimEnd(' ', '.')
            .ifBlank { "file" }
        return breakReservedStem(name)
    }

    private fun suffixReservedStem(name: String): String {
        if (!isReservedStem(name)) return name
        val stem = name.substringBefore('.')
        return stem + "_" + name.substring(stem.length)
    }

    /** Truncation can drop the `_` suffix; replace the stem's last character instead, without growing the name. */
    private fun breakReservedStem(name: String): String {
        if (!isReservedStem(name)) return name
        val stem = name.substringBefore('.')
        if (stem.length < 2) return "_" + name.substring(stem.length)
        return stem.dropLast(1) + "_" + name.substring(stem.length)
    }

    fun truncateUtf8BytesPreservingExtension(name: String, maxBytes: Int): String {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        if (utf8.size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot)
            val extBytes = ext.toByteArray(Charsets.UTF_8).size
            if (extBytes < maxBytes) {
                val stem = truncateUtf8Bytes(name.substring(0, dot), maxBytes - extBytes)
                val candidate = stem + ext
                if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate
            }
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
     * Folder a note belongs in for its notebook: the root without one, else the notebook name itself, which is
     * always a valid segment. A [currentParent] whose last segment already names the notebook is kept, so nested
     * folders are not flattened. A name that is not a valid segment never becomes a folder: the note stays at the
     * root and no folder is invented for it.
     */
    fun parentForNotebook(notebookName: String?, currentParent: String): String {
        val name = notebookName.orEmpty()
        if (name.isBlank() || validateSegmentName(name) != null) return ""
        val lastSegment = currentParent.substringAfterLast('/')
        if (lastSegment.equals(name, ignoreCase = true)) return currentParent
        return name
    }

    /**
     * [sanitizeFileName] of [base], made unique by [collisionKey] against [existingKeys] with `-2`, `-3`… suffixes.
     * Used when a name from outside (backup, upgrade) has to become a folder without replacing another one.
     */
    fun uniqueSegmentName(base: String, existingKeys: Set<String>): String {
        val sanitized = sanitizeFileName(base)
        if (collisionKey(sanitized) !in existingKeys) return sanitized
        var i = 2
        while (true) {
            val suffix = "-$i"
            val room = MAX_SEGMENT_BYTES - suffix.toByteArray(Charsets.UTF_8).size
            val stem = truncateUtf8BytesPreservingExtension(sanitized, room).trimEnd(' ', '.')
            val candidate = sanitizeFileName(stem + suffix)
            if (collisionKey(candidate) !in existingKeys) return candidate
            i++
        }
    }

    fun conflictPath(originalPath: String, epochSeconds: Long): String {
        val parent = parentPath(originalPath)
        val base = noteBaseName(originalPath)
        val ext = if (originalPath.endsWith(".txt", ignoreCase = true)) ".txt" else ".md"
        val conflictName = sanitizeFileName("$base (conflict $epochSeconds)$ext")
        return normalize(if (parent.isEmpty()) conflictName else "$parent/$conflictName")
    }

    private val conflictNameRegex = Regex(""" \(conflict \d+\)\.(md|txt)$""", RegexOption.IGNORE_CASE)

    /** True when the file name is one [conflictPath] would produce. */
    fun isConflictCopyPath(path: String): Boolean =
        conflictNameRegex.containsMatchIn(path.substringAfterLast('/'))

    private fun decodeTraversal(segment: String): String {
        require(!segment.equals("..", ignoreCase = true)) { "Path traversal: $segment" }
        return segment
    }

    const val VAULT_REL_PREFIX = "vaultrel:"
    /** The vault's bin. A dot-folder, so scans never import from it. */
    const val TRASH_DIR = ".trash"
    const val STAGING_DIR = ".allnotes-staging"
    const val PENDING_DIR = ".allnotes-staging/pending"
    const val RECOVERY_DIR = ".allnotes-staging/recovery"
    const val BACKUP_DIR = ".allnotes-staging/recovery/backups"
    const val JOURNAL_RELATIVE = ".allnotes-staging/recovery/journal.json"
}
