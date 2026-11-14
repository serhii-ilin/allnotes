package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Attachment

class AttachmentExtensionsTest {

    @Test
    fun preferredExtension_audioMp4_isM4a() {
        assertEquals(".m4a", AttachmentExtensions.preferredExtension("audio/mp4", Attachment.Type.AUDIO))
    }

    @Test
    fun preferredExtension_imageJpeg_isJpg() {
        assertEquals(".jpg", AttachmentExtensions.preferredExtension("image/jpeg", Attachment.Type.IMAGE))
    }

    @Test
    fun ensureFileNameHasExtension_recordedClip_getsM4a() {
        val name = AttachmentExtensions.ensureFileNameHasExtension(
            "Recorded clip",
            "audio/mp4",
            Attachment.Type.AUDIO,
        )
        assertTrue(name.endsWith(".m4a"))
        assertEquals("Recorded clip.m4a", name)
    }

    @Test
    fun preferredExtension_genericUnknownMime_isBin() {
        assertEquals(".bin", AttachmentExtensions.preferredExtension(null, Attachment.Type.GENERIC))
    }

    @Test
    fun ensureFileNameHasExtension_keepsExisting() {
        val name = AttachmentExtensions.ensureFileNameHasExtension(
            "vacation.jpg",
            "image/png",
            Attachment.Type.IMAGE,
        )
        assertEquals("vacation.jpg", name)
    }
}
