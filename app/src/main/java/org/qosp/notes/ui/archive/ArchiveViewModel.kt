package org.qosp.notes.ui.archive

import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.common.AbstractNotesViewModel

class ArchiveViewModel(
    noteRepository: NoteRepository,
    preferenceRepository: PreferenceRepository
) : AbstractNotesViewModel(preferenceRepository) {
    override val provideNotes = noteRepository::getArchived
}
