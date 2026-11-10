package org.qosp.notes.data.sync.fs

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.IdMappingDao
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
import org.qosp.notes.data.sync.fs.vault.VaultHousekeeping
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
import org.qosp.notes.data.sync.fs.vault.VaultWriteStore
import org.qosp.notes.data.sync.fs.vault.VaultScanCache
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.data.sync.core.BackendValidationResult
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.ui.attachments.getAttachmentUri
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.measureTimedValue

class StorageBackend(
    private val context: Context,
    val config: StorageConfig,
    private val notebookDao: NotebookDao,
    private val idMappingDao: IdMappingDao,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    vaultFs: VaultFs? = null,
    private val vaultWriteClock: VaultWriteClock = VaultWriteClock(),
) : ISyncBackend {

    private val injectedVaultFs: VaultFs? = vaultFs
    private val lazySafVaultFs: VaultFs by lazy {
        val root = DocumentFile.fromTreeUri(context, config.location)
            ?: throw IOException("Storage location not accessible")
        SafVaultFs(context, root, onWrite = { vaultWriteClock.markWrite() })
    }

    private val quarantinedNotePaths = mutableSetOf<String>()
    private var vaultIndexCache: List<VaultDocumentTree.IndexedVaultFile>? = null

    private fun indexFs(fs: VaultFs): List<VaultDocumentTree.IndexedVaultFile> {
        vaultIndexCache?.let { return it }
        return VaultDocumentTree.indexNotes(fs).also { vaultIndexCache = it }
    }

    private fun invalidateVaultIndex() {
        vaultIndexCache = null
    }

    override val type: CloudService = CloudService.FILE_STORAGE

    // An unreachable SAF root counts as case-insensitive: folding more paths only avoids more collisions.
    override val caseInsensitivePaths: Boolean
        get() = runCatching { vaultFs().caseInsensitive }.getOrDefault(true)

    override val quarantinedPaths: Set<String>
        get() = quarantinedNotePaths.toSet()

    private fun pathKey(path: String): String =
        VaultPaths.normalizeNotePath(path).let { if (caseInsensitivePaths) it.lowercase() else it }

    companion object {
        private const val TAG = "StorageBackend"
    }

    private fun vaultFs(): VaultFs = injectedVaultFs ?: lazySafVaultFs

    /** Per-vault scan cache in app-private storage, keyed by the vault scope (one instance per backend). */
    private val scanCache: VaultScanCache by lazy {
        val scopeId = vaultFs().scopeId
        // A new scope means the vault folder changed: drop other scopes' caches, which hold plaintext
        // copies of the previous vault.
        deleteOtherScopeCaches(scopeId)
        VaultScanCache(File(context.filesDir, "vault-scan-cache/$scopeId.json"))
    }

    private val bypassScanCache = AtomicBoolean(false)

    /**
     * Manual "rescan vault now" must see fresh bytes even when mtime/size did not change. Only flags
     * the next scan: it clears the cache itself under the vault lock, off the caller's (main) thread.
     */
    fun clearScanCache() {
        bypassScanCache.set(true)
    }

    private fun deleteOtherScopeCaches(keepScopeId: String) {
        val dir = File(context.filesDir, "vault-scan-cache")
        val keepName = "$keepScopeId.json"
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepName) file.delete()
        }
    }

    private fun vaultWriteStore(fs: VaultFs): VaultWriteStore = when (fs) {
        is SafVaultFs -> SafVaultWriteStore(context, fs.root, fs)
        is InMemoryVaultFs -> FsVaultWriteStore(context, fs)
        else -> FsVaultWriteStore(context, fs)
    }

    override suspend fun isAvailable(): AvailabilityStatus = VaultIo.run {
        return@run try {
            if (injectedVaultFs != null) {
                return@run if (vaultFs().canRead() && vaultFs().canWrite()) {
                    AvailabilityStatus.Available
                } else {
                    AvailabilityStatus.Unavailable("In-memory vault not writable")
                }
            }
            val root = getRootDocumentFile()
            when {
                root == null -> AvailabilityStatus.Unavailable("Storage location not accessible")
                !hasPermissionsAt(config.location) -> AvailabilityStatus.Unavailable(
                    "Missing storage permissions - please reconfigure sync",
                )
                !vaultFs().canRead() -> AvailabilityStatus.Unavailable("Cannot read from storage location")
                !vaultFs().canWrite() -> AvailabilityStatus.Unavailable("Cannot write to storage location")
                else -> AvailabilityStatus.Available
            }
        } catch (e: Exception) {
            AvailabilityStatus.Unavailable("Storage access error: ${e.message ?: "Unknown error"}")
        }
    }

    override suspend fun createNote(note: Note, linkExisting: Boolean): SyncNote = VaultIo.run {
        val fs = vaultFs()
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val desired = VaultPaths.titleToNotePath(
            note.title,
            note.isMarkdownEnabled,
            parentDir = VaultPaths.parentForNotebook(notebookName, currentParent = ""),
        )
        if (linkExisting) {
            linkableExistingPath(fs, note, desired, notebookName)?.let { existing ->
                return@run pathToSyncNote(fs, existing)
            }
        }
        val relativePath = uniqueNotePath(fs, desired, forNoteId = note.id)
        return@run writeNoteAt(relativePath, note, mapping = null)
    }

    override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping = VaultIo.run {
        val fs = vaultFs()
        val currentPath = VaultPaths.vaultMappingPath(mapping.storageUri)
            ?: throw IllegalArgumentException("Vault path missing in mapping")
        val currentParent = VaultPaths.parentPath(currentPath)
        val notebookNameForCheck = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        // The file follows the note's notebook: a changed notebook moves it (and its assets) like a title rename.
        val desiredPath = VaultPaths.titleToNotePath(
            title = note.title,
            markdown = note.isMarkdownEnabled,
            parentDir = VaultPaths.parentForNotebook(notebookNameForCheck, currentParent),
        )
        val targetPath = when {
            desiredPath == currentPath -> currentPath
            desiredPath.equals(currentPath, ignoreCase = true) -> desiredPath
            else -> uniqueNotePath(fs, desiredPath, exclude = currentPath, forNoteId = note.id)
        }
        var mappingState = mapping.extras?.let { VaultMappingState.resolve(it) }
        val oldRaw = fs.readText(currentPath)
        if (mappingState != null && !oldRaw.isNullOrBlank() &&
            mappingState.baselineHash.isNotEmpty()
        ) {
            // Reuse the raw text read above instead of re-reading the file.
            val diskSync = oldRaw?.let { pathToSyncNote(currentPath, it, fs.lastModifiedEpochSec(currentPath) * 1000) }
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
                // Written files end their body with a newline that the note's text does not; ignore trailing whitespace.
                val diskBodyHash = VaultContentHash.ofText(
                    VaultNoteDocument.parse(diskSync.content.orEmpty()).body.trimEnd(),
                )
                val localBody = if (note.isList) note.taskListToMd() else note.content
                val localBodyHash = VaultContentHash.ofText(localBody.trimEnd())
                val concurrentEdit = rawDiffers && diskBodyHash != localBodyHash
                if (concurrentEdit) {
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
            VaultNoteRelocation.relocateOwnedAssets(fs, currentPath, targetPath, ownedBeforeRemap)
        }
        val mappingForWrite = mappingState?.let {
            mapping.copy(extras = VaultMappingState.encode(it))
        } ?: mapping
        val writePath = if (targetPath.equals(currentPath, ignoreCase = true)) {
            currentPath
        } else {
            targetPath
        }
        // oldRaw is the single read of the current file in this method: pass it through so writeNoteAt
        // preserves the on-disk front matter without re-reading (the recovery journal still reads it once).
        val syncNote = writeNoteAt(
            writePath,
            noteForWrite,
            mappingForWrite,
            preservedDiskRaw = oldRaw,
            renameFromPath = currentPath.takeIf { it != targetPath },
        )
        if (targetPath != currentPath) {
            if (targetPath.equals(currentPath, ignoreCase = true)) {
                fs.renameFile(currentPath, targetPath)
            } else {
                fs.delete(currentPath)
                if (currentParent != VaultPaths.parentPath(targetPath)) deleteFolderIfEmpty(fs, currentParent)
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
        val mappingState = mapping.extras?.let { VaultMappingState.resolve(it) }
        val trashed = mappingState?.trashedPath?.let { VaultPaths.normalizeNotePath(it) }
        var removedTrash = false
        if (trashed != null && removeAssets) {
            removedTrash = fs.delete(trashed)
            deleteEmptyTrashFolders(fs, trashed)
            invalidateVaultIndex()
        }
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@run removedTrash
        if (removeAssets) {
            deleteOwnedAssets(fs, path, mappingState?.vaultAttachmentPathSet.orEmpty())
        }
        // The note's file lives in the bin: whatever sits at the original path now is not this note's.
        if (trashed != null) return@run removedTrash
        return@run fs.delete(path)
    }

    override suspend fun trashNote(mapping: IdMapping): String? = VaultIo.run {
        val fs = vaultFs()
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@run null
        if (!fs.isFile(path)) return@run null
        val target = VaultPaths.uniqueSiblingPath(VaultPaths.normalize("${VaultPaths.TRASH_DIR}/$path")) {
            fs.isFile(it)
        }
        fs.renameFile(path, target)
        invalidateVaultIndex()
        if (fs.isFile(path)) {
            // The copy landed but the source would not go: undo, or the note would exist twice.
            fs.delete(path)
            if (fs.isFile(path)) {
                fs.delete(target)
                throw IOException("Cannot move $path to the bin")
            }
        }
        deleteFolderIfEmpty(fs, VaultPaths.parentPath(path))
        return@run target
    }

    override suspend fun restoreFromTrash(trashedPath: String, toPath: String, forNoteId: Long): String? =
        VaultIo.run {
            val fs = vaultFs()
            val from = VaultPaths.normalizeNotePath(trashedPath)
            if (!fs.isFile(from)) return@run null
            val target = uniqueNotePath(fs, toPath, forNoteId = forNoteId)
            fs.renameFile(from, target)
            invalidateVaultIndex()
            deleteEmptyTrashFolders(fs, from)
            return@run target
        }

    /** Drops folders left empty under the bin, up to (not including) `.trash` itself. */
    private fun deleteEmptyTrashFolders(fs: VaultFs, removedFile: String) {
        var folder = VaultPaths.parentPath(removedFile)
        while (folder.startsWith("${VaultPaths.TRASH_DIR}/")) {
            val remaining = runCatching { fs.listChildren(folder) }.getOrNull() ?: return
            if (remaining.isNotEmpty() || !fs.delete(folder)) return
            folder = VaultPaths.parentPath(folder)
        }
    }

    override suspend fun getNote(mapping: IdMapping): SyncNote? = VaultIo.run {
        val fs = vaultFs()
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@run null
        // A quarantined file may be half written: callers must see it as unreadable, never as the note's content.
        if (pathKey(path) in quarantinedNotePaths.map { pathKey(it) }) return@run null
        if (!fs.isFile(path)) return@run null
        return@run pathToSyncNote(fs, path)
    }

    override suspend fun getAll(knownMappedPaths: Set<String>): List<SyncNote>? = VaultIo.run {
        val fs = vaultFs()
        if (!fs.canRead()) return@run null
        if (!runVaultRecoveryPrelude()) {
            Log.w(TAG, "getAll: vault recovery blocked authoritative scan")
            return@run null
        }
        return@run try {
            if (bypassScanCache.getAndSet(false)) scanCache.clear()
            invalidateVaultIndex()
            (fs as? SafVaultFs)?.beginDirectoryCache()
            // Quarantined files may be half written: never read them, so Room keeps its copy.
            val quarantineKeys = quarantinedNotePaths.map { pathKey(it) }.toSet()
            val candidates = VaultDocumentTree.listNoteCandidates(fs)
                .filterNot { pathKey(it.relativePath) in quarantineKeys }
            val read = readNoteTextsWithCache(fs, candidates)
            val indexed = VaultDocumentTree.filterIndexed(candidates, read.rawTexts)
            // End the snapshot first: probing a file the listing omitted through the same snapshot would
            // miss it too, so the completeness check must look the file up live.
            (fs as? SafVaultFs)?.endDirectoryCache()
            val mappedToCheck = knownMappedPaths.filterNot { pathKey(it) in quarantineKeys }.toSet()
            val reported = VaultDocumentTree.reportedPaths(fs, indexed, mappedToCheck)
            val indexedAsMapped = indexed.map { it.copy(relativePath = reported.getValue(it.relativePath)) }
            if (!VaultDocumentTree.isIndexCompleteForMappedPaths(fs, indexedAsMapped, mappedToCheck)) {
                Log.w(TAG, "getAll: mapped note visible but missing from index; failing scan")
                return@run null
            }
            val notes = indexed.map { file ->
                val raw = read.rawTexts[file.relativePath] ?: throw IOException("Cannot read ${file.relativePath}")
                pathToSyncNote(reported.getValue(file.relativePath), raw, file.lastModifiedMs)
            }
            scanCache.save(read.cacheEntries)
            notes
        } catch (e: Exception) {
            Log.e(TAG, "getAll: scan failed", e)
            null
        } finally {
            (fs as? SafVaultFs)?.endDirectoryCache()
        }
    }

    private data class ScanReadResult(
        val rawTexts: Map<String, String>,
        val cacheEntries: Map<String, VaultScanCache.Entry>,
    )

    /** Reads each candidate at most once, reusing cached raw text when the listing (mtime, size) is unchanged. */
    private fun readNoteTextsWithCache(fs: VaultFs, candidates: List<VaultDocumentTree.IndexedVaultFile>): ScanReadResult {
        val cache = scanCache
        val nowMs = System.currentTimeMillis()
        val rawTexts = mutableMapOf<String, String>()
        val cacheEntries = mutableMapOf<String, VaultScanCache.Entry>()
        for (cand in candidates) {
            val cached = cache.reusableEntry(cand.relativePath, cand.lastModifiedMs, cand.size)
            val raw = when {
                cached != null -> cached.raw
                else -> fs.readText(cand.relativePath) ?: continue
            }
            rawTexts[cand.relativePath] = raw
            // Preserve the original entry (and its scan time) on a hit; store a fresh entry only for
            // known, stable metadata. Unknown metadata (mtime <= 0 or size < 0) is never cached.
            val entry = when {
                cached != null -> cached
                cand.lastModifiedMs > 0 && cand.size >= 0 ->
                    VaultScanCache.Entry(cand.lastModifiedMs, cand.size, raw, nowMs)
                else -> null
            }
            if (entry != null) cacheEntries[cand.relativePath] = entry
        }
        return ScanReadResult(rawTexts, cacheEntries)
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
        val unique = uniqueNotePath(fs, conflictPath)
        val content = remote.content ?: return@run null
        writeRaw(unique, content)
        return@run unique
    }

    private suspend fun writeNoteAt(
        relativePath: String,
        note: Note,
        mapping: IdMapping?,
        preservedDiskRaw: String? = null,
        renameFromPath: String? = null,
    ): SyncNote {
        val fs = vaultFs()
        val path = VaultPaths.normalizeNotePath(relativePath)
        val mappingState = mapping?.extras?.let { VaultMappingState.resolve(it) }
        val vaultAttachments = materializeAttachments(fs, path, note.attachments, mappingState)
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val existingRaw = preservedDiskRaw ?: fs.readText(path)
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
        writeRaw(path, fullText)
        val lastModified = fs.lastModifiedEpochSec(path).takeIf { it > 0 }
            ?: throw IOException("Written file not found: $path")
        return SyncNote(
            id = 0,
            idStr = path,
            title = note.title,
            content = fullText,
            lastModified = lastModified,
            extra = hash,
        )
    }

    private fun materializeAttachments(
        fs: VaultFs,
        noteRelativePath: String,
        attachments: List<Attachment>,
        mappingState: VaultMappingState?,
    ): List<Attachment> {
        val names = mutableSetOf<String>()
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val needsDir = attachments.any { !VaultPaths.isVaultRelativeAttachment(it.path) }
        if (needsDir) {
            fs.ensureDirectory(assetDir)
            fs.listChildren(assetDir).map { it.name }.toCollection(names)
        } else {
            fs.listChildren(assetDir).map { it.name }.toCollection(names)
        }
        return attachments.map { att ->
            when {
                VaultPaths.isVaultRelativeAttachment(att.path) -> att
                else -> {
                    reuseFromMappingBaseline(fs, noteRelativePath, att, mappingState, names)
                        ?: runCatching {
                            VaultMediaWriter.copyAttachmentToVaultFs(
                                context,
                                fs,
                                noteRelativePath,
                                att,
                                names,
                            )
                        }.onFailure { Log.w(TAG, "materializeAttachments: keeping local ${att.path}", it) }
                            .getOrDefault(att)
                }
            }
        }
    }

    private fun reuseFromMappingBaseline(
        fs: VaultFs,
        noteRelativePath: String,
        att: Attachment,
        mappingState: VaultMappingState?,
        names: MutableSet<String>,
    ): Attachment? {
        if (mappingState == null) return null
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val sourceHash = runCatching {
            when {
                VaultPaths.isVaultRelativeAttachment(att.path) -> {
                    val rel = VaultPaths.toVaultRelative(att.path)
                    VaultStreamHashes.hashVaultFsFile(fs, rel)
                }
                att.path.startsWith("content://") || att.path.startsWith("file://") -> {
                    VaultStreamHashes.hashUri(context, android.net.Uri.parse(att.path))
                }
                else -> {
                    getAttachmentUri(context, att.path)?.let { VaultStreamHashes.hashUri(context, it) }
                }
            }
        }.getOrNull() ?: return null
        for (stored in mappingState.vaultAttachmentPathSet) {
            val rel = VaultPaths.toVaultRelative(stored)
            if (!rel.startsWith("$assetDir/")) continue
            if (!fs.isFile(rel)) continue
            if (VaultStreamHashes.hashVaultFsFile(fs, rel) != sourceHash) continue
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

    /** Removes a folder a note moved out of, only when nothing is left in it (a failed listing counts as not empty). */
    private fun deleteFolderIfEmpty(fs: VaultFs, folder: String) {
        if (folder.isEmpty() || VaultHousekeeping.shouldSkipDirectory(folder.substringAfterLast('/'))) return
        val remaining = runCatching { fs.listChildren(folder) }.getOrNull() ?: return
        if (remaining.isEmpty()) fs.delete(folder)
    }

    private fun deleteOwnedAssets(fs: VaultFs, noteRelativePath: String, ownedVaultRelPaths: Set<String>) {
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val ownedRel = ownedVaultRelPaths.map { VaultPaths.toVaultRelative(it) }.toSet()
        for (rel in ownedRel) {
            if (rel.startsWith("$assetDir/")) fs.delete(rel)
        }
        // Best effort: a failed listing must neither look empty nor fail the delete.
        val remaining = runCatching { fs.listChildren(assetDir) }.getOrNull()
        if (remaining != null && remaining.isEmpty()) {
            fs.delete(assetDir)
        }
    }

    private suspend fun uniqueNotePath(
        fs: VaultFs,
        desired: String,
        exclude: String? = null,
        forNoteId: Long = -1L,
    ): String {
        // A path another note is mapped to is taken even when its file is not visible right now (momentarily
        // missing, or only a case variant): reusing it would put two notes on one file.
        val reserved = idMappingDao.getActiveStorageUris(CloudService.FILE_STORAGE, forNoteId)
            .mapNotNull { VaultPaths.vaultMappingPath(it) }
            .map { pathKey(it) }
            .toSet()
        var candidate = VaultPaths.normalizeNotePath(desired)
        var i = 2
        while ((fs.isFile(candidate) || pathKey(candidate) in reserved) && !candidate.equals(exclude, ignoreCase = true)) {
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

    private suspend fun linkableAtPath(
        fs: VaultFs,
        note: Note,
        rel: String,
        desired: String,
        desiredHash: String,
        notebookName: String?,
    ): String? {
        if (!fs.isFile(rel)) return null
        val raw = fs.readText(rel) ?: return null
        val sync = pathToSyncNote(fs, rel)
        val imported = VaultSyncImport.noteFromRemote(sync, note)
        val onDiskHash = VaultSyncImport.canonicalFileHash(
            imported,
            rel,
            notebookName,
            VaultNoteDocument.parse(raw).frontMatter.preservedYaml,
        )
        if (onDiskHash != desiredHash) return null
        val desiredBase = VaultPaths.noteBaseName(desired)
        val baseMatch = VaultPaths.noteBaseName(rel) == desiredBase
        val parentMatch = VaultPaths.parentPath(rel) == VaultPaths.parentPath(desired)
        return if (baseMatch || parentMatch || rel == VaultPaths.normalizeNotePath(desired)) rel else null
    }

    private suspend fun linkableExistingPath(
        fs: VaultFs,
        note: Note,
        desired: String,
        notebookName: String?,
    ): String? {
        val desiredHash = VaultSyncImport.canonicalFileHash(note, desired, notebookName, preservedYamlBaseline = null)
        val desiredNorm = VaultPaths.normalizeNotePath(desired)
        linkableAtPath(fs, note, desiredNorm, desired, desiredHash, notebookName)?.let { return it }
        val parent = VaultPaths.parentPath(desiredNorm)
        for (child in fs.listChildren(parent)) {
            if (child.isDirectory) continue
            if (!VaultHousekeeping.shouldIndexFile(child.name)) continue
            linkableAtPath(fs, note, child.relativePath, desired, desiredHash, notebookName)?.let { return it }
        }
        val indexed = indexFs(fs)
        for (file in indexed) {
            linkableAtPath(fs, note, file.relativePath, desired, desiredHash, notebookName)?.let { return it }
        }
        return null
    }

    private suspend fun writeRaw(relativePath: String, text: String) {
        val path = VaultPaths.normalizeNotePath(relativePath)
        val fs = vaultFs()
        if (!runVaultRecoveryPrelude()) {
            throw IOException("Vault recovery incomplete; refusing write to $relativePath")
        }
        if (quarantinedNotePaths.any { VaultPaths.normalizeNotePath(it) == path }) {
            throw IOException("Vault path quarantined; refusing write to $path")
        }
        val store = vaultWriteStore(fs)
        val generationAtCapture = vaultSyncCoordinator.currentGeneration()
        val writeContext = VaultWriteContext(store.vaultScopeId(), generationAtCapture)
        VaultRecoverableWrite(store).write(path, text, writeContext)
        vaultWriteClock.markWrite()
        invalidateVaultIndex()
        if (vaultSyncCoordinator.isStaleMapping(generationAtCapture)) {
            throw IOException("Vault generation changed during write; refusing to treat result as committed")
        }
    }

    private suspend fun runVaultRecoveryPrelude(): Boolean {
        val fs = vaultFs()
        val store = vaultWriteStore(fs)
        val generation = vaultSyncCoordinator.currentGeneration()
        val outcome = VaultRecoveryService.recoverPending(store, generation)
        quarantinedNotePaths.clear()
        quarantinedNotePaths.addAll(outcome.quarantinedPaths)
        if (outcome.scanBlocked || outcome.writesBlocked) {
            Log.e(TAG, "Vault recovery blocked scan/writes quarantine=${outcome.quarantinedPaths}")
        }
        return !outcome.scanBlocked
    }

    private fun pathToSyncNote(fs: VaultFs, relativePath: String): SyncNote {
        val raw = fs.readText(relativePath) ?: throw IOException("Cannot read $relativePath")
        return pathToSyncNote(relativePath, raw, fs.lastModifiedEpochSec(relativePath) * 1000)
    }

    private fun pathToSyncNote(relativePath: String, raw: String, lastModifiedMs: Long): SyncNote {
        return SyncNote(
            id = 0,
            idStr = relativePath,
            content = raw,
            title = VaultPaths.noteBaseName(relativePath),
            lastModified = lastModifiedMs / 1000,
            extra = VaultContentHash.ofText(raw),
        )
    }

    private fun getRootDocumentFile(): DocumentFile? =
        DocumentFile.fromTreeUri(context, config.location)

    private fun hasPermissionsAt(uri: Uri): Boolean {
        val perm = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return perm?.let { it.isReadPermission && it.isWritePermission } ?: false
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
