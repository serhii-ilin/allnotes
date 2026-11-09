package org.qosp.notes.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Test
import org.qosp.notes.data.model.Note

/** Title-only drafts must survive [org.qosp.notes.data.repo.NoteRepositoryImpl.discardEmptyNotes]. */
class EditorTitleOnPauseRegressionTest {

    @Test
    fun noteWithTitleOnly_isNotEmpty() {
        assertFalse(Note(title = "Draft title", content = "").isEmpty())
    }
}
