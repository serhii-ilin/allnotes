package org.qosp.notes.data.sync.fs.vault

import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** SAF helpers for nested vault paths. */
object VaultDocumentTree {
    data class IndexedFile(val relativePath: String, val file: DocumentFile)
    data class IndexedVaultFile(
        val relativePath: String,
        val lastModifiedMs: Long = 0L,
        val size: Long = -1L,
    )

    fun indexNotes(fs: VaultFs): List<IndexedVaultFile> {
        val candidates = listNoteCandidates(fs)
        val rawTexts = readRawTexts(fs, candidates.map { it.relativePath })
        return filterIndexed(candidates, rawTexts)
    }

    /** Walks the tree once via [VaultFs.listChildren] and returns note-file candidates with listing metadata. */
    fun listNoteCandidates(fs: VaultFs): List<IndexedVaultFile> {
        val candidates = mutableListOf<IndexedVaultFile>()
        fun walk(dir: String) {
            val children = fs.listChildren(dir)
            children.forEach { child ->
                when {
                    child.isDirectory -> {
                        if (!VaultHousekeeping.shouldSkipDirectory(child.name)) walk(child.relativePath)
                    }
                    VaultHousekeeping.shouldIndexFile(child.name) -> {
                        candidates.add(
                            IndexedVaultFile(
                                relativePath = VaultPaths.normalizeNotePath(child.relativePath),
                                lastModifiedMs = child.lastModifiedMs,
                                size = child.size,
                            ),
                        )
                    }
                }
            }
        }
        walk("")
        return candidates
    }

    /** Reads each note path once into a raw-text map (unreadable notes are omitted). */
    fun readRawTexts(fs: VaultFs, notePaths: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (path in notePaths) {
            fs.readText(path)?.let { out[path] = it }
        }
        return out
    }

    /** Drops asset-folder and referenced-attachment markdown from [candidates], using already-read [rawTexts]. */
    fun filterIndexed(
        candidates: List<IndexedVaultFile>,
        rawTexts: Map<String, String>,
    ): List<IndexedVaultFile> {
        val notePaths = candidates.map { it.relativePath }.toSet()
        val referencedAttachments = referencedVaultPathsFromNotes(rawTexts, notePaths)
        return candidates.mapNotNull { cand ->
            val norm = cand.relativePath
            if (isUnderNoteAssetFolder(norm, notePaths)) return@mapNotNull null
            if (isReferencedAttachmentMarkdown(norm, notePaths, referencedAttachments)) return@mapNotNull null
            cand
        }
    }

    /**
     * `.md`/`.txt` beside a note's asset folder are omitted when referenced as vault attachments
     * by that note. Unreferenced files (e.g. `Meeting/agenda.md`) still import.
     */
    fun isReferencedAttachmentMarkdown(
        relativePath: String,
        indexedNotePaths: Set<String>,
        referencedVaultRelPaths: Set<String>,
    ): Boolean {
        if (!relativePath.endsWith(".md", ignoreCase = true) &&
            !relativePath.endsWith(".txt", ignoreCase = true)
        ) {
            return false
        }
        if (!isBesideIndexedNoteAssetFolder(relativePath, indexedNotePaths)) return false
        val norm = VaultPaths.normalize(relativePath)
        return norm in referencedVaultRelPaths ||
            VaultPaths.vaultRelative(norm) in referencedVaultRelPaths
    }

    private fun isBesideIndexedNoteAssetFolder(relativePath: String, indexedNotePaths: Set<String>): Boolean {
        val parent = VaultPaths.parentPath(relativePath)
        if (parent.isEmpty()) return false
        val dirName = VaultPaths.fileName(parent)
        val grandparent = VaultPaths.parentPath(parent)
        val siblingNotePath = VaultPaths.normalizeNotePath(
            if (grandparent.isEmpty()) "$dirName.md" else "$grandparent/$dirName.md",
        )
        return siblingNotePath in indexedNotePaths
    }

