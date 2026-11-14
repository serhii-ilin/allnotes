package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.flow.first
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.preferences.SortMethod

/**
 * Release B: makes other devices' notebook and tag actions land here.
 *
 * Every device folds the same merged events into the same per-name target, then moves its own rows toward
 * that target. Rows are never renamed in place: a rename becomes "create the target name, delete the old row
 * when it is unused". Files are never touched: notes carry their notebook and tags, so their new names arrive
 * with the files and the ordinary scan relinks them.
 */
class HistoryCatalogApplier(
    private val notebookDao: NotebookDao,
    private val noteDao: NoteDao,
    private val tagDao: TagDao,
    private val noteTagDao: NoteTagDao,
) : HistoryApplier {

    override suspend fun apply(
        history: HistoryReadResult,
        ownDeviceId: String,
        scannedPaths: Set<String>,
    ): ApplyOutcome {
        val targets = fold(history.items)
        var changed = applyNotebooks(targets[NOTEBOOK].orEmpty())
        changed = applyTags(targets[TAG].orEmpty()) || changed
        return ApplyOutcome(changed = changed)
    }

    /**
     * The deterministic target every device computes from the same history. A rename whose old name an
     * earlier event already renamed away or deleted lost its race: its new name is absent too.
     */
    private fun fold(items: List<HistoryReadResult.Item>): Map<String, Map<String, Target>> {
        val targets = mutableMapOf(
            NOTEBOOK to linkedMapOf<String, Target>(),
            TAG to linkedMapOf<String, Target>(),
        )
        for (item in items) {
            if (!item.knownOp || !item.applicable) continue
            val event = item.event
            val name = event.name?.takeIf { it.isNotBlank() } ?: continue
            val family = familyFor(event.op) ?: continue
            val names = targets.getValue(family)
            when (event.op) {
                HistoryOps.NOTEBOOK_CREATE -> if (isValidNotebookName(name)) names[name] = Target.PRESENT
                HistoryOps.TAG_CREATE -> names[name] = Target.PRESENT
                HistoryOps.NOTEBOOK_DELETE, HistoryOps.TAG_DELETE -> names[name] = Target.ABSENT
                HistoryOps.NOTEBOOK_RENAME -> {
                    val to = event.to?.takeIf { it.isNotBlank() } ?: continue
                    // A name that cannot be a folder never arrives as a notebook; the old row stays where it is.
                    if (!isValidNotebookName(to)) continue
                    if (names[name] == Target.ABSENT) {
                        names[to] = Target.ABSENT
                    } else {
                        names[name] = Target.ABSENT
                        names[to] = Target.PRESENT
                    }
                }
                HistoryOps.TAG_RENAME -> {
                    val to = event.to?.takeIf { it.isNotBlank() } ?: continue
                    if (names[name] == Target.ABSENT) {
                        names[to] = Target.ABSENT
                    } else {
                        names[name] = Target.ABSENT
                        names[to] = Target.PRESENT
                    }
                }
            }
        }
        return targets
    }

    private suspend fun applyNotebooks(targets: Map<String, Target>): Boolean {
        if (targets.isEmpty()) return false
        val notes = noteDao.getAll(SortMethod.TITLE_ASC).first()
        val rowsByName = notebookDao.getAll().first().groupBy { it.name }
        var changed = false
        for ((name, target) in targets) {
            val rows = rowsByName[name].orEmpty()
            when (target) {
                Target.PRESENT -> if (rows.isEmpty()) {
                    notebookDao.insert(Notebook(name = name))
                    changed = true
                }
                Target.ABSENT -> for (row in rows) {
                    if (notes.none { it.notebookId == row.id }) {
                        notebookDao.delete(row)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private suspend fun applyTags(targets: Map<String, Target>): Boolean {
        if (targets.isEmpty()) return false
        val rowsByName = tagDao.getAll().first().groupBy { it.name }
        var changed = false
        for ((name, target) in targets) {
            val rows = rowsByName[name].orEmpty()
            when (target) {
                Target.PRESENT -> if (rows.isEmpty()) {
                    tagDao.insert(Tag(name = name))
                    changed = true
                }
                Target.ABSENT -> for (row in rows) {
                    if (noteTagDao.getNotesByTagId(row.id).first().isEmpty()) {
                        tagDao.delete(row)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private enum class Target { PRESENT, ABSENT }

    private companion object {
        const val NOTEBOOK = "notebook"
        const val TAG = "tag"

        fun familyFor(op: String): String? = when (op) {
            HistoryOps.NOTEBOOK_CREATE, HistoryOps.NOTEBOOK_RENAME, HistoryOps.NOTEBOOK_DELETE -> NOTEBOOK
            HistoryOps.TAG_CREATE, HistoryOps.TAG_RENAME, HistoryOps.TAG_DELETE -> TAG
            else -> null
        }

        fun isValidNotebookName(name: String): Boolean = VaultPaths.validateSegmentName(name) == null
    }
}
