package org.qosp.notes.data.sync.fs.vault

import androidx.documentfile.provider.DocumentFile

object VaultHousekeeping {
    private val syncConflictPattern = Regex("""\.sync-conflict-.*""", RegexOption.IGNORE_CASE)

    fun shouldSkipDirectory(name: String?): Boolean {
        if (name == null) return true
        if (name.startsWith(".quillpad")) return true
        return name.startsWith('.')
    }

    fun shouldIndexFile(name: String?): Boolean {
        if (name == null) return false
        if (name.startsWith(".quillpad")) return false
        if (syncConflictPattern.containsMatchIn(name)) return false
        return name.endsWith(".md", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)
    }

    fun listNoteFilesRecursive(root: DocumentFile): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        fun walk(dir: DocumentFile, relativePrefix: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                when {
                    child.isDirectory -> {
                        if (!shouldSkipDirectory(name)) {
                            val next = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                            walk(child, next)
                        }
                    }
                    shouldIndexFile(name) -> out.add(child)
                }
            }
        }
        walk(root, "")
        return out
    }

    fun relativePathFromRoot(root: DocumentFile, file: DocumentFile): String? {
        // Build path by walking from root — SAF has no stable path; match by traversal during scan.
        return null
    }
}
