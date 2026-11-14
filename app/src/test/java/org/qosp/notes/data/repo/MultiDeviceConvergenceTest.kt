package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.ApplyOutcome
import org.qosp.notes.data.sync.fs.vault.history.DeviceManifest
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryJson
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.HistoryReadResult
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * HST-12: any set of devices that receives the same files converges, whatever the delivery order, with
 * duplicates, clock skew, a newer-format peer and a cloned install.
 *
 * Every device gets both Release B appliers, as `SyncModule` wires them in production. The appliers run on the
 * real repository harness, so the scan, the file sync and the history application are the production ones.
 */
@RunWith(RobolectricTestRunner::class)
class MultiDeviceConvergenceTest {

    private val watches = mutableMapOf<InMemoryVaultFs, DeviceWatch>()

    private fun TestScope.newVault(
        count: Int,
        stores: List<MutableMap<String, String>> = List(count) { mutableMapOf() },
    ): MultiDeviceVault = MultiDeviceVault(this, count, stores) { h ->
        val watch = watches.getOrPut(h.vaultFs) { DeviceWatch(h.vaultFs) }
        listOf(
            watch.wrap(
                HistoryNoteApplier(
                    h.noteDao,
                    h.idMappingDao,
                    h.db.reminderDao,
                    h.notebookDao,
                    VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
                    h.backendProvider,
                ),
            ),
            watch.wrap(
                HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao),
            ),
        )
    }

    /**
     * Scenario: Three phones converge whatever the delivery order (HST-12 AC1).
     */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder() = runTest {
        for (seed in 1..SEEDS) {
            val vault = newVault(3)
            try {
                val ledger = TextLedger(seed)
                randomRun(vault, Random(seed), ledger)

                val states = converge(vault, "seed $seed")

                assertConverged(states, "seed $seed")
                assertAppliersTouchedNoNoteFile(vault, "seed $seed")
                assertAllWrittenTextSurvives(vault, ledger, "seed $seed")
                assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "seed $seed")
            } finally {
                vault.close()
            }
        }
    }

    /** HST-12 AC2: a device whose wall clock is five hours behind does not change the outcome. */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder_withAClockFiveHoursBehind() = runTest {
        val vault = newVault(3)
        val a = vault[0]
        val b = vault[1]
        val c = vault[2]
        val ledger = TextLedger(1)
        try {
            val plan = a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            b.notebookRepository.insert(Notebook("Trips"))
            b.folderHistoryService.flush()
            c.notebookRepository.insert(Notebook("Colder"))
            c.folderHistoryService.flush()

            // C's clock is five hours behind: every event it owns is stamped that much earlier.
            val skewed = mutableSetOf<String>()
            c.skewOwnEvents(seen = skewed, deltaMs = FIVE_HOURS_MS)
            val cId = c.deviceId()
            val cOwnEvents = HistoryReader(c.backend).read().events.filter { it.dev == cId }
            assertTrue("the scenario really has skewed events", cOwnEvents.isNotEmpty())
            assertTrue(cOwnEvents.all { it.t < System.currentTimeMillis() - FOUR_HOURS_MS })

            vault.syncthing(0, 1)
            vault.syncthing(0, 2)
            vault.syncthing(1, 2)
            vault.devices.forEach { it.syncNotes() }

            a.updateAndSync(a.getNote(plan)!!.copy(content = a.getNote(plan)!!.content + "\n" + ledger.marker()))
            a.folderHistoryService.flush()
            b.binAndSync(b.noteByTitle("Plan"))
            b.folderHistoryService.flush()
            c.skewOwnEvents(seen = skewed, deltaMs = FIVE_HOURS_MS)

            val states = converge(vault, "clock skew")

            assertConverged(states, "clock skew")
            assertAppliersTouchedNoNoteFile(vault, "clock skew")
            assertAllWrittenTextSurvives(vault, ledger, "clock skew")
            val merged = vault.devices.map { device ->
                HistoryReader(device.backend).read().events.map { it.eid }
            }
            assertTrue(merged.all { it == merged[0] })
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "clock skew")
        } finally {
            vault.close()
        }
    }

    /** HST-12 AC2: a duplicated log delivered as a Syncthing conflict copy is read once, not twice. */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder_afterADuplicatedLogArrives() = runTest {
        val vault = newVault(2)
        val a = vault[0]
        val b = vault[1]
        val ledger = TextLedger(2)
        try {
            val newNote = a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            a.insertAndSync(Note(title = "Keep", content = ledger.marker()))
            a.binAndSync(a.getNote(newNote)!!)
            a.restoreAndSync(a.getNote(newNote)!!)
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()

            // Syncthing copies A's log next to the original: same events, another file name.
            val logPath = a.ownEventsPaths().first()
            val duplicate = logPath.replace(".jsonl", ".sync-conflict-20261110-120000-ABCDEF.jsonl")
            val copy = a.vaultFs.readText(logPath)!!
            a.vaultFs.writeText(duplicate, copy)
            b.vaultFs.writeText(duplicate, copy)

            a.updateAndSync(a.getNote(newNote)!!.copy(content = a.getNote(newNote)!!.content + "\n" + ledger.marker()))
            a.folderHistoryService.flush()
            b.notebookRepository.insert(Notebook("Trips"))
            b.folderHistoryService.flush()

            val states = converge(vault, "duplicated log")

            assertConverged(states, "duplicated log")
            assertAppliersTouchedNoNoteFile(vault, "duplicated log")
            assertAllWrittenTextSurvives(vault, ledger, "duplicated log")
            val read = HistoryReader(b.backend).read()
            assertEquals("each event is read once", read.items.size, read.items.map { it.event.eid }.distinct().size)
            assertTrue(read.conflictingEids.isEmpty())
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "duplicated log")
        } finally {
            vault.close()
        }
    }

    /** HST-06 AC2 / HST-12 AC2: a `v: 2` device without `partialApplyOk` affects nothing; with it, known ops apply. */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder_withANewerFormatDevice() = runTest {
        val vault = newVault(2)
        val a = vault[0]
        val b = vault[1]
        val ledger = TextLedger(3)
        try {
            val plan = a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            a.insertAndSync(Note(title = "Keep", content = ledger.marker()))
            vault.syncthing(0, 1)
            b.syncNotes()

            val newer = "device-newer"
            a.writeForeignManifest(newer, partialApplyOk = false)
            a.appendForeignEvent(
                newer,
                foreignEvent(newer, seq = 1, lc = 1000, op = HistoryOps.NOTEBOOK_CREATE, name = "Trips"),
            )
            vault.syncthing(0, 1)

            a.syncNotes()
            b.syncNotes()
            assertTrue("a promise-less v2 device affects nothing", a.notebookNames().isEmpty())
            assertTrue(b.notebookNames().isEmpty())
            assertTrue(a.mismatches().isEmpty() && b.mismatches().isEmpty())

            // The peer now promises that a partial apply of its newer format is safe.
            a.writeForeignManifest(newer, partialApplyOk = true)
            vault.syncthing(0, 1)
            a.syncNotes()
            b.syncNotes()
            assertEquals(listOf("Trips"), a.notebookNames())
            assertEquals(listOf("Trips"), b.notebookNames())

            // A v2 bin applies too: the file move is delivered, the event explains it before the two-scan rule.
            a.vaultFs.renameFile("Plan.md", ".trash/Plan.md")
            a.appendForeignEvent(
                newer,
                foreignEvent(newer, seq = 2, lc = 1001, op = HistoryOps.NOTE_BIN, id = a.uid(plan), path = "Plan.md", to = ".trash/Plan.md"),
            )
            vault.syncthing(0, 1)

            a.syncNotes()
            b.syncNotes()
            assertTrue("the v2 bin applied on A", a.getNote(plan)!!.isDeleted)
            assertTrue("the v2 bin applied on B", b.noteByTitle("Plan").isDeleted)

            val states = converge(vault, "newer format")
            assertConverged(states, "newer format")
            assertAppliersTouchedNoNoteFile(vault, "newer format")
            assertAllWrittenTextSurvives(vault, ledger, "newer format")
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "newer format")
        } finally {
            vault.close()
        }
    }

    /** HST-08 / HST-12 AC2: deleting `.allnotes/` everywhere mid-run changes nothing and the devices still converge. */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder_afterDeletingTheHistoryFolderMidRun() = runTest {
        val vault = newVault(3)
        val a = vault[0]
        val b = vault[1]
        val c = vault[2]
        val ledger = TextLedger(4)
        try {
            val plan = a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            a.insertAndSync(Note(title = "Keep", content = ledger.marker()))
            b.notebookRepository.insert(Notebook("Trips"))
            b.folderHistoryService.flush()
            c.tagRepository.insert(Tag("work"))
            c.folderHistoryService.flush()
            val beforeDelete = converge(vault, "before deleting history")

            vault.devices.indices.forEach { vault.deleteHistoryFolder(it) }
            vault.devices.forEach { it.syncNotes() }
            assertEquals(
                "deleting .allnotes changed no note, notebook or tag",
                beforeDelete,
                vault.devices.map { it.state() },
            )

            a.binAndSync(a.getNote(plan)!!)
            a.folderHistoryService.flush()
            b.notebookRepository.insert(Notebook("After"))
            b.folderHistoryService.flush()
            c.updateAndSync(c.noteByTitle("Keep").copy(content = c.noteByTitle("Keep").content + "\n" + ledger.marker()))
            c.folderHistoryService.flush()

            val states = converge(vault, "deleted history")
            assertConverged(states, "deleted history")
            assertAppliersTouchedNoNoteFile(vault, "deleted history")
            assertAllWrittenTextSurvives(vault, ledger, "deleted history")
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "deleted history")
        } finally {
            vault.close()
        }
    }

    /** HST-02 / HST-12 AC2: a cloned install detects its shared id, re-identifies, and the three phones converge. */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder_withAClonedDevice() = runTest {
        val stores = List(3) { mutableMapOf<String, String>() }
        val vault = newVault(3, stores)
        val a = vault[0]
        val b = vault[1]
        val c = vault[2]
        val ledger = TextLedger(5)
        try {
            a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            a.folderHistoryService.flush()
            val originalId = a.deviceId()

            // Phone C was restored from a copy of A's app data at this point.
            stores[2].putAll(stores[0])
            // A keeps writing under its own id without C knowing.
            a.updateAndSync(a.noteByTitle("Plan").copy(content = a.noteByTitle("Plan").content + "\n" + ledger.marker()))
            a.notebookRepository.insert(Notebook("Trips"))
            a.folderHistoryService.flush()
            val aFolderBefore = a.historyBytes()
            vault.syncthing(0, 2)

            // The clone records something of its own, so its first flush reads the logs and sees A's lines.
            c.notebookRepository.insert(Notebook("C-first"))
            c.syncNotes()

            val newId = c.deviceId()
            assertNotEquals("the clone switches to its own id", originalId, newId)
            val reid = HistoryReader(c.backend).read().events.single { it.op == HistoryOps.DEVICE_REID }
            assertEquals(originalId, reid.name)
            assertEquals(newId, reid.dev)
            assertEquals("the clone never wrote into A's folder", aFolderBefore, a.historyBytes())

            b.tagRepository.insert(Tag("work"))
            b.folderHistoryService.flush()
            c.notebookRepository.insert(Notebook("Clone"))
            c.folderHistoryService.flush()

            val states = converge(vault, "cloned device")
            assertConverged(states, "cloned device")
            assertAppliersTouchedNoNoteFile(vault, "cloned device")
            assertAllWrittenTextSurvives(vault, ledger, "cloned device")
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "cloned device")
        } finally {
            vault.close()
        }
    }

    /** HST-09 AC3 / HST-12: a bin on A while B has an unsynced edit keeps the edit and the phones converge. */
    @Test
    fun aBinOnAWhileBEditsTheSameNote_keepsTheEditAndConverges() = runTest {
        val vault = newVault(2)
        val a = vault[0]
        val b = vault[1]
        val ledger = TextLedger(6)
        try {
            a.insertAndSync(Note(title = "Plan", content = ledger.marker()))
            a.insertAndSync(Note(title = "Keep", content = ledger.marker()))
            vault.syncthing(0, 1)
            b.syncNotes()

            val onB = b.noteByTitle("Plan")
            b.repository.updateNotes(
                onB.copy(content = onB.content + "\n" + ledger.marker("edited on B")),
                sync = false,
            )
            a.binAndSync(a.noteByTitle("Plan"))
            a.folderHistoryService.flush()

            vault.syncthing(0, 1)
            b.syncNotes()
            val mismatch = b.mismatches().single { it.op == HistoryOps.NOTE_BIN }
            assertEquals("Edited here; kept", mismatch.reason)
            assertTrue(b.getNote(onB.id)!!.content.contains("edited on B"))

            b.updateAndSync(b.getNote(onB.id)!!)
            vault.syncthing(1, 0)
            val states = converge(vault, "bin versus edit")

            assertConverged(states, "bin versus edit")
            assertAppliersTouchedNoNoteFile(vault, "bin versus edit")
            assertAllWrittenTextSurvives(vault, ledger, "bin versus edit")
            val planOnA = a.noteByTitle("Plan")
            assertTrue(!planOnA.isDeleted)
            assertTrue(planOnA.content.contains("edited on B"))
            assertHistoryLogConflictsAreOwnerOnlyAndStable(vault, "bin versus edit")
        } finally {
            vault.close()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Random run
    // ---------------------------------------------------------------------------------------------------------

    private suspend fun randomRun(vault: MultiDeviceVault, random: Random, ledger: TextLedger) {
        repeat(STEPS) {
            val deviceIndex = random.nextInt(vault.devices.size)
            val device = vault[deviceIndex]
            when (random.nextInt(ACTION_TYPES)) {
                0 -> createNote(device, random, ledger)
                1 -> editNote(device, random, ledger)
                2 -> binNote(device, random)
                3 -> restoreNote(device, random)
                4 -> purgeNote(device, random, ledger)
                5 -> createNotebook(device, random, ledger)
                6 -> renameNotebook(device, random, ledger)
                7 -> deleteNotebook(device, random)
                8 -> moveNoteToNotebook(device, random)
                9 -> createTag(device, random, ledger)
                10 -> renameTag(device, random, ledger)
                11 -> deleteTag(device, random)
                12, 13 -> {
                    val other = randomOther(deviceIndex, vault.devices.size, random)
                    vault.syncthing(deviceIndex, other)
                }
                14 -> device.syncNotes()
                else -> {
                    // Deliver the same pair twice, as a sync tool can.
                    val other = randomOther(deviceIndex, vault.devices.size, random)
                    vault.syncthing(deviceIndex, other)
                    vault.syncthing(deviceIndex, other)
                }
            }
            if (random.nextInt(4) == 0) device.syncNotes()
        }
    }

    private suspend fun createNote(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        val notebook = h.notebooks().randomOrNull(random)
        val id = ledger.next()
        h.insertAndSync(
            Note(title = "N-${ledger.seed}-$id", content = ledger.text(id), notebookId = notebook?.id),
        )
    }

    private suspend fun editNote(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        val note = h.liveNotes().randomOrNull(random) ?: return createNote(h, random, ledger)
        val edited = note.copy(content = note.content.trimEnd() + "\n" + ledger.text(ledger.next()))
        h.updateAndSync(edited)
    }

    private suspend fun binNote(h: NoteRepositoryVaultHarness, random: Random) {
        val note = h.liveNotes().randomOrNull(random) ?: return
        h.binAndSync(note)
    }

    private suspend fun restoreNote(h: NoteRepositoryVaultHarness, random: Random) {
        val note = h.binnedNotes().filter { h.userBinned(it) }.randomOrNull(random) ?: return
        h.restoreAndSync(note)
    }

    private suspend fun purgeNote(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        val note = h.binnedNotes().filter { h.userBinned(it) }.randomOrNull(random) ?: return
        ledger.markPurged(note.content)
        h.deleteAndSync(note)
    }

    private suspend fun createNotebook(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        if (random.nextInt(3) == 0) createNote(h, random, ledger)
        h.notebookRepository.insert(Notebook("NB-${ledger.seed}-${ledger.next()}"))
    }

    private suspend fun renameNotebook(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        val notebook = h.notebooks().randomOrNull(random) ?: return
        h.notebookRepository.update(notebook.copy(name = "${notebook.name}-R${ledger.next()}"))
        h.flushRemoteActions()
    }

    private suspend fun deleteNotebook(h: NoteRepositoryVaultHarness, random: Random) {
        val notebook = h.notebooks().randomOrNull(random) ?: return
        h.notebookRepository.delete(notebook)
        h.flushRemoteActions()
    }

    private suspend fun moveNoteToNotebook(h: NoteRepositoryVaultHarness, random: Random) {
        val note = h.liveNotes().randomOrNull(random) ?: return
        val notebook = h.notebooks().randomOrNull(random)
        h.updateAndSync(note.copy(notebookId = notebook?.id))
    }

    private suspend fun createTag(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        if (random.nextInt(3) == 0) createNote(h, random, ledger)
        h.tagRepository.insert(Tag("T-${ledger.seed}-${ledger.next()}"))
    }

    private suspend fun renameTag(h: NoteRepositoryVaultHarness, random: Random, ledger: TextLedger) {
        val tag = h.tags().randomOrNull(random) ?: return
        h.tagRepository.update(tag.copy(name = "${tag.name}-R${ledger.next()}"))
        h.flushRemoteActions()
    }

    private suspend fun deleteTag(h: NoteRepositoryVaultHarness, random: Random) {
        val tag = h.tags().randomOrNull(random) ?: return
        h.tagRepository.delete(tag)
        h.flushRemoteActions()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Convergence and invariants
    // ---------------------------------------------------------------------------------------------------------

    private suspend fun converge(vault: MultiDeviceVault, label: String): List<DeviceState> {
        var previous: List<DeviceSnapshot>? = null
        repeat(MAX_CONVERGENCE_ROUNDS) {
            deliverMesh(vault)
            vault.devices.forEach { it.syncNotes() }
            val snapshots = vault.devices.map { it.snapshot() }
            if (snapshots == previous && snapshots.map { it.state }.distinct().size == 1) {
                return snapshots.map { it.state }
            }
            previous = snapshots
        }
        val states = vault.devices.map { it.state() }
        throw AssertionError("$label: no convergence after $MAX_CONVERGENCE_ROUNDS full mesh rounds: $states")
    }

    private suspend fun deliverMesh(vault: MultiDeviceVault) {
        for (from in vault.devices.indices) {
            for (to in vault.devices.indices) {
                if (from != to) vault.syncthing(from, to)
            }
        }
    }

    private fun assertConverged(states: List<DeviceState>, label: String) {
        states.forEachIndexed { index, state ->
            assertEquals("$label: device ${index + 1} live notes", states[0].live, state.live)
            assertEquals("$label: device ${index + 1} binned notes", states[0].binned, state.binned)
            assertEquals("$label: device ${index + 1} notebooks", states[0].notebooks, state.notebooks)
            assertEquals("$label: device ${index + 1} tags", states[0].tags, state.tags)
        }
    }

    private fun assertAppliersTouchedNoNoteFile(vault: MultiDeviceVault, label: String) {
        vault.devices.forEachIndexed { index, device ->
            val violations = watches.getValue(device.vaultFs).violations
            assertTrue("$label: device ${index + 1} appliers wrote, moved or deleted $violations", violations.isEmpty())
        }
    }

    private suspend fun assertAllWrittenTextSurvives(vault: MultiDeviceVault, ledger: TextLedger, label: String) {
        val survivors = vault.devices.flatMap { it.allTextBlobs() }
        for (marker in ledger.written - ledger.purged) {
            assertTrue("$label: no live note, binned note or vault file still holds \"$marker\"", survivors.any { marker in it })
        }
    }

    /**
     * HST-12: a log conflict copy may only repeat its owner's own lines, and a further mesh round must not grow
     * it. A device that appended to another device's log would fail both checks.
     */
    private suspend fun assertHistoryLogConflictsAreOwnerOnlyAndStable(vault: MultiDeviceVault, label: String) {
        vault.devices.forEachIndexed { index, device ->
            device.historyConflictPaths().forEach { path ->
                val owner = path.removePrefix("${HistoryPaths.DEVICES}/").substringBefore('/')
                val events = device.vaultFs.readText(path).orEmpty().lines()
                    .filter { it.isNotBlank() }
                    .map { HistoryJson.json.decodeFromString<HistoryEvent>(it) }
                assertTrue(
                    "$label: device ${index + 1} has a foreign event in ${owner}'s log: " +
                        events.filter { it.dev != owner }.map { it.eid },
                    events.all { it.dev == owner },
                )
            }
        }
        val before = vault.devices.map { it.historyConflictBytes() }
        deliverMesh(vault)
        vault.devices.forEach { it.syncNotes() }
        val after = vault.devices.map { it.historyConflictBytes() }
        before.forEachIndexed { index, copies ->
            copies.forEach { (path, bytes) ->
                assertEquals(
                    "$label: device ${index + 1} kept growing the log conflict ${path.substringAfterLast('/')}",
                    bytes,
                    after[index][path],
                )
            }
            assertEquals(
                "$label: device ${index + 1} wrote another device's log",
                copies.keys,
                after[index].keys,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Device state and file helpers
    // ---------------------------------------------------------------------------------------------------------

    private data class NoteView(val title: String, val body: String, val notebook: String?, val tags: List<String>)

    private data class DeviceState(
        val live: Map<String, NoteView>,
        /** A binned note's body is a cache: the file in `.trash/` is the truth, so the bin compares ids. */
        val binned: Set<String>,
        val notebooks: List<String>,
        val tags: List<String>,
    )

    private data class DeviceSnapshot(val state: DeviceState, val files: Map<String, String>)

    private suspend fun NoteRepositoryVaultHarness.snapshot(): DeviceSnapshot =
        DeviceSnapshot(state(), noteFileBytes())

    private suspend fun NoteRepositoryVaultHarness.state(): DeviceState {
        val notebookNames = notebookDao.getAll().first().associate { it.id to it.name }
        val tagNames = db.tagDao.getAll().first().associate { it.id to it.name }
        val tagsByNote = db.noteTagDao.getAllNoteTagRelations().first()
            .groupBy({ it.noteId }, { tagNames[it.tagId] ?: "tag-${it.tagId}" })
            .mapValues { (_, names) -> names.sorted() }
        val uidByNote: Map<Long, String> = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .mapNotNull { mapping ->
                val uid = VaultMappingState.resolve(mapping.extras)?.noteUid?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                mapping.localNoteId to uid
            }
            .toMap()
        val live = mutableMapOf<String, NoteView>()
        val binned = mutableSetOf<String>()
        for (note in noteDao.getAll(SortMethod.TITLE_ASC).first()) {
            val uid = uidByNote[note.id] ?: error("note \"${note.title}\" has no allnotes-id on this device")
            if (note.isDeleted) {
                binned += uid
            } else {
                live[uid] = NoteView(
                    title = note.title,
                    body = note.content.trimEnd(),
                    notebook = note.notebookId?.let { notebookNames[it] },
                    tags = tagsByNote[note.id].orEmpty(),
                )
            }
        }
        return DeviceState(
            live = live,
            binned = binned,
            notebooks = notebookNames.values.sorted(),
            tags = tagNames.values.sorted(),
        )
    }

    private fun NoteRepositoryVaultHarness.noteFileBytes(): Map<String, String> =
        vaultFs.notePaths().associateWith { path ->
            vaultFs.openRead(path)?.use { it.readBytes().toHex() }.orEmpty()
        }

    private suspend fun NoteRepositoryVaultHarness.allTextBlobs(): List<String> =
        noteDao.getAll(SortMethod.TITLE_ASC).first().map { it.content } +
            vaultFs.notePaths().mapNotNull { vaultFs.readText(it) }

    private suspend fun NoteRepositoryVaultHarness.liveNotes(): List<Note> =
        noteDao.getAll(SortMethod.TITLE_ASC).first().filterNot { it.isDeleted }

    private suspend fun NoteRepositoryVaultHarness.binnedNotes(): List<Note> =
        noteDao.getDeleted(SortMethod.TITLE_ASC).first()

    private suspend fun NoteRepositoryVaultHarness.notebooks(): List<Notebook> = notebookDao.getAll().first()

    private suspend fun NoteRepositoryVaultHarness.tags(): List<Tag> = db.tagDao.getAll().first()

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    private suspend fun NoteRepositoryVaultHarness.notebookNames(): List<String> =
        notebookDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.mismatches() = folderHistory.load().mismatches

    private suspend fun NoteRepositoryVaultHarness.uid(noteId: Long): String =
        VaultMappingState.resolve(
            idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!.extras,
        )!!.noteUid

    private suspend fun NoteRepositoryVaultHarness.userBinned(note: Note): Boolean {
        val mapping = idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE) ?: return false
        return VaultMappingState.resolve(mapping.extras)?.userBinned == true
    }

    private suspend fun NoteRepositoryVaultHarness.ownEventsPaths(): List<String> {
        val dir = HistoryPaths.deviceDir(deviceId())
        return vaultFs.snapshotPaths()
            .filter { it.startsWith("$dir/") && HistoryPaths.isEventsFile(it.substringAfterLast('/')) }
            .sorted()
    }

    private fun NoteRepositoryVaultHarness.historyConflictPaths(): List<String> =
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") && ".sync-conflict-" in it }
            .sorted()

    private fun NoteRepositoryVaultHarness.historyConflictBytes(): Map<String, String> =
        historyConflictPaths().associateWith { vaultFs.readText(it).orEmpty() }

    private fun NoteRepositoryVaultHarness.historyBytes(): Map<String, String> =
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .associateWith { vaultFs.readText(it).orEmpty() }

    /**
     * Rewrites the events this device owns with a wall time [deltaMs] earlier, as a wrong phone clock would.
     * Only the newest ids are touched, so repeated calls keep the whole log skewed without drifting further.
     */
    private suspend fun NoteRepositoryVaultHarness.skewOwnEvents(seen: MutableSet<String>, deltaMs: Long) {
        for (path in ownEventsPaths()) {
            val lines = vaultFs.readText(path).orEmpty().lines().filter { it.isNotBlank() }
            var changed = false
            val rewritten = lines.map { line ->
                val event = HistoryJson.json.decodeFromString<HistoryEvent>(line)
                if (seen.add(event.eid)) {
                    changed = true
                    HistoryJson.json.encodeToString(event.copy(t = event.t - deltaMs))
                } else {
                    line
                }
            }
            if (changed) vaultFs.writeText(path, rewritten.joinToString("\n") + "\n")
        }
    }

    private fun NoteRepositoryVaultHarness.writeForeignManifest(deviceId: String, partialApplyOk: Boolean) {
        vaultFs.writeText(
            HistoryPaths.manifestPath(deviceId),
            HistoryJson.json.encodeToString(
                DeviceManifest(
                    deviceId = deviceId,
                    name = "Newer phone",
                    appVersion = "2.0",
                    writes = HistoryOps.ALL_V1,
                    partialApplyOk = partialApplyOk,
                ),
            ) + "\n",
        )
    }

    private fun NoteRepositoryVaultHarness.appendForeignEvent(deviceId: String, event: HistoryEvent) {
        val path = "${HistoryPaths.deviceDir(deviceId)}/${HistoryPaths.monthlyEventsFile(event.t)}"
        val existing = vaultFs.readText(path).orEmpty()
        vaultFs.writeText(path, existing + HistoryJson.json.encodeToString(event) + "\n")
    }

    private fun foreignEvent(
        deviceId: String,
        seq: Long,
        lc: Long,
        op: String,
        id: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
    ) = HistoryEvent(
        v = 2,
        eid = "$deviceId:$seq",
        dev = deviceId,
        seq = seq,
        lc = lc,
        t = System.currentTimeMillis(),
        op = op,
        id = id,
        path = path,
        to = to,
        name = name,
    )

    private fun randomOther(index: Int, count: Int, random: Random): Int {
        var other = random.nextInt(count)
        while (other == index) other = random.nextInt(count)
        return other
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Text this run wrote into note bodies, and the markers a user purge was allowed to destroy. */
    private class TextLedger(val seed: Int) {
        private var counter = 0
        val written = linkedSetOf<String>()
        val purged = linkedSetOf<String>()

        /** A number unique to one simulated user action, so every title, notebook and tag name is unique too. */
        fun next(): Int = counter++

        fun text(id: Int): String {
            val value = "text-$seed-$id"
            written += value
            return value
        }

        fun marker(suffix: String = ""): String {
            val value = text(next()) + if (suffix.isEmpty()) "" else "-$suffix"
            written += value
            return value
        }

        fun markPurged(body: String) {
            purged += MARKER.findAll(body).map { it.value }
        }

        private companion object {
            val MARKER = Regex("""text-\d+-\d+""")
        }
    }

    /** Fails the test when either applier changes a file outside `.allnotes/`, which events must never do. */
    private class DeviceWatch(private val fs: InMemoryVaultFs) {
        val violations = mutableListOf<String>()

        fun wrap(delegate: HistoryApplier): HistoryApplier = object : HistoryApplier {
            override suspend fun apply(
                history: HistoryReadResult,
                ownDeviceId: String,
                scannedPaths: Set<String>,
            ): ApplyOutcome {
                val before = noteFiles()
                val outcome = delegate.apply(history, ownDeviceId, scannedPaths)
                val after = noteFiles()
                for (path in before.keys + after.keys) {
                    if (before[path] != after[path]) violations += path
                }
                return outcome
            }
        }

        private fun noteFiles(): Map<String, String> =
            fs.notePaths().associateWith { path -> fs.openRead(path)?.use { it.readBytes().toHex() }.orEmpty() }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** HST-12 AC1 asks for 20 random delivery orders. */
        const val SEEDS = 20
        const val STEPS = 30
        const val ACTION_TYPES = 16
        const val MAX_CONVERGENCE_ROUNDS = 10
        const val FIVE_HOURS_MS = 5L * 60 * 60 * 1000
        const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000
    }
}
