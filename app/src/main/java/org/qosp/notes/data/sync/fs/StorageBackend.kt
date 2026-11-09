package org.qosp.notes.data.sync.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.AvailabilityStatus
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.VaultAttachments
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultDocumentTree
import org.qosp.notes.data.sync.fs.vault.VaultMediaWriter
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.data.sync.fs.vault.VaultNoteRelocation
import org.qosp.notes.data.sync.fs.vault.VaultStreamHashes
import org.qosp.notes.data.sync.fs.vault.SafVaultWriteStore
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultRecoveryService
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.fs.vault.VaultRecoverableWrite
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultWriteContext
import org.qosp.notes.data.sync.nextcloud.BackendValidationResult
import org.qosp.notes.preferences.CloudService
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.measureTimedValue

class StorageBackend(
    private val context: Context,
    private val config: StorageConfig,
    private val notebookDao: NotebookDao,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
) : ISyncBackend {

    companion object {
        private const val TAG = "StorageBackend"
    }

    private val quarantinedNotePaths = mutableSetOf<String>()

    override val type: CloudService = CloudService.FILE_STORAGE

    override suspend fun isAvailable(): AvailabilityStatus {
        return try {
            val root = getRootDocumentFile()
            when {
                root == null -> AvailabilityStatus.Unavailable("Storage location not accessible")
                !hasPermissionsAt(config.location) -> AvailabilityStatus.Unavailable("Missing storage permissions - please reconfigure sync")
                !root.canRead() -> AvailabilityStatus.Unavailable("Cannot read from storage location")
                !root.canWrite() -> AvailabilityStatus.Unavailable("Cannot write to storage location")
                else -> AvailabilityStatus.Available
            }
        } catch (e: Exception) {
            AvailabilityStatus.Unavailable("Storage access error: ${e.message ?: "Unknown error"}")
        }
    }

    override suspend fun createNote(note: Note): SyncNote {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")
        val relativePath = uniqueNotePath(root, VaultPaths.titleToNotePath(note.title, note.isMarkdownEnabled))
        return writeNoteAt(root, relativePath, note, mapping = null)
    }

    override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")
        val currentPath = mapping.storageUri?.let { VaultPaths.normalizeNotePath(it) }
            ?: throw IllegalArgumentException("Vault path missing in mapping")
        val desiredPath = VaultPaths.titleToNotePath(
            title = note.title,
            markdown = note.isMarkdownEnabled,
            parentDir = VaultPaths.parentPath(currentPath),
        )
        val targetPath = if (desiredPath != currentPath) {
            uniqueNotePath(root, desiredPath, exclude = currentPath)
        } else {
            currentPath
        }
        val mappingState = mapping.extras?.let { VaultMappingState.resolve(it) }
        val noteForWrite = if (targetPath != currentPath) {
            VaultNoteRelocation.remapNoteAttachments(note, currentPath, targetPath)
        } else {
            note
        }
        if (targetPath != currentPath) {
            VaultNoteRelocation.relocateOwnedAssets(
                context,
                root,
                currentPath,
                targetPath,
                mappingState?.vaultAttachmentPathSet.orEmpty(),
            )
        }
        val syncNote = writeNoteAt(root, targetPath, noteForWrite, mapping)
        if (targetPath != currentPath) {
            VaultDocumentTree.findByRelativePath(root, currentPath)?.let { old ->
                DocumentsContract.deleteDocument(context.contentResolver, old.uri)
            }
        }
        return mapping.copy(storageUri = targetPath, extras = syncNote.extra)
    }

    override suspend fun deleteNote(mapping: IdMapping): Boolean {
        val root = getRootDocumentFile() ?: return false
        val path = mapping.storageUri ?: return false
        val file = VaultDocumentTree.findByRelativePath(root, path) ?: return false
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, file.uri)
        return deleted
    }

    override suspend fun getNote(mapping: IdMapping): SyncNote? {
        val path = mapping.storageUri ?: return null
        val root = getRootDocumentFile() ?: return null
        val file = VaultDocumentTree.findByRelativePath(root, path) ?: return null
        return fileToSyncNote(file, path)
    }

    override suspend fun getAll(knownMappedPaths: Set<String>): List<SyncNote>? {
        val root = getRootDocumentFile() ?: return null
        if (!root.canRead()) return null
        if (!runVaultRecoveryPrelude(root)) {
            Log.w(TAG, "getAll: vault recovery blocked authoritative scan")
            return null
        }
        return try {
            val indexed = VaultDocumentTree.indexNotes(root)
            if (!VaultDocumentTree.isIndexCompleteForMappedPaths(root, indexed, knownMappedPaths)) {
                Log.w(TAG, "getAll: mapped note visible but missing from index; failing scan")
                return null
            }
            indexed.map { (rel, file) ->
                fileToSyncNote(file, rel)
                    ?: throw IOException("Cannot read note file: $rel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAll: scan failed", e)
            null
        }
    }

    suspend fun validateConfig(): BackendValidationResult {
        return try {
            val root = getRootDocumentFile()
            if (root == null || !hasPermissionsAt(config.location)) {
                BackendValidationResult.InvalidConfig
            } else {
                BackendValidationResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateConfig: Error validating config", e)
            BackendValidationResult.InvalidConfig
        }
    }

    suspend fun createConflictCopy(remote: SyncNote): String? {
        val root = getRootDocumentFile() ?: return null
        val original = remote.idStr
        val conflictPath = VaultPaths.conflictPath(original, remote.lastModified)
        val unique = uniqueNotePath(root, conflictPath)
        val content = remote.content ?: return null
        writeRaw(root, unique, content)
        return unique
    }

    private suspend fun writeNoteAt(
        root: DocumentFile,
        relativePath: String,
        note: Note,
        mapping: IdMapping?,
    ): SyncNote {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val mappingState = mapping?.extras?.let { VaultMappingState.resolve(it) }
        val vaultAttachments = materializeAttachments(root, path, note.attachments, mappingState)
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val existingRaw = VaultDocumentTree.findByRelativePath(root, path)?.let { readFileContent(it) }
        var doc = VaultNoteDocument.fromNote(
            note.copy(attachments = vaultAttachments),
            path,
            assignedNotebookName = notebookName,
            exportNotebookExplicitly = true,
        )
        doc = VaultNoteDocument.mergePreservedFromDisk(existingRaw, doc)
        val removedPaths = VaultSyncImport.removedVaultAttachmentPaths(
            note.copy(attachments = vaultAttachments),
            mappingState,
        )
        var body = doc.body
        if (removedPaths.isNotEmpty()) {
            body = VaultAttachments.removeVaultLinksForPaths(body, path, removedPaths)
        }
        val bodyWithLinks = VaultAttachments.mergeAttachmentLinks(body, path, vaultAttachments)
        doc = doc.copy(body = bodyWithLinks)
        val fullText = doc.renderFullText()
        val hash = VaultContentHash.ofText(fullText)
        writeRaw(root, path, fullText)
        val file = VaultDocumentTree.findByRelativePath(root, path)
            ?: throw IOException("Written file not found: $path")
        return SyncNote(
            id = 0,
            idStr = path,
            title = note.title,
            content = fullText,
            lastModified = file.lastModified() / 1000,
            extra = hash,
        )
    }

    private fun materializeAttachments(
        root: DocumentFile,
        noteRelativePath: String,
        attachments: List<Attachment>,
        mappingState: VaultMappingState?,
    ): List<Attachment> {
        val names = mutableSetOf<String>()
        VaultDocumentTree.ensureDirectory(root, VaultPaths.assetDirectoryForNote(noteRelativePath))
            .listFiles().mapNotNull { it.name }.toCollection(names)
        return attachments.map { att ->
            when {
                VaultPaths.isVaultRelativeAttachment(att.path) -> att
                else -> {
                    reuseFromMappingBaseline(context, root, noteRelativePath, att, mappingState, names)
                        ?: materializePrivateAttachment(root, noteRelativePath, att, names)
                }
            }
        }
    }

    private fun reuseFromMappingBaseline(
        context: Context,
        root: DocumentFile,
        noteRelativePath: String,
        att: Attachment,
        mappingState: VaultMappingState?,
        names: MutableSet<String>,
    ): Attachment? {
        if (mappingState == null) return null
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val sourceUri = runCatching {
            when {
                VaultPaths.isVaultRelativeAttachment(att.path) ->
                    VaultDocumentTree.findByRelativePath(root, VaultPaths.toVaultRelative(att.path))?.uri
                att.path.startsWith("content://") || att.path.startsWith("file://") -> android.net.Uri.parse(att.path)
                else -> null
            }
        }.getOrNull() ?: return null
        val sourceHash = runCatching { VaultStreamHashes.hashUri(context, sourceUri) }.getOrNull()
            ?: return null
        for (stored in mappingState.vaultAttachmentPathSet) {
            val rel = VaultPaths.toVaultRelative(stored)
            if (!rel.startsWith("$assetDir/")) continue
            val file = VaultDocumentTree.findByRelativePath(root, rel) ?: continue
            if (!file.isFile) continue
            if (VaultStreamHashes.hashDocumentFile(context, file) != sourceHash) continue
            val fileName = VaultPaths.fileName(rel)
            names.add(fileName)
            return att.copy(
                path = stored,
                description = att.description.ifBlank { fileName },
                fileName = "",
            )
        }
        return null
    }

    private fun materializePrivateAttachment(
        root: DocumentFile,
        noteRelativePath: String,
        att: Attachment,
        names: MutableSet<String>,
    ): Attachment {
        return runCatching {
            VaultMediaWriter.copyAttachmentToVault(context, root, noteRelativePath, att, names)
        }.onFailure { Log.w(TAG, "materializeAttachments: keeping local ${att.path}", it) }
            .getOrDefault(att)
    }

    private suspend fun writeRaw(root: DocumentFile, relativePath: String, text: String) {
        if (!runVaultRecoveryPrelude(root)) {
            throw IOException("Vault recovery incomplete; refusing write to $relativePath")
        }
        val path = VaultPaths.normalizeNotePath(relativePath)
        val store = SafVaultWriteStore(context, root)
        val generationAtCapture = vaultSyncCoordinator.currentGeneration()
        val context = VaultWriteContext(store.vaultScopeId(), generationAtCapture)
        VaultRecoverableWrite(store).write(path, text, context)
        if (vaultSyncCoordinator.isStaleMapping(generationAtCapture)) {
            throw IOException("Vault generation changed during write; refusing to treat result as committed")
        }
    }

    private suspend fun runVaultRecoveryPrelude(root: DocumentFile): Boolean {
        val store = SafVaultWriteStore(context, root)
        val generation = vaultSyncCoordinator.currentGeneration()
        val outcome = VaultRecoveryService.recoverPending(store, generation)
        quarantinedNotePaths.clear()
        quarantinedNotePaths.addAll(outcome.quarantinedPaths)
        if (outcome.scanBlocked || outcome.writesBlocked) {
            Log.e(TAG, "Vault recovery blocked scan/writes quarantine=${outcome.quarantinedPaths}")
        }
        return !outcome.scanBlocked && !outcome.writesBlocked
    }

    private fun fileToSyncNote(file: DocumentFile, relativePath: String): SyncNote? {
        val raw = readFileContent(file) ?: return null
        val hash = VaultContentHash.ofText(raw)
        return SyncNote(
            id = 0,
            idStr = relativePath,
            content = raw,
            title = VaultPaths.noteBaseName(relativePath),
            lastModified = file.lastModified() / 1000,
            extra = hash,
        )
    }

    private fun uniqueNotePath(root: DocumentFile, desired: String, exclude: String? = null): String {
        var candidate = VaultPaths.normalizeNotePath(desired)
        var i = 2
        while (VaultDocumentTree.findByRelativePath(root, candidate) != null && candidate != exclude) {
            val base = VaultPaths.noteBaseName(desired)
            val parent = VaultPaths.parentPath(desired)
            val ext = if (desired.endsWith(".txt", ignoreCase = true)) ".txt" else ".md"
            candidate = VaultPaths.normalize(
                if (parent.isEmpty()) "$base-$i$ext" else "$parent/$base-$i$ext",
            )
            i++
        }
        return candidate
    }

    private fun getRootDocumentFile(): DocumentFile? {
        return DocumentFile.fromTreeUri(context, config.location)
    }

    private fun hasPermissionsAt(uri: Uri): Boolean {
        val perm = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return perm?.let { it.isReadPermission && it.isWritePermission } ?: false
    }

    private fun readFileContent(file: DocumentFile): String? {
        return context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }

    private inline fun <reified T> inStorage(block: () -> T): T? {
        return try {
            val duration = measureTimedValue { block() }
            Log.i(TAG, "inStorage: That took ${duration.duration} to complete")
            duration.value
        } catch (e: Exception) {
            Log.e(TAG, "Exception while storing: ${e.message}", e)
            null
        }
    }
}
