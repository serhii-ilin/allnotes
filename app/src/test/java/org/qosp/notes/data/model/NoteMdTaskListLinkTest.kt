package org.qosp.notes.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMdTaskListLinkTest {

    @Test
    fun checklist_absorbsLinkOnlyContinuationLine() {
        val md = """
            - [ ] Fix shelf
            ![damage](photos/shelf.jpg)
        """.trimIndent()
        val tasks = Note().mdToTaskList(md)
        assertEquals(1, tasks.size)
        assertEquals("Fix shelf", tasks[0].content)
    }

    @Test
    fun checklist_keepsPlainContinuationLine() {
        val md = """
            - [ ] Title
            extra detail
        """.trimIndent()
        val tasks = Note().mdToTaskList(md)
        assertEquals(1, tasks.size)
        assertEquals("Title extra detail", tasks[0].content)
    }
}
