package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultLinkResolverTest {
    @Test
    fun bareFile_resolvesRelativeToNoteParentDirectory() {
        assertEquals(
            "Work/photo.jpg",
            VaultLinkResolver.resolve("Work/Meeting.md", "photo.jpg"),
        )
    }

    @Test
    fun nestedRelative_resolvesBesideNote() {
        assertEquals(
            "Work/Meeting/camera.jpg",
            VaultLinkResolver.resolve("Work/Meeting.md", "Meeting/camera.jpg"),
        )
    }

    @Test
    fun parentTraversalWithinVault() {
        assertEquals(
            "Work/photo.jpg",
            VaultLinkResolver.resolve("Work/Notes/Meeting.md", "../photo.jpg"),
        )
    }

    @Test
    fun parentTraversalEscapeRejected() {
        assertNull(VaultLinkResolver.resolve("Meeting.md", "../../outside.jpg"))
    }

    @Test
    fun percentEncodedSpaces() {
        assertEquals(
            "My Folder/photo a.jpg",
            VaultLinkResolver.resolve("My Folder/note.md", "photo%20a.jpg"),
        )
    }

    @Test
    fun plusSign_notDecodedAsSpace() {
        assertEquals(
            "Work/photo+1.jpg",
            VaultLinkResolver.resolve("Work/Note.md", "photo+1.jpg"),
        )
    }

    @Test
    fun invalidPercentEscape_returnsNull() {
        assertNull(VaultLinkResolver.resolve("Work/Note.md", "100%.jpg"))
    }

    @Test
    fun unicodePathSegments() {
        assertEquals(
            "日本/写真.jpg",
            VaultLinkResolver.resolve("日本/メモ.md", "写真.jpg"),
        )
    }

    @Test
    fun percentDecode_utf8Cyrillic() {
        val encoded = "%D0%A4%D0%BE%D1%82%D0%BE.jpg"
        assertEquals("Фото.jpg", VaultLinkResolver.decodePathSegment(encoded))
    }

    @Test
    fun percentDecode_emoji() {
        assertEquals("🙂.png", VaultLinkResolver.decodePathSegment("%F0%9F%99%82.png"))
    }

    @Test
    fun httpLeftUntouchedForResolver() {
        assertNull(VaultLinkResolver.resolve("Work/a.md", "https://example.com/x.png"))
    }
}
