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
import org.qosp.notes.data.sync.fs.vault.FsVaultWriteStore
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.SafVaultWriteStore
import org.qosp.notes.data.sync.fs.vault.SafVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.data.sync.fs.vault.VaultIo
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultRecoveryService
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.fs.vault.VaultRecoverableWrite
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultWriteContext
import org.qosp.notes.data.sync.core.BackendValidationResult
import org.qosp.notes.preferences.CloudService
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.measureTimedValue

class StorageBackend(
    private val context: Context,
    private val config: StorageConfig,
    private val notebookDao: NotebookDao,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    /** When set, all vault tree I/O uses this filesystem (Robolectric / unit harness). */
    private val vaultFsOverride: VaultFs? = null,
) : ISyncBackend {

    private val quarantinedNotePaths = mutableSetOf<String>()

    override val type: CloudService = CloudService.FILE_STORAGE

    companion object {
        private const val TAG = "StorageBackend"

        fun withInMemoryVault(
            context: Context,
            config: StorageConfig,
            notebookDao: NotebookDao,
            vaultSyncCoordinator: VaultSyncCoordinator,
            vaultFs: InMemoryVaultFs = InMemoryVaultFs(),
        ): StorageBackend = StorageBackend(
            context,
            config,
            notebookDao,
            vaultSyncCoordinator,
            vaultFsOverride = vaultFs,
        )
    }

    override suspend fun isAvailable(): AvailabilityStatus = VaultIo.run {
        return@run try {
            vaultFsOverride?.let {
                return@run if (it.canRead() && it.canWrite()) {
                    AvailabilityStatus.Available
                } else {
                    AvailabilityStatus.Unavailable("In-memory vault not writable")
                }
            }
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

    override suspend fun createNote(note: Note): SyncNote = VaultIo.run {
        val fs = vaultFs()
        if (fs != null) {
            val desired = VaultPaths.titleToNotePath(note.title, note.isMarkdownEnabled)
            val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            linkableExistingPathFs(fs, note, desired, notebookName)?.let { existing ->
                return@run pathToSyncNote(fs, existing)
            }
            val relativePath = uniqueNotePathFs(fs, desired)
            return@run writeNoteAt(null, relativePath, note, mapping = null)
        }
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")
        val desired = VaultPaths.titleToNotePath(note.title, note.isMarkdownEnabled)
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        linkableExistingPath(root, note, desired, notebookName)?.let { existing ->
            val file = VaultDocumentTree.findByRelativePath(root, existing)
                ?: throw IOException("Cannot open existing note at $existing")
            return@run fileToSyncNote(file, existing)
                ?: throw IOException("Cannot read existing note at $existing")
        }
        val relativePath = uniqueNotePath(root, desired)
        return@run writeNoteAt(root, relativePath, note, mapping = null)
    }

    override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping = VaultIo.run {
        val fs = vaultFs()
        val root = if (fs == null) getRootDocumentFile() else null
        if (fs == null && root == null) throw IOException("Unable to access storage location")
        val currentPath = VaultPaths.vaultMappingPath(mapping.storageUri)
            ?: throw IllegalArgumentException("Vault path missing in mapping")
        val desiredPath = VaultPaths.titleToNotePath(
            title = note.title,
            markdown = note.isMarkdownEnabled,
            parentDir = VaultPaths.parentPath(currentPath),
        )
        val targetPath = if (desiredPath != currentPath) {
            if (fs != null) uniqueNotePathFs(fs, desiredPath, exclude = currentPath)
            else uniqueNotePath(root!!, desiredPath, exclude = currentPath)
        } else {
            currentPath
        }
        var mappingState = mapping.extras?.let { VaultMappingState.resolve(it) }
        val oldRaw = when {
            fs != null -> fs.readText(currentPath)
            else -> VaultDocumentTree.findByRelativePath(root!!, currentPath)?.let { readFileContent(it) }
        }
        val notebookNameForCheck = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        if (mappingState != null && !oldRaw.isNullOrBlank() &&
            mappingState.baselineHash.isNotEmpty()
        ) {
            val diskSync = when {
                fs != null && fs.isFile(currentPath) -> pathToSyncNote(fs, currentPath)
                root != null -> VaultDocumentTree.findByRelativePath(root, currentPath)
                    ?.let { fileToSyncNote(it, currentPath) }
                else -> null
            }
            if (diskSync != null) {
                val diskHash = VaultSyncImport.logicalHashFromRemoteFile(
                    diskSync,
                    note,
                    currentPath,
                    notebookNameForCheck,
                )
                val diskRawHash = VaultContentHash.ofText(diskSync.content.orEmpty())
                val rawBaseline = mappingState.baselineRawHash
                val rawDiffers = rawBaseline.isNotEmpty() && diskRawHash != rawBaseline
                val logicalDiffers = diskHash != mappingState.baselineHash
                if (rawDiffers && logicalDiffers) {
                    val conflictPath = createConflictCopy(diskSync)
                    mappingState = mappingState.copy(
                        conflictRemoteHash = diskHash,
                        conflictCopyPath = conflictPath ?: mappingState.conflictCopyPath,
                    )
                }
            }
        }
        val noteForWrite = if (targetPath != currentPath) {
            VaultNoteRelocation.remapNoteAttachments(note, currentPath, targetPath)
        } else {
            note
        }
        val ownedBeforeRemap = mappingState?.vaultAttachmentPathSet.orEmpty()
        if (targetPath != currentPath && mappingState != null) {
            val remappedPaths = mappingState.vaultAttachmentPaths.map {
                VaultNoteRelocation.remapVaultRelPath(it, currentPath, targetPath)
            }
            mappingState = mappingState.copy(vaultAttachmentPaths = remappedPaths)
        }
        if (targetPath != currentPath) {
            if (fs != null) {
                VaultNoteRelocation.relocateOwnedAssets(fs, currentPath, targetPath, ownedBeforeRemap)
            } else {
                VaultNoteRelocation.relocateOwnedAssets(
                    context,
                    root!!,
                    currentPath,
                    targetPath,
                    ownedBeforeRemap,
                )
            }
        }
        val mappingForWrite = mappingState?.let {
            mapping.copy(extras = VaultMappingState.encode(it))
        } ?: mapping
        val syncNote = writeNoteAt(
            root,
            targetPath,
            noteForWrite,
            mappingForWrite,
            preservedDiskRaw = oldRaw,
            renameFromPath = currentPath.takeIf { it != targetPath },
        )
        if (targetPath != currentPath) {
            when {
                fs != null -> fs.delete(currentPath)
                root != null -> VaultDocumentTree.findByRelativePath(root, currentPath)?.let { old ->
                    DocumentsContract.deleteDocument(context.contentResolver, old.uri)
                }
            }
        }
        val gen = vaultSyncCoordinator.currentGeneration()
        val notebookName = noteForWrite.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val clearedPrior = mappingState?.copy(conflictRemoteHash = null, conflictCopyPath = null)
        val state = VaultMappingState.fromDiskSync(
            syncNote.content.orEmpty(),
            noteForWrite,
            targetPath,
            VaultSyncImport.vaultPathsFromNote(noteForWrite),
            gen,
            notebookName,
            clearedPrior,
        )
        return@run mapping.copy(storageUri = targetPath, extras = VaultMappingState.encode(state))
    }

    override suspend fun deleteNote(mapping: IdMapping, removeAssets: Boolean): Boolean = VaultIo.run {
        val fs = vaultFs()
        val root = if (fs == null) getRootDocumentFile() else null
        if (fs == null && root == null) return@run false
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@run false
        val mappingState = mapping.extras?.let { VaultMappingState.resolve(it) }
        if (removeAssets) {
            if (fs != null) {
                deleteOwnedAssetsFs(fs, path, mappingState?.vaultAttachmentPathSet.orEmpty())
            } else {
                VaultNoteRelocation.deleteOwnedAssets(
                    context,
                    root!!,
                    path,
                    mappingState?.vaultAttachmentPathSet.orEmpty(),
                )
            }
        }
        return@run when {
            fs != null -> fs.delete(path)
            else -> {
                val file = VaultDocumentTree.findByRelativePath(root!!, path) ?: return@run false
                DocumentsContract.deleteDocument(context.contentResolver, file.uri)
            }
        }
    }

    override suspend fun getNote(mapping: IdMapping): SyncNote? = VaultIo.run {
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@run null
        vaultFs()?.let { fs ->
            if (!fs.isFile(path)) return@run null
            return@run pathToSyncNote(fs, path)
        }
        val root = getRootDocumentFile() ?: return@run null
        val file = VaultDocumentTree.findByRelativePath(root, path) ?: return@run null
        return@run fileToSyncNote(file, path)
    }

    override suspend fun getAll(knownMappedPaths: Set<String>): List<SyncNote>? = VaultIo.run {
        vaultFs()?.let { fs ->
            if (!fs.canRead()) return@run null
            return@run try {
                val indexed = VaultDocumentTree.indexNotes(fs)
                if (!VaultDocumentTree.isIndexCompleteForMappedPaths(fs, indexed, knownMappedPaths)) {
                    Log.w(TAG, "getAll: mapped note visible but missing from index; failing scan")
                    return@run null
                }
                indexed.map { file -> pathToSyncNote(fs, file.relativePath) }
            } catch (e: Exception) {
                Log.e(TAG, "getAll: scan failed", e)
                null
            }
        }
        val root = getRootDocumentFile() ?: return@run null
        if (!root.canRead()) return@run null
        if (!runVaultRecoveryPrelude(root)) {
            Log.w(TAG, "getAll: vault recovery blocked authoritative scan")
            return@run null
        }
        return@run try {
            val indexed = VaultDocumentTree.indexNotes(root)
            if (!VaultDocumentTree.isIndexCompleteForMappedPaths(root, indexed, knownMappedPaths)) {
                Log.w(TAG, "getAll: mapped note visible but missing from index; failing scan")
                return@run null
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

    suspend fun createConflictCopy(remote: SyncNote): String? = VaultIo.run {
        val fs = vaultFs()
        val original = remote.idStr
        val conflictPath = VaultPaths.conflictPath(original, remote.lastModified)
        val unique = if (fs != null) uniqueNotePathFs(fs, conflictPath) else {
            val root = getRootDocumentFile() ?: return@run null
            uniqueNotePath(root, conflictPath)
        }
        val content = remote.content ?: return@run null
        writeRaw(getRootDocumentFile(), unique, content)
        return@run unique
    }

    private suspend fun writeNoteAt(
        root: DocumentFile?,
        relativePath: String,
        note: Note,
        mapping: IdMapping?,
        preservedDiskRaw: String? = null,
        renameFromPath: String? = null,
    ): SyncNote {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val mappingState = mapping?.extras?.let { VaultMappingState.resolve(it) }
        val fs = vaultFs()
        val vaultAttachments = if (fs != null) {
            materializeAttachmentsFs(fs, path, note.attachments, mappingState)
        } else {
            materializeAttachments(root!!, path, note.attachments, mappingState)
        }
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val existingRaw = preservedDiskRaw
            ?: when {
                fs != null -> fs.readText(path)
                else -> VaultDocumentTree.findByRelativePath(root!!, path)?.let { readFileContent(it) }
            }
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
        if (renameFromPath != null) {
            val oldDir = VaultPaths.assetDirectoryForNote(renameFromPath)
            body = VaultAttachments.stripLinksUnderAssetDirectory(body, renameFromPath, oldDir)
            val oldOwned = mappingState?.vaultAttachmentPathSet.orEmpty()
            if (oldOwned.isNotEmpty()) {
                body = VaultAttachments.removeVaultLinksForPaths(
                    body,
                    renameFromPath,
                    oldOwned.map { VaultPaths.toVaultRelative(it) }.toSet(),
                )
            }
        }
        if (removedPaths.isNotEmpty()) {
            body = VaultAttachments.removeVaultLinksForPaths(body, path, removedPaths)
        }
        val bodyWithLinks = VaultAttachments.mergeAttachmentLinks(body, path, vaultAttachments)
        doc = doc.copy(body = bodyWithLinks)
        val fullText = doc.renderFullText()
        val hash = VaultContentHash.ofText(fullText)
        writeRaw(root, path, fullText)
        val lastModified = when {
            fs != null -> fs.lastModifiedEpochSec(path)
            else -> VaultDocumentTree.findByRelativePath(root!!, path)
                ?.lastModified()?.div(1000)
                ?: throw IOException("Written file not found: $path")
        }
        return SyncNote(
            id = 0,
            idStr = path,
            title = note.title,
            content = fullText,
            lastModified = lastModified,
            extra = hash,
        )
    }

    private fun materializeAttachmentsFs(
        fs: VaultFs,
        noteRelativePath: String,
        attachments: List<Attachment>,
        mappingState: VaultMappingState?,
    ): List<Attachment> {
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        if (attachments.any { VaultPaths.isVaultRelativeAttachment(it.path) }) {
            fs.ensureDirectory(assetDir)
        }
        return attachments.map { att ->
            if (VaultPaths.isVaultRelativeAttachment(att.path)) att else att
        }
    }

    private fun deleteOwnedAssetsFs(fs: VaultFs, noteRelativePath: String, ownedVaultRelPaths: Set<String>) {
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val ownedRel = ownedVaultRelPaths.map { VaultPaths.toVaultRelative(it) }.toSet()
        for (rel in ownedRel) {
            if (rel.startsWith("$assetDir/")) fs.delete(rel)
        }
        if (fs.listChildren(assetDir).isEmpty()) {
            fs.delete(assetDir)
        }
    }

    private fun uniqueNotePathFs(fs: VaultFs, desired: String, exclude: String? = null): String {
        var candidate = VaultPaths.normalizeNotePath(desired)
        var i = 2
        while (fs.isFile(candidate) && candidate != exclude) {
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

    private suspend fun linkableExistingPathFs(
        fs: VaultFs,
        note: Note,
        desired: String,
        notebookName: String?,
    ): String? {
        val desiredHash = VaultSyncImport.canonicalFileHash(note, desired, notebookName, preservedYamlBaseline = null)
        val desiredBase = VaultPaths.noteBaseName(desired)
        val indexed = VaultDocumentTree.indexNotes(fs)
        for (file in indexed) {
            val rel = file.relativePath
            val raw = fs.readText(rel) ?: continue
            val sync = pathToSyncNote(fs, rel)
            val imported = VaultSyncImport.noteFromRemote(sync, note)
            val onDiskHash = VaultSyncImport.canonicalFileHash(
                imported,
                rel,
                notebookName,
                VaultNoteDocument.parse(raw).frontMatter.preservedYaml,
            )
            if (onDiskHash != desiredHash) continue
            val baseMatch = VaultPaths.noteBaseName(rel) == desiredBase
            val parentMatch = VaultPaths.parentPath(rel) == VaultPaths.parentPath(desired)
            if (baseMatch || parentMatch || rel == desired) return rel
        }
        return null
    }

    private fun materializeAttachments(
        root: DocumentFile,
        noteRelativePath: String,
        attachments: List<Attachment>,
        mappingState: VaultMappingState?,
    ): List<Attachment> {
        val names = mutableSetOf<String>()
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val needsDir = attachments.any { !VaultPaths.isVaultRelativeAttachment(it.path) }
        if (needsDir) {
            VaultDocumentTree.ensureDirectory(root, assetDir)
                .listFiles().mapNotNull { it.name }.toCollection(names)
        } else {
            VaultDocumentTree.findDirectory(root, assetDir)
                ?.listFiles().orEmpty().mapNotNull { it.name }.toCollection(names)
        }
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

    private suspend fun writeRaw(root: DocumentFile?, relativePath: String, text: String) {
        val path = VaultPaths.normalizeNotePath(relativePath)
        vaultFs()?.let { fs ->
            val store = FsVaultWriteStore(fs)
            val generationAtCapture = vaultSyncCoordinator.currentGeneration()
            val writeContext = VaultWriteContext(store.vaultScopeId(), generationAtCapture)
            VaultRecoverableWrite(store).write(path, text, writeContext)
            if (vaultSyncCoordinator.isStaleMapping(generationAtCapture)) {
                throw IOException("Vault generation changed during write; refusing to treat result as committed")
            }
            return
        }
        val safRoot = root ?: throw IOException("Vault recovery incomplete; refusing write to $relativePath")
        if (!runVaultRecoveryPrelude(safRoot)) {
            throw IOException("Vault recovery incomplete; refusing write to $relativePath")
        }
        val store = SafVaultWriteStore(context, safRoot)
        val generationAtCapture = vaultSyncCoordinator.currentGeneration()
        val writeContext = VaultWriteContext(store.vaultScopeId(), generationAtCapture)
        VaultRecoverableWrite(store).write(path, text, writeContext)
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

    private suspend fun linkableExistingPath(
        root: DocumentFile,
        note: Note,
        desired: String,
        notebookName: String?,
    ): String? {
        val desiredHash = VaultSyncImport.canonicalFileHash(note, desired, notebookName, preservedYamlBaseline = null)
        val desiredBase = VaultPaths.noteBaseName(desired)
        val indexed = VaultDocumentTree.indexNotes(root)
        for ((rel, file) in indexed) {
            val raw = readFileContent(file) ?: continue
            val imported = VaultSyncImport.noteFromRemote(
                fileToSyncNote(file, rel) ?: continue,
                note,
            )
            val onDiskHash = VaultSyncImport.canonicalFileHash(
                imported,
                rel,
                notebookName,
                VaultNoteDocument.parse(raw).frontMatter.preservedYaml,
            )
            if (onDiskHash != desiredHash) continue
            val baseMatch = VaultPaths.noteBaseName(rel) == desiredBase
            val parentMatch = VaultPaths.parentPath(rel) == VaultPaths.parentPath(desired)
            if (baseMatch || parentMatch || rel == desired) return rel
        }
        return null
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

    private fun vaultFs(): VaultFs? = vaultFsOverride

    private fun pathToSyncNote(fs: VaultFs, relativePath: String): SyncNote {
        val raw = fs.readText(relativePath) ?: throw IOException("Cannot read $relativePath")
        return SyncNote(
            id = 0,
            idStr = relativePath,
            content = raw,
            title = VaultPaths.noteBaseName(relativePath),
            lastModified = fs.lastModifiedEpochSec(relativePath),
            extra = VaultContentHash.ofText(raw),
        )
    }

    private fun getRootDocumentFile(): DocumentFile? {
        if (vaultFsOverride != null) return null
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
