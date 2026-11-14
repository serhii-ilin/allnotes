package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.flow.first
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod

/**
 * Reads the merged per-device logs for the Folder history screen.
 *
 * Only display data is produced here: no event is applied to a note, notebook or tag. A missing folder, a
 * missing backend or a failed read is an empty history, never an error.
 */
class VaultFolderHistory(
    private val backendProvider: BackendProvider,
    private val idMappingDao: IdMappingDao,
    private val noteDao: NoteDao,
    /** Events this device did not apply (Release B); none when absent. */
    private val mismatchStore: HistoryMismatchStore? = null,
) : FolderHistory {

    override suspend fun load(): FolderHistorySnapshot {
        val access = backendProvider.syncProvider.value as? VaultFileAccess
            ?: return FolderHistorySnapshot()
        val history = runCatching { HistoryReader(access).read() }.getOrElse {
            return FolderHistorySnapshot()
        }
        val titles = noteTitlesByUid()
        val entries = history.items.asReversed().map { item ->
            val event = item.event
            HistoryEntry(
                timeMs = event.t,
                deviceLabel = deviceLabel(event.dev, history.manifests[event.dev]),
                op = event.op,
                knownOp = item.knownOp,
                noteTitle = event.id?.let { titles[it] },
                path = event.path,
                to = event.to,
                name = event.name,
            )
        }
        val mismatches = mismatchStore?.readAll().orEmpty()
            .sortedByDescending { it.t }
            .map { m ->
                HistoryMismatch(
                    timeMs = m.t,
                    deviceLabel = deviceLabel(m.dev, history.manifests[m.dev]),
                    op = m.op,
                    reason = m.reason,
                    noteTitle = m.id?.let { titles[it] },
                    path = m.path ?: m.name,
                )
            }
        return FolderHistorySnapshot(
            entries = entries,
            skippedLines = history.skippedLines,
            mismatches = mismatches,
        )
    }

    /** Note title by front-matter id, so a binned note still shows its title while its mapping remains. */
    private suspend fun noteTitlesByUid(): Map<String, String> {
        val notes = noteDao.getAll(SortMethod.TITLE_ASC).first().associateBy { it.id }
        val titles = LinkedHashMap<String, String>()
        for (mapping in idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)) {
            val uid = VaultMappingState.resolve(mapping.extras)?.noteUid?.takeIf { it.isNotBlank() }
                ?: continue
            val title = notes[mapping.localNoteId]?.title ?: continue
            titles.putIfAbsent(uid, title)
        }
        return titles
    }

    /**
     * The manifest already carries `Pixel 9 · a1b2c3` from this app; another client may write only `Pixel 9`.
     * Either way the row shows the model and the id prefix exactly once.
     */
    private fun deviceLabel(deviceId: String, manifest: DeviceManifest?): String {
        val shortId = deviceId.take(6)
        val name = manifest?.name?.takeIf { it.isNotBlank() }
            ?: return "Unknown device · $shortId"
        return if (name.endsWith("· $shortId") || name.endsWith("·$shortId")) name else "$name · $shortId"
    }
}
