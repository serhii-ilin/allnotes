package org.qosp.notes.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultLinkResolver

class TaskListMarkwonBindingsTest {

    @Test
    fun previewTask_vaultContextMatchesParentNotePath() {
        val taskText = "Fix shelf ![Damage](photos/shelf.jpg)"
        val plan = planTaskRowRender(
            text = taskText,
            inPreview = true,
            isChecked = false,
            vaultRelativePath = "Work/Repairs.md",
        )
        assertEquals(taskText, plan.markdown)
        assertEquals("Work/Repairs.md", plan.vaultContext?.noteVaultRelativePath)
        val resolved = VaultLinkResolver.resolve(
            plan.vaultContext!!.noteVaultRelativePath,
            "photos/shelf.jpg",
        )
        assertEquals("Work/photos/shelf.jpg", resolved)
    }

    @Test
    fun nonVault_clearsVaultContext() {
        val plan = planTaskRowRender(
            text = "![](local.png)",
            inPreview = true,
            isChecked = false,
            vaultRelativePath = null,
        )
        assertNotNull(plan.markdown)
        assertNull(plan.vaultContext)
    }

    @Test
    fun distinctParentNotes_distinctVaultContext() {
        val planA = planTaskRowRender("t", inPreview = true, isChecked = false, vaultRelativePath = "Work/Repairs.md")
        val planB = planTaskRowRender("t", inPreview = true, isChecked = false, vaultRelativePath = "Home/Todo.md")
        assertNotEquals(planA.vaultContext, planB.vaultContext)
    }

    @Test
    fun editorUnchecked_usesPlainTextNotMarkwonPreview() {
        val plan = planTaskRowRender(
            text = "![x](photos/shelf.jpg)",
            inPreview = false,
            isChecked = false,
            vaultRelativePath = "Work/Repairs.md",
        )
        assertNull(plan.markdown)
        assertNull(plan.vaultContext)
    }

    @Test
    fun editorChecked_strikethroughWithVaultContext() {
        val plan = planTaskRowRender(
            text = "Fix shelf ![Damage](photos/shelf.jpg)",
            inPreview = false,
            isChecked = true,
            vaultRelativePath = "Work/Repairs.md",
        )
        assertEquals("~~Fix shelf ![Damage](photos/shelf.jpg)~~", plan.markdown)
        assertEquals("Work/Repairs.md", plan.vaultContext?.noteVaultRelativePath)
    }

    @Test
    fun remoteHttps_stillMarkdownWithoutVaultContext() {
        val plan = planTaskRowRender(
            text = "see ![](https://example.com/logo.png)",
            inPreview = true,
            isChecked = false,
            vaultRelativePath = null,
        )
        assertNotNull(plan.markdown)
        assertNull(plan.vaultContext)
    }
}
