package org.qosp.notes.data.sync.fs.vault

import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** SAF helpers for nested vault paths. */
object VaultDocumentTree {
    data class IndexedFile(val relativePath: String, val file: DocumentFile)

    fun indexNotes(root: DocumentFile): List<IndexedFile> {
        val result = mutableListOf<IndexedFile>()
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
                        result.add(IndexedFile(VaultPaths.normalizeNotePath(rel), child))
                    }
                }
            }
        }
        walk(root, "")
        return result
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

    fun findByRelativePath(root: DocumentFile, relativePath: String): DocumentFile? {
        val norm = VaultPaths.normalizeNotePath(relativePath)
        var current: DocumentFile = root
        val parts = norm.split('/')
        parts.forEachIndexed { index, segment ->
            val child = current.findFile(segment) ?: return null
            current = child
            if (index < parts.lastIndex && !current.isDirectory) return null
        }
        return current.takeIf { it.isFile }
    }

    fun ensureDirectory(root: DocumentFile, relativeDir: String): DocumentFile {
        val norm = VaultPaths.normalize(relativeDir)
        if (norm.isEmpty()) return root
        var current = root
        norm.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val next = current.findFile(segment)
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