    private fun referencedVaultPathsFromNotes(rawTexts: Map<String, String>, notePaths: Set<String>): Set<String> {
        val out = mutableSetOf<String>()
        for (path in notePaths) {
            val raw = rawTexts[path] ?: continue
            val body = VaultNoteDocument.parse(raw).body
            VaultAttachments.parseFromBody(body, path).forEach { att ->
                if (VaultPaths.isVaultRelativeAttachment(att.path)) {
                    out.add(VaultPaths.toVaultRelative(att.path))
                }
            }
        }
        return out
    }

    /**
     * Every mapped path that exists must be in [indexed]. [probe] looks a missing one up live and must throw when
     * the lookup itself fails: a failed lookup is not proof that the file is gone.
     */
    fun isIndexCompleteForMappedPaths(
        fs: VaultFs,
        indexed: List<IndexedVaultFile>,
        knownMappedPaths: Set<String>,
        probe: (String) -> Boolean = fs::isFile,
    ): Boolean {
        if (knownMappedPaths.isEmpty()) return true
        val fold = fs.caseInsensitive
        fun key(path: String) = if (fold) path.lowercase() else path
        val indexedKeys = indexed.map { key(it.relativePath) }.toSet()
        for (path in knownMappedPaths) {
            if (key(path) in indexedKeys) continue
            if (probe(path)) return false
        }
        return true
    }

    /**
     * On a case-insensitive vault the provider may list a mapped file under another case (renamed outside the app).
     * Report such a file under the mapped spelling so the mapping still finds it; reads and writes fold case anyway.
     * An exact-case match keeps the mapping, and any other file folding to the same path keeps its own spelling.
     * Returns listed path -> reported path.
     */
    fun reportedPaths(
        fs: VaultFs,
        listed: List<IndexedVaultFile>,
        knownMappedPaths: Set<String>,
    ): Map<String, String> {
        val identity = listed.associate { it.relativePath to it.relativePath }
        if (!fs.caseInsensitive || knownMappedPaths.isEmpty()) return identity
        val listedPaths = listed.map { it.relativePath }.toSet()
        val mappedByFold = knownMappedPaths.groupBy { it.lowercase() }
        val out = identity.toMutableMap()
        for ((fold, group) in listed.groupBy { it.relativePath.lowercase() }) {
            val mapped = mappedByFold[fold]?.singleOrNull() ?: continue
            if (mapped in listedPaths) continue
            // Several listed spellings: pick deterministically, the rest stay under their own name.
            val pick = group.minOf { it.relativePath }
            out[pick] = mapped
        }
        return out
    }

    fun indexNotes(root: DocumentFile): List<IndexedFile> {
        val candidates = mutableListOf<Pair<String, DocumentFile>>()
        fun walk(dir: DocumentFile, prefix: String) {
            val children = dir.listFiles() ?: throw IOException("Cannot list vault directory: $prefix")
            children.forEach { child ->
                val name = child.name ?: return@forEach
                when {
                    child.isDirectory -> {
                        if (!VaultHousekeeping.shouldSkipDirectory(name)) {
                            val next = if (prefix.isEmpty()) name else "$prefix/$name"
                            walk(child, next)
                        }
                    }
                    VaultHousekeeping.shouldIndexFile(name) -> {
                        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                        candidates.add(rel to child)
                    }
                }
            }
        }
        walk(root, "")
        val notePaths = candidates.map { VaultPaths.normalizeNotePath(it.first) }.toSet()
        return candidates.mapNotNull { (rel, file) ->
            val norm = VaultPaths.normalizeNotePath(rel)
            if (isUnderNoteAssetFolder(norm, notePaths)) return@mapNotNull null
            IndexedFile(norm, file)
        }
    }

