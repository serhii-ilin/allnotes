package org.qosp.notes.data.sync.fs.vault.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The app-private outbox: durable before the vault is touched, atomically emptied after a verified flush. */
class HistoryOutboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun outbox(): HistoryOutbox =
        HistoryOutbox(File(tmp.newFolder("app"), HistoryOutbox.FILE_NAME))

    @Test
    fun appendedActions_areReadBackInOrder() {
        val outbox = outbox()
        outbox.append(PendingHistoryEvent(op = HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md", t = 1))
        outbox.append(PendingHistoryEvent(op = HistoryOps.NOTEBOOK_CREATE, name = "Trips", t = 2))

        val pending = outbox.readAll()

        assertEquals(listOf(HistoryOps.NOTE_BIN, HistoryOps.NOTEBOOK_CREATE), pending.map { it.op })
        assertEquals("Plan.md", pending.first().path)
        assertEquals(".trash/Plan.md", pending.first().to)
        assertEquals("Trips", pending.last().name)
        assertEquals(listOf(1L, 2L), pending.map { it.t })
    }

    @Test
    fun clear_emptiesTheFile() {
        val outbox = outbox()
        outbox.append(PendingHistoryEvent(op = HistoryOps.NOTE_BIN, id = "1", t = 1))

        outbox.clear()

        assertTrue(outbox.readAll().isEmpty())
    }

    @Test
    fun aDamagedLine_isDroppedInsteadOfFailingTheFlush() {
        val outbox = outbox()
        outbox.append(PendingHistoryEvent(op = HistoryOps.NOTE_BIN, id = "1", t = 1))
        val file = File(tmp.root, "app/${HistoryOutbox.FILE_NAME}")
        file.appendText("{ truncated\n")

        assertEquals(1, outbox.readAll().size)
    }

    @Test
    fun aMissingFile_readsAsEmpty() {
        assertTrue(outbox().readAll().isEmpty())
    }
}
