package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Backfill adds front-matter ids safely: one bad file cannot fail a sync, and all devices write the same bytes. */
@RunWith(RobolectricTestRunner::class)
class VaultNoteIdBackfillTest {

    /** Reports a stable old mtime so the scan cache can be reused, like files Syncthing has already settled. */
    private class FixedMtimeVaultFs(
        private val delegate: InMemoryVaultFs,
        private val mtimeMs: Long = 1_000_000L,
    ) : VaultFs by delegate {
        override fun readText(relativePath: String): String? = delegate.readText(relativePath)

        override fun lastModifiedEpochSec(relativePath: String): Long = mtimeMs / 1000

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = mtimeMs) }
    }

    /** Counts [VaultFs.readText] calls while reporting a stable old mtime for listing. */
    private class CountingVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var readTextCalls: Int = 0
            private set

        fun reset() {
            readTextCalls = 0
        }

        override fun readText(relativePath: String): String? {
            // History metadata reads are not note reads; this test is about the note scan/backfill.
            if (!relativePath.startsWith("${HistoryPaths.ROOT}/")) readTextCalls++
            return delegate.readText(relativePath)
        }

        override fun lastModifiedEpochSec(relativePath: String): Long = OLD_MTIME_MS / 1000

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = OLD_MTIME_MS) }
    }

    private companion object {
        const val OLD_MTIME_MS = 1_000_000L
    }

    @Test
    fun unwritableFile_doesNotFailTheSync_orDropThePush() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.wrapBackend { backend ->
            object : ISyncBackend by backend {
                override suspend fun replaceNoteText(
                    relativePath: String,
                    text: String,
                    expectedRawHash: String,
                ): Boolean = throw IOException("simulated read-only file")
            }
        }
        h.vaultFs.writeText("Plain.md", "---\ntitle: \"Plain\"\ncreated: 1700000000\n---\nhello\n")
        // The import lands before the backfill; only the id write fails.
        h.syncNotes()
        val note = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
        h.repository.updateNotes(
            note.copy(content = "edited", modifiedDate = note.modifiedDate + 10),
            sync = false,
        )

        val result = h.repository.syncNotes()
        h.flushRemoteActions()

        assertTrue("backfill must not change the sync result", result is Success)
        val raw = h.vaultFs.readText("Plain.md").orEmpty()
        assertTrue("the local edit was not pushed: $raw", raw.contains("edited"))
    }

    @Test
    fun twoPhones_backfillTheSameBytes() = runTest {
        val files = listOf(
            "Alpha.md" to "---\ntitle: \"Alpha\"\ncreated: 1700000000\n---\nalpha\n",
            "Beta.md" to "---\ntitle: \"Beta\"\ncreated: 1700000100\n---\nbeta\n",
            // No created: the id must still agree, using the file's creation time.
            "Gamma.md" to "---\ntitle: \"Gamma\"\n---\ngamma\n",
        )
        suspend fun phone(scope: String): NoteRepositoryVaultHarness {
            val fs = InMemoryVaultFs(scopeId = scope, caseInsensitive = true)
            files.forEach { (path, body) -> fs.writeText(path, body) }
            val h = NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs))
            h.syncNotes()
            return h
        }

        val a = phone("phone-a")
        val b = phone("phone-b")

        for ((path, _) in files) {
            val rawA = a.vaultFs.readText(path).orEmpty()
            val rawB = b.vaultFs.readText(path).orEmpty()
            assertEquals("$path differs between phones", rawA, rawB)
            assertNotNull("$path did not gain an id", VaultNoteDocument.parse(rawA).frontMatter.id)
        }
    }

    /**
     * The text is decoded as UTF-8, so writing it back would replace Latin-1 bytes with U+FFFD, and a leading BOM
     * hides the front matter. Backfill leaves such files byte-for-byte alone.
     */
    @Test
    fun filesThatDoNotRoundTrip_areNotRewritten() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val latin1 = "---\ntitle: \"Café\"\n---\ndéjà vu\n".toByteArray(Charsets.ISO_8859_1)
        val bom = "﻿---\ntitle: \"Bom\"\n---\nbody\n".toByteArray(Charsets.UTF_8)
        h.vaultFs.writeBytes("Latin.md", latin1, "text/markdown")
        h.vaultFs.writeBytes("Bom.md", bom, "text/markdown")

        h.syncNotes()
        h.syncNotes()

        assertArrayEquals(latin1, h.vaultFs.openRead("Latin.md")!!.use { it.readBytes() })
        assertArrayEquals(bom, h.vaultFs.openRead("Bom.md")!!.use { it.readBytes() })
    }

    /**
     * Phones import a file without `created` at different times (say, before and after an edit). The id still
     * comes from the path, so it agrees; each file records the modification time that device saw.
     */
    @Test
    fun twoPhones_differentFileTimes_fileWithoutCreated_agreeOnTheId_onlyTheDatesDiffer() = runTest {
        val body = "---\ntitle: \"Gamma\"\n---\ngamma\n"
        suspend fun phone(scope: String, mtimeMs: Long): String {
            val fs = InMemoryVaultFs(scopeId = scope, caseInsensitive = true)
            fs.writeText("Gamma.md", body)
            NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs, mtimeMs)).syncNotes()
            return fs.readText("Gamma.md").orEmpty()
        }

        val rawA = phone("phone-a", 1_000_000L)
        val rawB = phone("phone-b", 9_000_000L)

        val a = VaultNoteDocument.parse(rawA)
        val b = VaultNoteDocument.parse(rawB)
        assertNotNull(a.frontMatter.id)
        assertEquals(a.frontMatter.id, b.frontMatter.id)
        assertEquals(1_000L, a.frontMatter.created)
        assertEquals(1_000L, a.frontMatter.modified)
        assertEquals(9_000L, b.frontMatter.created)
        assertEquals(9_000L, b.frontMatter.modified)
    }

    @Test
    fun externalEditDuringBackfill_isNotOverwritten_andGetsAnIdNextSync() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val original = "---\ntitle: \"Plain\"\ncreated: 1700000000\n---\nhello\n"
        val external = original.replace("hello", "external edit")
        h.vaultFs.writeText("Plain.md", original)
        var injected = false
        h.wrapBackend { backend ->
            object : ISyncBackend by backend {
                override suspend fun replaceNoteText(
                    relativePath: String,
                    text: String,
                    expectedRawHash: String,
                ): Boolean {
                    if (!injected) {
                        injected = true
                        h.vaultFs.writeText(relativePath, external)
                    }
                    return backend.replaceNoteText(relativePath, text, expectedRawHash)
                }
            }
        }

        h.syncNotes()

        assertEquals("the external edit was overwritten", external, h.vaultFs.readText("Plain.md"))
        h.wrapBackend { it }
        h.syncNotes()
        val after = h.vaultFs.readText("Plain.md").orEmpty()
        assertTrue("the external body was lost: $after", after.contains("external edit"))
        assertFalse("the old body came back: $after", after.contains("hello\n"))
        assertNotNull("the next sync did not add an id", VaultNoteDocument.parse(after).frontMatter.id)
    }

    @Test
    fun filesWithIds_addNoReadsOutsideTheScan() = runTest {
        val fs = InMemoryVaultFs(scopeId = "read-count", caseInsensitive = true)
        val counting = CountingVaultFs(fs)
        val h = NoteRepositoryVaultHarness(this, fs, counting)
        val idA = VaultNoteIds.forFile("A.md", 1000L)
        val idB = VaultNoteIds.forFile("B.md", 2000L)
        h.vaultFs.writeText("A.md", "---\nallnotes-id: \"$idA\"\ncreated: 1000\nmodified: 1000\n---\nA body\n")
        h.vaultFs.writeText("B.md", "---\nallnotes-id: \"$idB\"\ncreated: 2000\nmodified: 1000\n---\nB body\n")
        h.syncNotes()
        counting.reset()

        h.syncNotes()

        // The attachment refresh reads each file once; the backfill must add none on top of that.
        assertEquals(2, counting.readTextCalls)
    }
}
