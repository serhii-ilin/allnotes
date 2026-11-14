package org.qosp.notes.data.repo

import kotlinx.coroutines.test.TestScope
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.data.sync.fs.vault.VaultIo
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Simulated Syncthing between [count] devices.
 *
 * Each device owns a private vault tree and its own history identity, so the only thing that can carry an
 * event from one log to another is [syncthing]. A pair exchange is a two-way reconciliation: a change on
 * either side reaches the other, a deletion propagates, and when both sides changed the same file the newer
 * one wins while the other is kept as a Syncthing conflict copy.
 */
class MultiDeviceVault(
    testScope: TestScope,
    count: Int,
    /**
     * History prefs per device, in device order. A cloned install copies another device's map into its own,
     * as restoring a phone from a backup would.
     */
    historyStores: List<MutableMap<String, String>> = List(count) { mutableMapOf() },
    /** Release B appliers for each device; the harness is passed so they can use its DAOs. */
    appliersFor: (NoteRepositoryVaultHarness) -> List<HistoryApplier> = { emptyList() },
) {
    /** The last harness to close would otherwise restore this to a previous harness's dispatcher. */
    private val savedVaultDispatcher = VaultIo.vaultDispatcher

    val devices: List<NoteRepositoryVaultHarness> = List(count) { index ->
        // The coordinator keeps this exact list, so adding after construction still wires the appliers.
        val wired = mutableListOf<HistoryApplier>()
        NoteRepositoryVaultHarness(
            testScope,
            historyStore = historyStores[index],
            historyAppliers = wired,
        ).also { harness -> wired += appliersFor(harness) }
    }

    private val deviceKeys = mutableMapOf<Int, String>()

    /** Last state both sides of a pair agreed on, per path; null means "absent". */
    private val bases = mutableMapOf<Pair<Int, Int>, MutableMap<String, ByteArray?>>()

    operator fun get(index: Int): NoteRepositoryVaultHarness = devices[index]

    suspend fun deviceId(index: Int): String =
        deviceKeys[index] ?: devices[index].deviceId().also { deviceKeys[index] = it }

    suspend fun syncthing(from: Int, to: Int) {
        require(from != to) { "a device cannot sync with itself" }
        val fromFs = devices[from].vaultFs
        val toFs = devices[to].vaultFs
        val base = bases.getOrPut(orderedPair(from, to)) { mutableMapOf() }
        val paths = (fromFs.snapshotPaths() + toFs.snapshotPaths() + base.keys).sorted()

        for (path in paths) {
            val fromBytes = readBytes(fromFs, path)
            val toBytes = readBytes(toFs, path)
            val baseBytes = base[path]
            val fromChanged = !same(fromBytes, baseBytes)
            val toChanged = !same(toBytes, baseBytes)

            when {
                same(fromBytes, toBytes) -> base[path] = fromBytes

                fromChanged && toChanged -> {
                    if (fromBytes != null && toBytes != null) {
                        val fromWins = fromIsNewer(fromFs, path, fromBytes, toFs, path, toBytes)
                        val winner = if (fromWins) fromBytes else toBytes
                        val loser = if (fromWins) toBytes else fromBytes
                        val loserIndex = if (fromWins) to else from
                        write(fromFs, path, winner)
                        write(toFs, path, winner)
                        val conflict = unusedConflictPath(fromFs, toFs, path, loserIndex)
                        write(fromFs, conflict, loser)
                        write(toFs, conflict, loser)
                        base[path] = winner
                        base[conflict] = loser
                    } else {
                        // A modification beats a deletion; there is no bytes to keep as a conflict copy.
                        val winner = fromBytes ?: toBytes
                        write(fromFs, path, winner)
                        write(toFs, path, winner)
                        base[path] = winner
                    }
                }

                fromChanged -> {
                    write(toFs, path, fromBytes)
                    base[path] = fromBytes
                }

                toChanged -> {
                    write(fromFs, path, toBytes)
                    base[path] = toBytes
                }

                else -> base[path] = toBytes
            }
        }
    }

    /** Deletes every file under `.allnotes/` on one device, as a user clearing the history folder would. */
    fun deleteHistoryFolder(index: Int) {
        val fs = devices[index].vaultFs
        fs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { fs.delete(it) }
    }

    fun close() {
        devices.forEach {
            it.db.close()
            it.close()
        }
        VaultIo.vaultDispatcher = savedVaultDispatcher
    }

    private fun orderedPair(a: Int, b: Int): Pair<Int, Int> = if (a < b) a to b else b to a

    private fun readBytes(fs: InMemoryVaultFs, path: String): ByteArray? =
        fs.openRead(path)?.use { it.readBytes() }

    private fun same(a: ByteArray?, b: ByteArray?): Boolean =
        if (a == null || b == null) a == null && b == null else a.contentEquals(b)

    private fun write(fs: InMemoryVaultFs, path: String, bytes: ByteArray?) {
        if (bytes == null) fs.delete(path) else fs.writeBytes(path, bytes, VaultFs.mimeForPath(path))
    }

    private fun fromIsNewer(
        fromFs: InMemoryVaultFs,
        fromPath: String,
        fromBytes: ByteArray,
        toFs: InMemoryVaultFs,
        toPath: String,
        toBytes: ByteArray,
    ): Boolean {
        val fromTime = fromFs.lastModifiedEpochSec(fromPath)
        val toTime = toFs.lastModifiedEpochSec(toPath)
        if (fromTime != toTime) return fromTime > toTime
        // Same second: content order keeps the merge deterministic on every delivery order.
        return fromBytes.toHexString() >= toBytes.toHexString()
    }

    private fun unusedConflictPath(
        fromFs: InMemoryVaultFs,
        toFs: InMemoryVaultFs,
        path: String,
        loserIndex: Int,
    ): String {
        val loserFs = devices[loserIndex].vaultFs
        val stamp = Instant.ofEpochSecond(loserFs.lastModifiedEpochSec(path))
            .atZone(ZoneOffset.UTC)
            .format(CONFLICT_STAMP)
        val device = deviceKeys[loserIndex]?.take(6)?.uppercase(Locale.ROOT) ?: "DEVICE"
        var candidate = conflictName(path, "$stamp-$device")
        var counter = 2
        while (readBytes(fromFs, candidate) != null || readBytes(toFs, candidate) != null) {
            candidate = conflictName(path, "$stamp-$device-$counter")
            counter++
        }
        return candidate
    }

    private fun conflictName(path: String, suffix: String): String {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return "$dir$base.sync-conflict-$suffix$extension"
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val CONFLICT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
