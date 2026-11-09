package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * SAF-backed [VaultWriteStore]. No atomic rename guarantee across providers;
 * durable journal + isolated backup namespace per vault tree URI.
 */
class SafVaultWriteStore(
    private val context: Context,
    private val root: DocumentFile,
    private val scopeId: String = vaultScopeFromRoot(root),
) : VaultWriteStore {

    override fun vaultScopeId(): String = scopeId

    override fun readJournal(): VaultRecoveryJournal? {
        val file = VaultDocumentTree.findByRelativePath(root, VaultPaths.JOURNAL_RELATIVE) ?: return null
        val raw = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
        return VaultRecoveryJournalCodec.decode(raw)
    }

    override fun writeJournal(journal: VaultRecoveryJournal?) {
        val recoveryDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.RECOVERY_DIR)
        val existing = recoveryDir.findFile("journal.json")
        if (journal == null) {
            existing?.let { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
            return
        }
        val encoded = VaultRecoveryJournalCodec.encode(journal)
        val parent = VaultDocumentTree.ensureDirectory(root, VaultPaths.RECOVERY_DIR)
        val target = parent.findFile("journal.json")
            ?: parent.createFile("application/json", "journal")?.also {
                if (it.name != "journal.json") it.renameTo("journal.json")
            }
            ?: throw IOException("cannot create recovery journal")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(encoded.encodeToByteArray())
        } ?: throw IOException("cannot write recovery journal")
    }

    override fun readVerified(relativePath: String): String? {
        val file = VaultDocumentTree.findByRelativePath(root, relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        val pendingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.PENDING_DIR)
        val token = "st_${VaultContentHash.ofText(relativePath + content.length).take(16)}"
        pendingDir.findFile(token)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
        val mime = if (relativePath.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
        val created = pendingDir.createFile(mime, token) ?: return null
        val storageName = ensureStorageName(pendingDir, created, token)
        val file = pendingDir.findFile(storageName) ?: created
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            val bytes = content.encodeToByteArray()
            out.write(bytes)
            if (bytes.isEmpty()) throw IOException("empty write")
        } ?: return null
        return VaultWriteStore.RecoveryTicket(relativePath, token, storageName)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
        val pendingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.PENDING_DIR)
        val file = pendingDir.findFile(ticket.storageName) ?: pendingDir.findFile(ticket.token) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }

    override fun publishFromStaging(relativePath: String, staging: VaultWriteStore.RecoveryTicket) {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val staged = readStaging(staging) ?: throw IOException("staging unreadable")
        val existing = VaultDocumentTree.findByRelativePath(root, path)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { out ->
                out.write(staged.encodeToByteArray())
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
                out.write(staged.encodeToByteArray())
            } ?: throw IOException("cannot write $fileName")
            if (target.name != fileName) target.renameTo(fileName)
        }
        val pendingDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.PENDING_DIR)
        pendingDir.findFile(staging.storageName)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val backupDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.BACKUP_DIR)
        val token = "bk_${VaultContentHash.ofText(relativePath + content).take(16)}"
        backupDir.findFile(token)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
        val created = backupDir.createFile("text/plain", token)
            ?: throw IOException("backup create failed")
        val storageName = ensureStorageName(backupDir, created, token)
        val file = backupDir.findFile(storageName) ?: created
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(content.encodeToByteArray())
        } ?: throw IOException("backup write failed")
        return VaultWriteStore.RecoveryTicket(relativePath, token, storageName)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? {
        val backupDir = VaultDocumentTree.ensureDirectory(root, VaultPaths.BACKUP_DIR)
        val file = backupDir.findFile(ticket.storageName) ?: backupDir.findFile(ticket.token) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? =
        readStaging(ticket) ?: readBackup(ticket)

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = readBackup(backup) ?: return
        val staged = writeToStaging(relativePath, body)
            ?: throw IOException("restore staging failed")
        publishFromStaging(relativePath, staged)
    }

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        VaultDocumentTree.ensureDirectory(root, VaultPaths.PENDING_DIR).findFile(ticket.storageName)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
        VaultDocumentTree.ensureDirectory(root, VaultPaths.BACKUP_DIR).findFile(ticket.storageName)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it.uri)
        }
    }

    internal fun ensureStorageName(dir: DocumentFile, created: DocumentFile, desired: String): String {
        val current = VaultStagingNames.requireCreatedName(created.name)
        val renamed = if (current == desired) true else created.renameTo(desired)
        return VaultStagingNames.resolveAfterCreate(
            current,
            desired,
            renameSucceeded = renamed,
            findAfterRename = dir.findFile(desired)?.name,
        )
    }

    companion object {
        fun vaultScopeFromRoot(root: DocumentFile): String =
            VaultContentHash.ofText(root.uri.toString())
    }
}
