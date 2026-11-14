package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryJson
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.data.sync.fs.vault.history.TestVaultFileAccess
import java.io.File

/**
 * Runs the platform-neutral conformance fixtures in `docs/spec/fixtures/` through the real implementation, so the
 * folder contract in `docs/spec/vault-format.md` and `docs/spec/algorithms.md` is proven by this build. Another
 * implementation reuses the same fixtures as its own conformance suite.
 */
class VaultContractFixturesTest {

    private val fixtures: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/spec/fixtures") }
        .first { it.isDirectory }

    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(File(fixtures, name).readText()).jsonObject

    private fun JsonElement?.str(): String? = (this as? JsonElement)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonElement)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonElement)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.cases(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun derivedIds() {
        for (c in load("ids.json").cases("cases")) {
            assertEquals(c.toString(), c["expected"].str(), VaultNoteIds.forFile(c["path"].str()!!, c["created"].long()))
        }
    }

    @Test
    fun names() {
        val f = load("names.json")
        for (c in f.cases("sanitize")) {
            assertEquals(c.toString(), c["expected"].str(), VaultPaths.sanitizeFileName(c["input"].str()!!))
        }
        for (c in f.cases("conflictPath")) {
            assertEquals(
                c.toString(),
                c["expected"].str(),
                VaultPaths.conflictPath(c["path"].str()!!, c["epochSeconds"].long()!!),
            )
            assertEquals(true, VaultPaths.isConflictCopyPath(c["expected"].str()!!))
        }
        for (c in f.cases("validateSegment")) {
            assertEquals(c.toString(), c["expected"].str(), VaultPaths.validateSegmentName(c["input"].str()!!)?.name)
        }
    }

    @Test
    fun render() {
        for (c in load("render.json").cases("cases")) {
            val n = c.getValue("note").jsonObject
            val note = Note(
                title = n["title"].str().orEmpty(),
                content = n["content"].str().orEmpty(),
                isList = n["isList"].bool() ?: false,
                taskList = n["tasks"]?.jsonArray.orEmpty().mapIndexed { i, t ->
                    NoteTask(i.toLong(), t.jsonObject["content"].str()!!, t.jsonObject["isDone"].bool()!!)
                },
                isPinned = n["isPinned"].bool() ?: false,
                color = n["color"].str()?.let(NoteColor::valueOf) ?: NoteColor.Default,
                tags = n["tags"]?.jsonArray.orEmpty().map { Tag(name = it.str()!!) },
                reminders = n["reminders"]?.jsonArray.orEmpty().map {
                    Reminder(name = it.jsonObject["name"].str()!!, noteId = 0, date = it.jsonObject["date"].long()!!)
                },
                creationDate = n["creationDate"].long()!!,
                modifiedDate = n["modifiedDate"].long()!!,
                notebookId = if (c["notebook"].str() != null) 1L else null,
            )
            val previous = c["previous"].str()
            // The same steps as StorageBackend.writeNoteAt, minus attachments.
            var doc = VaultNoteDocument.fromNote(
                note,
                c["path"].str()!!,
                assignedNotebookName = c["notebook"].str(),
                exportNotebookExplicitly = true,
                includeEmptyReminders = previous?.let { VaultNoteDocument.parse(it).frontMatter.reminders } != null,
            )
            doc = VaultNoteDocument.mergePreservedFromDisk(previous, doc)
            doc = doc.copy(frontMatter = doc.frontMatter.copy(id = c["id"].str()))
            assertEquals(c["name"].str(), c["expected"].str(), doc.renderFullText())
        }
    }

    @Test
    fun lineEdits() {
        val f = load("line-edits.json")
        for (c in f.cases("setId")) {
            assertEquals(
                c["name"].str(),
                c["expected"].str(),
                VaultNoteDocument.replaceNoteId(c["input"].str()!!, c["id"].str()!!),
            )
        }
        for (c in f.cases("addMissingDates")) {
            assertEquals(
                c["name"].str(),
                c["expected"].str(),
                VaultNoteDocument.withMissingDates(c["input"].str()!!, c["created"].long(), c["modified"].long()),
            )
        }
    }

    @Test
    fun parse() {
        val f = load("parse.json")
        for (c in f.cases("cases")) {
            val doc = VaultNoteDocument.parse(c["input"].str()!!)
            val fm = doc.frontMatter
            val e = c.getValue("expected").jsonObject
            val name = c["name"].str()
            assertEquals(name, e["id"].str(), fm.id)
            assertEquals(name, e["title"].str(), fm.title)
            assertEquals(name, (e["tags"] as? JsonArray)?.map { it.str() }, fm.tags)
            assertEquals(name, e["pinned"].bool(), fm.pinned)
            assertEquals(name, e["color"].str(), fm.color?.name)
            assertEquals(name, e["created"].long(), fm.created)
            assertEquals(
                name,
                (e["reminders"] as? JsonArray)?.map { it.jsonObject["name"].str() to it.jsonObject["date"].long() },
                fm.reminders?.map { it.name to it.date },
            )
            assertEquals(name, e["preservedYaml"].str(), fm.preservedYaml)
            assertEquals(name, e["body"].str(), doc.body)
        }
        for (c in f.cases("checklists")) {
            val raw = "---\nisList: true\n---\n" + c["body"].str()
            val note = VaultNoteDocument.toNote(VaultNoteDocument.parse(raw), c["path"].str()!!, lastModified = 0)
            assertEquals(
                c["name"].str(),
                c.getValue("expected").jsonArray.map { it.jsonObject["content"].str() to it.jsonObject["isDone"].bool() },
                note.taskList.map { it.content to it.isDone },
            )
        }
    }

    @Test
    fun canonicalHash() {
        for (g in load("canonical-hash.json").cases("groups")) {
            val path = g["path"].str()!!
            val hashes = g.getValue("variants").jsonArray.map { v ->
                val remote = SyncNote(id = 0, idStr = path, content = v.str(), title = "", lastModified = 1)
                VaultSyncImport.logicalHashFromRemoteFile(remote, Note(), path, null)
            }
            assertEquals(g["name"].str(), 1, hashes.distinct().size)
            g["expected"].str()?.let { assertEquals(g["name"].str(), it, hashes.first()) }
        }
    }

    @Test
    fun history() = runTest {
        val f = load("history.json")
        for (c in f.cases("encode")) {
            val e = c.getValue("event").jsonObject
            val event = HistoryEvent(
                eid = e["eid"].str()!!,
                dev = e["dev"].str()!!,
                seq = e["seq"].long()!!,
                lc = e["lc"].long()!!,
                t = e["t"].long()!!,
                op = e["op"].str()!!,
                id = e["id"].str(),
                path = e["path"].str(),
                to = e["to"].str(),
                name = e["name"].str(),
            )
            assertEquals(c["expectedLine"].str(), HistoryJson.json.encodeToString(HistoryEvent.serializer(), event))
            assertEquals(c["expectedMonthFile"].str(), HistoryPaths.monthlyEventsFile(event.t))
        }
        for (c in f.cases("read")) {
            val access = TestVaultFileAccess()
            c.getValue("files").jsonObject.forEach { (path, text) -> access.fs.writeText(path, text.str()!!) }
            val result = HistoryReader(access).read()
            val name = c["name"].str()
            assertEquals(name, c.getValue("expectedOrder").jsonArray.map { it.str() }, result.events.map { it.eid })
            assertEquals(
                name,
                c.getValue("expectedApplicable").jsonObject.mapValues { it.value.bool() },
                result.items.associate { it.event.eid to it.applicable },
            )
            assertEquals(name, c["expectedSkippedLines"].long()!!.toInt(), result.skippedLines)
        }
    }
}
