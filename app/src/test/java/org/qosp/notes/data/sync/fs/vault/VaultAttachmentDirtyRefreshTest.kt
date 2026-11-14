package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.junit.Test

/**
 * Orchestration-level attachment rules while dirty (not just pure resolver helpers).
 */
class VaultAttachmentDirtyRefreshTest {

    private fun remote(path: String, body: String) = SyncNote(
        id = 0,
        idStr = path,
        title = "t",
        content = """
            |---
            |pinned: false
            |archived: false
            |hidden: false
            |isList: false
            |markdown: true
            |color: "Default"
            |tags: []
            |notebook: ""
            |---
            |$body
        """.trimMargin(),
        lastModified = 1L,
        extra = "hash",
    )

    @Test
    fun removedVaultAttachment_staysRemovedThroughRefreshAndPush() {
        val path = "Work/Note.md"
        val vaultRel = "vaultrel:Work/Note/x.jpg"
        val remote = remote(path, "![x](Note/x.jpg)")
        val baseline = setOf(vaultRel)
        val localAttachments = emptyList<Attachment>()
        val afterRefresh = VaultSyncImport.attachmentsAfterDirtyRefresh(localAttachments, remote, baseline)
        assertTrue(afterRefresh.isEmpty())
        val disk = VaultSyncImport.noteFromRemote(remote)
        val localNote = Note(title = "t", content = "removed image locally", attachments = afterRefresh)
        val baselineHash = VaultSyncImport.canonicalFileHash(
            Note(title = "t", content = "had image"),
            path,
            null,
            "",
        )
        assertTrue(VaultSyncImport.isLocallyDirty(localNote, path, VaultMappingState(baselineHash), null))
        val afterPush = VaultSyncImport.mergeNoteAfterPush(
            localNote,
            disk,
            VaultMappingState(
                baselineHash = baselineHash,
                vaultAttachmentPaths = listOf(vaultRel),
            ),
            path,
            null,
        )
        assertFalse(afterPush.attachments.any { it.path == vaultRel })
    }

    @Test
    fun removedVaultAttachment_staysRemovedOnRefresh() {
        val path = "Work/Note.md"
        val remote = remote(path, "![x](Note/x.jpg)")
        val baseline = setOf("vaultrel:Work/Note/x.jpg")
        val local = emptyList<Attachment>()
        val merged = VaultSyncImport.attachmentsAfterDirtyRefresh(local, remote, baseline)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun pendingPrivate_survivesRefreshAndPushMerge() {
        val pending = Attachment(Attachment.Type.IMAGE, "content://p", "p")
        val local = listOf(pending)
        val remote = remote("Work/Note.md", "no images")
        val merged = VaultSyncImport.attachmentsAfterDirtyRefresh(local, remote, emptySet())
        assertEquals(1, merged.size)
        assertEquals("content://p", merged[0].path)

        val disk = Note(title = "t", content = "no images", attachments = emptyList())
        val afterPush = VaultSyncImport.mergeNoteAfterPush(
            Note(title = "t", content = "edited locally", attachments = local),
            disk,
            VaultMappingState("h"),
            "Work/Note.md",
            null,
        )
        assertEquals("edited locally", afterPush.content)
        assertTrue(afterPush.attachments.any { it.path == "content://p" })
    }

    @Test
    fun lateVaultFile_addedOnlyWhenNotInBaseline() {
        val path = "Work/Note.md"
        val remote = remote(path, "![new](Note/new.jpg)")
        val local = listOf(Attachment(Attachment.Type.IMAGE, "content://local", "l"))
        val merged = VaultSyncImport.attachmentsAfterDirtyRefresh(local, remote, emptySet())
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.path.startsWith("vaultrel:Work/Note/new") })
    }

    @Test
    fun baselinePreservedYaml_notTakenFromLiveRemoteFile() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "same")
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(note, path, "Journal", preservedYamlBaseline = ""),
            preservedYaml = "",
        )
        val withForeignOnDisk = "---\naliases:\n  - x\n---\nsame\n"
        val hashIfWrong = VaultSyncImport.canonicalFileHash(note, path, "Journal", withForeignOnDisk)
        val hashCorrect = VaultSyncImport.canonicalFileHash(note, path, "Journal", "")
        assertEquals(state.baselineHash, hashCorrect)
        assertTrue(hashIfWrong != state.baselineHash)
    }
}
