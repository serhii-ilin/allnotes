package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.preferences.CloudService

/**
 * DSL for in-memory vault + [StorageBackend] scenario tests (real sync helpers, fake tree).
 */
class VaultFolderTestHarness(
    val vaultFs: InMemoryVaultFs = InMemoryVaultFs(),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val vaultSyncCoordinator = mockk<VaultSyncCoordinator>(relaxed = true)
    private val config = StorageConfig(location = Uri.parse("content://test/vault"))

    val backend: StorageBackend = StorageBackend.withInMemoryVault(
        context,
        config,
        notebookDao,
        vaultSyncCoordinator,
        vaultFs,
    )

    init {
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        coEvery { vaultSyncCoordinator.currentGeneration() } returns 1L
        coEvery { vaultSyncCoordinator.isStaleMapping(any()) } returns false
    }

    fun seedNoteFile(
        relativePath: String,
        title: String,
        body: String,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
    ) {
        val note = Note(title = title, content = body)
        val doc = VaultNoteDocument.fromNote(note, relativePath, exportNotebookExplicitly = true)
        vaultFs.writeText(relativePath, doc.renderFullText())
        val assetDir = VaultPaths.assetDirectoryForNote(relativePath)
        attachments.forEach { (name, bytes) ->
            vaultFs.writeBytes("$assetDir/$name", bytes, VaultFs.mimeForPath(name))
        }
    }

    fun externalEdit(relativePath: String, newBody: String) {
        val raw = vaultFs.readText(relativePath).orEmpty()
        val doc = VaultNoteDocument.parse(raw)
        vaultFs.writeText(relativePath, doc.copy(body = newBody).renderFullText())
    }

    fun vaultPaths(): Set<String> = vaultFs.snapshotPaths()

    fun mappingWithAttachments(
        localId: Long,
        path: String,
        note: Note,
        attachmentVaultPaths: List<String>,
        baselineFromDisk: Boolean = true,
    ): IdMapping {
        val raw = vaultFs.readText(path).orEmpty()
        val gen = 1L
        val state = if (baselineFromDisk && raw.isNotEmpty()) {
            VaultMappingState.fromDiskSync(
                raw,
                note,
                path,
                attachmentVaultPaths,
                gen,
                null,
            )
        } else {
            VaultMappingState(baselineHash = "", vaultAttachmentPaths = attachmentVaultPaths, vaultGeneration = gen)
        }
        return IdMapping(
            localNoteId = localId,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
    }

    fun noteWithVaultAttachments(
        id: Long,
        title: String,
        body: String,
        paths: List<String>,
    ): Note = Note(
        id = id,
        title = title,
        content = body,
        attachments = paths.map {
            Attachment(Attachment.Type.IMAGE, it, VaultPaths.fileName(VaultPaths.toVaultRelative(it)))
        },
    )
}
