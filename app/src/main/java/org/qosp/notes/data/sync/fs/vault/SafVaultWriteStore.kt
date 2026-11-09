package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * SAF-backed [VaultWriteStore]. No atomic rename guarantee across providers;
 * [VaultRecoverableWrite] keeps backups until publish verifies.
 */
class SafVaultWriteStore(
    private val context: Context,
    private val root: DocumentFile,
) : VaultWriteStore {

    override fun readVerified(relativePath: String): String? {
        val file = VaultDocumentTree.findByRelativePath(root, relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        val stagingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.STAGING_DIR)
        val token = "w_${VaultContentHash.ofText(relativePath + content.length).take(16)}"
        val mime = if (relativePath.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
        stagingDir.findFile(token)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
        val file = stagingDir.createFile(mime, token) ?: return null
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            val bytes = content.encodeToByteArray()
            out.write(bytes)
            if (bytes.isEmpty()) throw IOException("empty write")
        } ?: return null
        return VaultWriteStore.RecoveryTicket(relativePath, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
        val stagingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.STAGING_DIR)
        val file = stagingDir.findFile(ticket.token) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }

    override fun publishFromStaging(relativePath: String, staging: VaultWriteStore.RecoveryTicket) {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val stagingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.STAGING_DIR)
        val staged = stagingDir.findFile(staging.token) ?: throw IOException("staging missing")
        val body = readStaging(staging) ?: throw IOException("staging unreadable")
        val existing = VaultDocumentTree.findByRelativePath(root, path)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { out ->
                out.write(body.encodeToByteArray())
            } ?: throw IOException("cannot publish truncate $path")
        } else {
            val parentPath = VaultPaths.parentPath(path)
            val parent = if (parentPath.isEmpty()) root else VaultDocumentTree.ensureDirectory(root, parentPath)
            val fileName = VaultPaths.fileName(path)
            val mime = if (path.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
            val target = parent.findFile(fileName)
                ?: parent.createFile(mime, fileName.substringBeforeLast('.'))
                ?: throw IOException("cannot create $fileName")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(body.encodeToByteArray())
            } ?: throw IOException("cannot write $fileName")
            if (target.name != fileName) target.renameTo(fileName)
        }
        DocumentsContract.deleteDocument(context.contentResolver, staged.uri)
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        return writeToStaging("${VaultPaths.RECOVERY_DIR}/$relativePath", content)
            ?: throw IOException("backup staging failed")
    }

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? = readStaging(ticket)

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = readRecovery(backup) ?: return
        writeToStaging(relativePath, body)?.let { publishFromStaging(relativePath, it) }
    }

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        val stagingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.RECOVERY_DIR)
        stagingDir.findFile(ticket.token)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
        val mainStaging = VaultDocumentTree.ensureDirectory(root, VaultPaths.STAGING_DIR)
        mainStaging.findFile(ticket.token)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
    }
}
