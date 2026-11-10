package org.qosp.notes.data.sync.fs.vault

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Provider-agnostic vault tree access. Production uses [SafVaultFs]; tests use [InMemoryVaultFs].
 */
interface VaultFs {
    val scopeId: String

    /** True when the volume treats `Note.md` and `note.md` as one file, so path comparisons must fold case. */
    val caseInsensitive: Boolean get() = false

    fun canRead(): Boolean = true
    fun canWrite(): Boolean = true

    fun listChildren(relativeDir: String): List<VaultFsEntry>

    fun isFile(relativePath: String): Boolean
    /** Like [isFile], but throws when a folder query fails instead of answering "no such file". */
    fun isFileStrict(relativePath: String): Boolean = isFile(relativePath)

    fun isDirectory(relativePath: String): Boolean

    fun readText(relativePath: String): String?
    fun openRead(relativePath: String): InputStream?

    fun writeText(relativePath: String, content: String, mimeType: String = mimeForPath(relativePath))

    fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String = "application/octet-stream")

    fun openWrite(relativePath: String, mimeType: String = mimeForPath(relativePath)): OutputStream? = null

    fun ensureDirectory(relativeDir: String)

    fun delete(relativePath: String): Boolean

    fun lastModifiedEpochSec(relativePath: String): Long

    /** Copy or move source into destination path (creates parent dirs). */
    fun copyFile(fromRelative: String, toRelative: String, deleteSource: Boolean = true)

    fun renameFile(fromRelative: String, toRelative: String)

    /**
     * Renames a folder within its parent, used for case-only changes (`foo` to `Foo`). Case-insensitive
     * storage treats the target as already existing, so implementations may need a temporary name.
     */
    fun renameDirectory(fromRelative: String, toRelative: String)

    data class VaultFsEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        /** Provider-reported modification time in epoch milliseconds (0 when unknown). */
        val lastModifiedMs: Long = 0L,
        /** Provider-reported size in bytes (-1 when unknown). */
        val size: Long = -1L,
    )

    companion object {
        fun mimeForPath(path: String): String =
            if (path.endsWith(".txt", ignoreCase = true)) "text/plain"
            else if (path.endsWith(".md", ignoreCase = true)) "text/markdown"
            else "application/octet-stream"
    }
}
