package org.qosp.notes.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteRepositoryDiscardEmptyNotesTest {

    @Test
    fun `discardEmptyNotes skips a note whose editor still has pending state`() = runTest {
        val harness = NoteRepositoryVaultHarness(this)
        try {
            val noteId = harness.insertLocal(Note())
            harness.editorSessionRegistry.markPending(noteId)

            assertFalse(harness.repository.discardEmptyNotes())
            assertNotNull(harness.getNote(noteId))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `discardEmptyNotes still deletes blank notes without an editor session`() = runTest {
        val harness = NoteRepositoryVaultHarness(this)
        try {
            val noteId = harness.insertLocal(Note())

            assertTrue(harness.repository.discardEmptyNotes())
            assertNull(harness.getNote(noteId))
        } finally {
            harness.close()
        }
    }
}
