package org.qosp.notes.data.sync.fs.vault

import java.io.IOException
import java.io.InputStream

/**
 * Provider-agnostic vault tree access. Production uses [SafVaultFs]; tests use [InMemoryVaultFs].
 */
interface VaultFs {
    val scopeId: String

    fun canRead(): Boolean = true
    fun canWrite(): Boolean = true

    fun listChildren(relativeDir: String): List<VaultFsEntry>

    fun isFile(relativePath: String): Boolean
    fun isDirectory(relativePath: String): Boolean

    fun readText(relativePath: String): String?
    fun openRead(relativePath: String): InputStream?

    fun writeText(relativePath: String, content: String, mimeType: String = mimeForPath(relativePath))

    fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String = "application/octet-stream")

    fun ensureDirectory(relativeDir: String)

    fun delete(relativePath: String): Boolean

    fun lastModifiedEpochSec(relativePath: String): Long

    /** Copy or move source into destination path (creates parent dirs). */
    fun copyFile(fromRelative: String, toRelative: String, deleteSource: Boolean = true)

    fun renameFile(fromRelative: String, toRelative: String)

    data class VaultFsEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
    )

    companion object {
        fun mimeForPath(path: String): String =
            if (path.endsWith(".txt", ignoreCase = true)) "text/plain"
            else if (path.endsWith(".md", ignoreCase = true)) "text/markdown"
            else "application/octet-stream"
    }
}
