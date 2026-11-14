package org.qosp.notes.data.sync.fs.vault.history

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.msoul.datastore.defaultOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.repo.MultiDeviceVault
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.preferences.HistoryRetention
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * HST-13: each install deletes the months of its OWN log that ended longer ago than the "Keep folder
 * history" period, and nothing else. Another device's files, the manifest and the README always stay.
 */
@RunWith(RobolectricTestRunner::class)
class EachPhoneRemovesItsOwnOldHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val today = at("2026-11-11T12:00:00Z")

    /** HST-13 AC1: 3 months kept on 2026-11-11 deletes July and keeps August and November. */
    @Test
    fun threeMonths_deletesJuly_andKeepsAugustAndNovember() = runTest {
        val access = TestVaultFileAccess()
        access.seedMonth("phone-a", "2026-07", at("2026-07-05T10:00:00Z"))
        access.seedMonth("phone-a", "2026-08", at("2026-08-05T10:00:00Z"))
        access.seedMonth("phone-a", "2026-11", at("2026-11-05T10:00:00Z"))

        assertEquals(1, HistoryPruner().prune(access, "phone-a", 3, today))

        assertEquals(
            setOf(eventsPath("phone-a", "2026-08"), eventsPath("phone-a", "2026-11")),
            access.eventsFilesOf("phone-a"),
        )
        assertEquals(listOf(eventsPath("phone-a", "2026-07")), access.deletes)
    }

    /** HST-13 AC2: the other phone's January log is not this phone's to delete. */
    @Test
    fun anotherDevicesOldLog_isNeverDeleted() = runTest {
        val access = TestVaultFileAccess()
        access.seedMonth("phone-a", "2026-07", at("2026-07-05T10:00:00Z"))
        access.seedMonth("phone-b", "2026-01", at("2026-01-05T10:00:00Z"))

        assertEquals(1, HistoryPruner().prune(access, "phone-a", 3, today))

        assertNotNull(access.text(eventsPath("phone-b", "2026-01")))
        assertTrue(access.deletes.none { it.startsWith(HistoryPaths.deviceDir("phone-b")) })
    }

    /** HST-13 AC3: Forever is a no-op, even for years-old months. */
    @Test
    fun forever_keepsEveryFile() = runTest {
        val access = TestVaultFileAccess()
        access.seedMonth("phone-a", "2024-01", at("2024-01-05T10:00:00Z"))

        assertEquals(0, HistoryPruner().prune(access, "phone-a", null, today))

        assertNotNull(access.text(eventsPath("phone-a", "2024-01")))
        assertTrue(access.deletes.isEmpty())
    }

    /** The manifest, the README and anything else in the folder are not month logs. */
    @Test
    fun theManifest_theReadme_andUnknownFiles_areKept() = runTest {
        val access = TestVaultFileAccess()
        val dir = HistoryPaths.deviceDir("phone-a")
        access.seedMonth("phone-a", "2026-07", at("2026-07-05T10:00:00Z"))
        access.fs.writeText(HistoryPaths.manifestPath("phone-a"), "{}\n")
        access.fs.writeText(HistoryPaths.README, HistoryWriter.README_TEXT)
        access.fs.writeText("$dir/notes.txt", "not history\n")

        assertEquals(1, HistoryPruner().prune(access, "phone-a", 3, today))

        assertNotNull(access.text(HistoryPaths.manifestPath("phone-a")))
        assertNotNull(access.text(HistoryPaths.README))
        assertNotNull(access.text("$dir/notes.txt"))
    }

    /** A Syncthing conflict copy of an old own month is the same month: it goes too. */
    @Test
    fun aConflictCopyOfAnOldOwnMonth_isDeletedToo() = runTest {
        val access = TestVaultFileAccess()
        val dir = HistoryPaths.deviceDir("phone-a")
        val oldCopy = "$dir/events-2026-07.sync-conflict-20260901-120000-ABCDEF1.jsonl"
        val recentCopy = "$dir/events-2026-10.sync-conflict-20261001-120000-ABCDEF1.jsonl"
        access.seedMonth("phone-a", "2026-07", at("2026-07-05T10:00:00Z"))
        access.fs.writeText(oldCopy, eventLine("phone-a", at("2026-07-05T10:00:00Z"), seq = 2))
        access.fs.writeText(recentCopy, eventLine("phone-a", at("2026-10-05T10:00:00Z"), seq = 3))

        assertEquals(2, HistoryPruner().prune(access, "phone-a", 3, today))

        assertNull(access.text(oldCopy))
        assertNotNull("a copy of a kept month stays", access.text(recentCopy))
    }

    /** Names it cannot read as a month are left alone rather than guessed at. */
    @Test
    fun namesThatAreNotAMonthLog_areLeftAlone() = runTest {
        val access = TestVaultFileAccess()
        val dir = HistoryPaths.deviceDir("phone-a")
        val names = listOf(
            "events.jsonl",
            "events-notes.jsonl",
            "events-2026-13.jsonl",
            "events-26-07.jsonl",
            "notes.md",
        )
        names.forEach { access.fs.writeText("$dir/$it", "x\n") }

        assertEquals(0, HistoryPruner().prune(access, "phone-a", 3, today))

        names.forEach { assertNotNull(it, access.text("$dir/$it")) }
    }

    /** A folder that cannot be listed is a failed prune, not a failed sync. */
    @Test
    fun anUnreadableFolder_isNotAFailure() = runTest {
        val access = TestVaultFileAccess()
        val unreadable = object : VaultFileAccess by access {
            override suspend fun listVaultDir(relativePath: String): List<VaultFs.VaultFsEntry> =
                throw IOException("simulated provider failure")
        }

        assertEquals(0, HistoryPruner().prune(unreadable, "phone-a", 3, today))
    }

    /** HST-13 AC4: a failed delete keeps the file and is retried by the next flush, which still succeeds. */
    @Test
    fun aFailedDelete_isRetriedOnTheNextFlush_andNeverFailsTheFlush() = runTest {
        val access = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-a"))
        val provider = mockk<BackendProvider> {
            every { syncProvider } returns MutableStateFlow<ISyncBackend?>(TestFolderBackend(access))
        }
        val identity = DeviceIdentity(TestHistoryPrefs().repository, model = "Pixel 9", defaultScope = "folder-a")
        val deviceId = identity.deviceId()
        val oldPath = eventsPath(deviceId, "2026-07")
        access.fs.writeText(oldPath, eventLine(deviceId, at("2026-07-05T10:00:00Z")))
        // A recent event keeps the clock check from treating the whole log as years old.
        access.fs.writeText(
            eventsPath(deviceId, "2026-11"),
            eventLine(deviceId, at("2026-11-05T10:00:00Z"), seq = 2),
        )
        access.failedDeletes += oldPath
        val service = FolderHistoryService(
            identity = identity,
            outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME)),
            appVersion = "1.5.14",
            backendProvider = provider,
            retention = { 3 },
            now = { today },
        )

        service.flush()
        assertNotNull("a failed delete leaves the file for the next sync", access.text(oldPath))

        access.failedDeletes.clear()
        service.flush()
        assertNull(access.text(oldPath))
    }

    /** HST-13: the setting defaults to three months, and Forever has no month count. */
    @Test
    fun theRetentionSetting_defaultsToThreeMonths() {
        assertEquals(HistoryRetention.THREE_MONTHS, defaultOf<HistoryRetention>())
        assertEquals(listOf(1, 3, 6, 12), HistoryRetention.entries.mapNotNull { it.keepMonths })
        assertNull(HistoryRetention.FOREVER.keepMonths)
    }

    /**
     * HST-13 AC5: after the old months go, the Folder history screen shows only what remains, and the two
     * phones still agree on notes, notebooks and tags.
     */
    @Test
    fun afterPruning_folderHistoryShowsWhatRemains_andBothPhonesStillConverge() = runTest {
        val vault = MultiDeviceVault(this, 2) { h -> appliersFor(h) }
        val a = vault[0]
        val b = vault[1]
        try {
            val aId = a.deviceId()
            // Months are relative to the real clock: the pinned 2026 dates are covered above, and the test
            // must not depend on when the suite runs.
            val now = System.currentTimeMillis()
            val thisMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC))
            val oldMonth = thisMonth.minusMonths(5)
            val recentMonth = thisMonth.minusMonths(1)
            val oldTime = startOfDay(oldMonth)
            val recentTime = startOfDay(recentMonth)
            // An install this phone used before rotating its id left two months in this phone's own folder.
            a.vaultFs.writeText(eventsPath(aId, oldMonth.toString()), eventLine("old-install", oldTime, seq = 1))
            a.vaultFs.writeText(eventsPath(aId, recentMonth.toString()), eventLine("old-install", recentTime, seq = 2))

            a.notebookRepository.insert(Notebook("Trips"))
            a.tagRepository.insert(Tag("work"))
            val noteId = a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()

            a.binAndSync(a.getNote(noteId)!!)
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()
            vault.syncthing(1, 0)
            a.syncNotes()

            assertTrue(
                "the old month is in the history before pruning",
                a.folderHistory.load().entries.any { it.timeMs == oldTime },
            )
            assertNotNull(
                "the folder sync brought the old month to the other phone",
                b.vaultFs.readText(eventsPath(aId, oldMonth.toString())),
            )

            assertEquals(1, HistoryPruner().prune(a.backend, aId, 3, now))

            assertNull(a.vaultFs.readText(eventsPath(aId, oldMonth.toString())))
            assertNotNull(a.vaultFs.readText(eventsPath(aId, recentMonth.toString())))
            assertNotNull(
                "another phone's copy stays until the sync tool carries the deletion",
                b.vaultFs.readText(eventsPath(aId, oldMonth.toString())),
            )

            val remaining = a.folderHistory.load().entries
            assertFalse("the pruned month is no longer shown", remaining.any { it.timeMs == oldTime })
            assertTrue("the month that is still kept is shown", remaining.any { it.timeMs == recentTime })
            assertTrue(
                "this phone's own actions are still shown",
                remaining.any { it.op == HistoryOps.NOTE_BIN && it.timeMs != oldTime },
            )

            vault.syncthing(0, 1)
            b.syncNotes()
            vault.syncthing(1, 0)
            a.syncNotes()

            assertNull(
                "the sync tool carried the deletion to the other phone",
                b.vaultFs.readText(eventsPath(aId, oldMonth.toString())),
            )
            assertFalse(b.folderHistory.load().entries.any { it.timeMs == oldTime })

            assertEquals(a.notebookNames(), b.notebookNames())
            assertEquals(a.tagNames(), b.tagNames())
            assertTrue("the bin reached both phones", a.noteByTitle("Plan").isDeleted)
            assertTrue(b.noteByTitle("Plan").isDeleted)
        } finally {
            vault.close()
        }
    }

    private fun TestScope.appliersFor(h: NoteRepositoryVaultHarness): List<HistoryApplier> = listOf(
        HistoryNoteApplier(
            h.noteDao,
            h.idMappingDao,
            h.db.reminderDao,
            h.notebookDao,
            VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
            h.backendProvider,
        ),
        HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao),
    )

    private suspend fun NoteRepositoryVaultHarness.notebookNames(): List<String> =
        notebookDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.tagNames(): List<String> =
        db.tagDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    private fun TestVaultFileAccess.seedMonth(deviceId: String, month: String, t: Long) {
        fs.writeText(eventsPath(deviceId, month), eventLine(deviceId, t))
    }

    private fun TestVaultFileAccess.eventsFilesOf(deviceId: String): Set<String> =
        pathsUnder(HistoryPaths.deviceDir(deviceId))
            .filter { HistoryPaths.isEventsFile(it.substringAfterLast('/')) }
            .toSet()

    private fun eventLine(deviceId: String, t: Long, seq: Long = 1): String =
        HistoryJson.json.encodeToString(
            HistoryEvent(
                eid = "$deviceId:$seq",
                dev = deviceId,
                seq = seq,
                lc = seq,
                t = t,
                op = HistoryOps.NOTE_BIN,
                id = "3f2a",
                path = "Plan.md",
                to = ".trash/Plan.md",
            ),
        ) + "\n"

    private fun startOfDay(month: YearMonth): Long =
        month.atDay(10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
