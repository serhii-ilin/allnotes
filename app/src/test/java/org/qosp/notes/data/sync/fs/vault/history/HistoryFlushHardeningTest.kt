package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs

/** A slow or failed flush never loses an action and never turns a cancellation into a retry. */
class HistoryFlushHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun identity(fs: InMemoryVaultFs) =
        DeviceIdentity(TestHistoryPrefs().repository, model = "Pixel 9", defaultScope = fs.scopeId)

    private fun outbox() = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))

    /** HST-05: an action recorded while the flush is writing is not part of what that flush consumed. */
    @Test
    fun anActionRecordedDuringAFlush_survivesAndLandsOnTheNextFlush() = runTest {
        val fs = InMemoryVaultFs()
        val base = TestVaultFileAccess(fs)
        val outbox = outbox()
        val identity = identity(fs)
        var injected = false
        val access = object : VaultFileAccess by base {
            override suspend fun writeVaultFile(relativePath: String, text: String) {
                base.writeVaultFile(relativePath, text)
                if (!injected && relativePath.startsWith(HistoryPaths.DEVICES)) {
                    injected = true
                    // The user bins another note while this flush is inside the slow vault write.
                    outbox.append(
                        PendingHistoryEvent(
                            op = HistoryOps.TAG_CREATE,
                            name = "mid-flush",
                            t = at("2026-11-10T12:00:01Z"),
                            scope = fs.scopeId,
                        ),
                    )
                }
            }
        }
        val path = eventsPath(identity.deviceId())
        val writer = HistoryWriter(access, identity, outbox, "1.5.14", now = { at("2026-11-10T12:00:00Z") })

        writer.record(HistoryOps.TAG_CREATE, name = "first")
        assertTrue(writer.flush() is FlushResult.Flushed)

        assertEquals(listOf("mid-flush"), outbox.readAll().map { it.name })
        assertEquals(listOf("first"), base.events(path).map { it.name })

        assertTrue(writer.flush() is FlushResult.Flushed)
        assertEquals(listOf("first", "mid-flush"), base.events(path).map { it.name })
        assertEquals(listOf(1L, 2L), base.events(path).map { it.seq })
        assertTrue(outbox.readAll().isEmpty())
    }

    /** HST-01/HST-05: a null read only means empty when the listing agrees; otherwise the flush retries. */
    @Test
    fun aNullReadForAListedLogFile_failsTheFlushAndKeepsTheOutbox() = runTest {
        val fs = InMemoryVaultFs()
        val access = TestVaultFileAccess(fs)
        val outbox = outbox()
        val identity = identity(fs)
        val path = eventsPath(identity.deviceId())
        val writer = HistoryWriter(access, identity, outbox, "1.5.14", now = { at("2026-11-10T12:00:00Z") })

        writer.record(HistoryOps.TAG_CREATE, name = "first")
        writer.flush()
        val before = access.text(path).orEmpty()
        assertEquals(1, access.events(path).size)

        writer.record(HistoryOps.TAG_RENAME, name = "first", to = "second")
        // The clone guard reads the log once; the append then gets a flaky null on its own read.
        access.reads.clear()
        access.nullReadAt = path to access.readCount(path) + 2

        val result = writer.flush()
        assertTrue(result is FlushResult.Failed)

        assertEquals(before, access.text(path))
        assertEquals(listOf(HistoryOps.TAG_RENAME), outbox.readAll().map { it.op })

        assertTrue(writer.flush() is FlushResult.Flushed)
        assertEquals(
            listOf(HistoryOps.TAG_CREATE, HistoryOps.TAG_RENAME),
            access.events(path).map { it.op },
        )
        assertEquals(listOf("first", "second"), access.events(path).map { it.to ?: it.name })
        assertEquals(listOf(1L, 2L), access.events(path).map { it.seq })
        assertTrue(outbox.readAll().isEmpty())
    }

    /** A cancelled sync must cancel, not look like a failed flush that the next sync retries. */
    @Test
    fun flush_rethrowsCancellation() = runTest {
        val fs = InMemoryVaultFs()
        val base = TestVaultFileAccess(fs)
        val outbox = outbox()
        val identity = identity(fs)
        val access = object : VaultFileAccess by base {
            override suspend fun writeVaultFile(relativePath: String, text: String) {
                if (relativePath.startsWith(HistoryPaths.DEVICES)) throw CancellationException("cancelled")
                base.writeVaultFile(relativePath, text)
            }
        }
        val writer = HistoryWriter(access, identity, outbox, "1.5.14", now = { at("2026-11-10T12:00:00Z") })
        writer.record(HistoryOps.TAG_CREATE, name = "work")

        try {
            writer.flush()
            fail("expected the cancellation to propagate")
        } catch (expected: CancellationException) {
            assertEquals(listOf("work"), outbox.readAll().map { it.name })
        }
    }
}