    /**
     * A mapped path that [findByRelativePath] can open must appear in [indexed]; otherwise listing was incomplete.
     * (Residual: partial directory listings that omit notes without failing [findByRelativePath] may still require
     * future directory-entry-count heuristics — only the single-path probe is implemented here.)
     */
    fun isIndexCompleteForMappedPaths(
        root: DocumentFile,
        indexed: List<IndexedFile>,
        knownMappedPaths: Set<String>,
    ): Boolean {
        if (knownMappedPaths.isEmpty()) return true
        val indexedPaths = indexed.map { it.relativePath }.toSet()
        for (path in knownMappedPaths) {
            if (path in indexedPaths) continue
            val direct = findByRelativePath(root, path)
            if (direct != null && direct.isFile) return false
        }
        return true
    }

    /**
     * True when [relativePath] is a non-note asset (or attachment markdown) under
     * `{Name}/` that sits beside an indexed `{Name}.md` at the parent directory level.
     */
    fun isUnderNoteAssetFolder(relativePath: String, indexedNotePaths: Set<String>): Boolean {
        if (relativePath.endsWith(".md", ignoreCase = true) ||
            relativePath.endsWith(".txt", ignoreCase = true)
        ) {
            return false
        }
        val parent = VaultPaths.parentPath(relativePath)
        if (parent.isEmpty()) return false

        val dirName = VaultPaths.fileName(parent)
        val grandparent = VaultPaths.parentPath(parent)
        val siblingNotePath = VaultPaths.normalizeNotePath(
            if (grandparent.isEmpty()) "$dirName.md" else "$grandparent/$dirName.md",
        )
        if (siblingNotePath !in indexedNotePaths) return false
        return true
    }

    fun findDirectory(root: DocumentFile, relativeDir: String): DocumentFile? {
        val norm = VaultPaths.normalize(relativeDir)
        if (norm.isEmpty()) return root
        var current = root
        norm.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val child = current.findFile(segment) ?: return null
            if (!child.isDirectory) return null
            current = child
        }
        return current
    }

    fun findByRelativePath(root: DocumentFile, relativePath: String): DocumentFile? =
        findByRelativePathCaseInsensitive(root, relativePath)

    fun findByRelativePathCaseInsensitive(root: DocumentFile, relativePath: String): DocumentFile? {
        val norm = VaultPaths.normalizeNotePath(relativePath)
        var current: DocumentFile = root
        val parts = norm.split('/')
        parts.forEachIndexed { index, segment ->
            val child = findFileCaseInsensitive(current, segment) ?: return null
            current = child
            if (index < parts.lastIndex && !current.isDirectory) return null
        }
        return current.takeIf { it.isFile }
    }

    fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { return it }
        return parent.listFiles()?.firstOrNull { child ->
            child.name?.equals(name, ignoreCase = true) == true
        }
    }

    fun ensureDirectory(root: DocumentFile, relativeDir: String): DocumentFile {
        val norm = VaultPaths.normalize(relativeDir)
        if (norm.isEmpty()) return root
        var current = root
        norm.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            // Case-insensitive: on shared storage `foo` and `Foo` are one folder, not a new sibling.
            val next = findFileCaseInsensitive(current, segment)
            current = when {
                next == null -> current.createDirectory(segment)
                    ?: throw IOException("Cannot create directory $segment")
                next.isDirectory -> next
                else -> throw IOException("$segment exists and is not a directory")
            }
        }
        return current
    }

    fun createNoteFile(root: DocumentFile, relativePath: String, mimeType: String): DocumentFile {
        val norm = VaultPaths.normalizeNotePath(relativePath)
        val parent = VaultPaths.parentPath(norm)
        val fileName = VaultPaths.fileName(norm)
        val dir = if (parent.isEmpty()) root else ensureDirectory(root, parent)
        return dir.createFile(mimeType, fileName.removeSuffix(".md").removeSuffix(".txt"))
            ?.let { created ->
                // Document providers may append extension; rename if needed
                if (created.name == fileName) created
                else {
                    created.renameTo(fileName)
                    dir.findFile(fileName) ?: created
                }
            } ?: throw IOException("Cannot create $fileName")
    }
}
