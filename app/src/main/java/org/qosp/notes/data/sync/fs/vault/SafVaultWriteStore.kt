package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Vault note files live on SAF; recovery journal/staging/backup live in app-private storage per vault scope.
 */
class SafVaultWriteStore(
    private val context: Context,
    private val root: DocumentFile,
    private val vaultFs: SafVaultFs? = null,
    private val scopeId: String = vaultScopeFromRoot(root),
) : VaultWriteStore {

    private val recoveryRoot: File
        get() = File(context.filesDir, "vault-recovery/$scopeId").apply { mkdirs() }

    private val pendingDir: File
        get() = File(recoveryRoot, "pending").apply { mkdirs() }

    private val backupDir: File
        get() = File(recoveryRoot, "backup").apply { mkdirs() }

    override fun vaultScopeId(): String = scopeId

    override fun readJournal(): VaultRecoveryJournal? {
        val file = File(recoveryRoot, "journal.json")
        if (!file.exists()) return null
        return VaultRecoveryJournalCodec.decode(file.readText())
    }

    override fun writeJournal(journal: VaultRecoveryJournal?) {
        val file = File(recoveryRoot, "journal.json")
        if (journal == null) {
            file.delete()
            return
        }
        file.writeText(VaultRecoveryJournalCodec.encode(journal))
    }

    override fun readVerified(relativePath: String): String? {
        val file = findByRelativePath(relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        val token = "st_${VaultContentHash.ofText(relativePath + content.length).take(16)}"
        val target = File(pendingDir, token)
        target.writeText(content)
        if (content.isEmpty()) throw IOException("empty write")
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
        val file = File(pendingDir, ticket.storageName)
        if (!file.exists()) return null
        return file.readText()
    }

    override fun publishFromStaging(relativePath: String, staging: VaultWriteStore.RecoveryTicket) {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val staged = readStaging(staging) ?: throw IOException("staging unreadable")
        val existing = findByRelativePath(path)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { out ->
                out.write(staged.encodeToByteArray())
            } ?: throw IOException("cannot publish truncate $path")
        } else {
            val parentPath = VaultPaths.parentPath(path)
            val parent = if (parentPath.isEmpty()) root else VaultDocumentTree.ensureDirectory(root, parentPath)
            val fileName = VaultPaths.fileName(path)
            val mime = if (path.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
            val target = vaultFs?.findFileCaseInsensitive(parent, fileName)
                ?: parent.findFile(fileName)
                ?: parent.createFile(mime, fileName.substringBeforeLast('.'))
                ?: throw IOException("cannot create $fileName")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(staged.encodeToByteArray())
            } ?: throw IOException("cannot write $fileName")
            if (target.name != fileName) target.renameTo(fileName)
        }
        File(pendingDir, staging.storageName).delete()
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val token = "bk_${VaultContentHash.ofText(relativePath + content).take(16)}"
        File(backupDir, token).writeText(content)
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? {
        val file = File(backupDir, ticket.storageName)
        if (!file.exists()) return null
        return file.readText()
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
        File(pendingDir, ticket.storageName).delete()
        File(backupDir, ticket.storageName).delete()
    }

    fun recoveryItemCount(): Int = recoveryRoot.walkTopDown().count { it.isFile }

    /** Deletes all recovery files for this vault scope and returns how many files were removed. */
    fun clearAllRecoveryState(): Int {
        val count = recoveryItemCount()
        recoveryRoot.deleteRecursively()
        return count
    }

    private fun findByRelativePath(relativePath: String): DocumentFile? {
        vaultFs?.let {
            val norm = VaultPaths.normalizeNotePath(relativePath)
            var current: DocumentFile = root
            val parts = norm.split('/')
            parts.forEachIndexed { index, segment ->
                val child = it.findFileCaseInsensitive(current, segment) ?: return null
                current = child
                if (index < parts.lastIndex && !current.isDirectory) return null
            }
            return current.takeIf { it.isFile }
        }
        return VaultDocumentTree.findByRelativePathCaseInsensitive(root, relativePath)
    }

    companion object {
        fun vaultScopeFromRoot(root: DocumentFile): String =
            VaultContentHash.ofText(root.uri.toString())
    }
}
