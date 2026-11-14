package org.qosp.notes.data.sync.fs.vault.history

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.preferences.PreferenceRepository
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-memory [VaultFileAccess] over [InMemoryVaultFs]; [writes] records the paths a device actually touched. */
class TestVaultFileAccess(val fs: InMemoryVaultFs = InMemoryVaultFs()) : VaultFileAccess {

    override val historyScope: String get() = fs.scopeId

    val writes = mutableListOf<String>()
    val reads = mutableListOf<String>()

    private val readCounts = mutableMapOf<String, Int>()

    /** The nth read of this path returns null once, as a flaky provider read would; null disables it. */
    var nullReadAt: Pair<String, Int>? = null

    /** The next write throws, as a provider hiccup would. */
    var failNextWrite = false

    /** Every write throws, as an unavailable folder would. */
    var failAllWrites = false

    /** Writes "succeed" but never reach the tree, as a lost publish would. */
    var dropWrites = false

    /** The next write to this path throws once, as a README/manifest write failing after the events landed. */
    var failNextOnPath: String? = null

    override suspend fun readVaultFile(relativePath: String): String? {
        reads.add(relativePath)
        val count = (readCounts[relativePath] ?: 0) + 1
        readCounts[relativePath] = count
        val nullAt = nullReadAt
        if (nullAt != null && nullAt.first == relativePath && nullAt.second == count) return null
        return fs.readText(relativePath)
    }

    override suspend fun listVaultDir(relativePath: String): List<VaultFs.VaultFsEntry> =
        fs.listChildren(relativePath)

    override suspend fun writeVaultFile(relativePath: String, text: String) {
        if (relativePath == failNextOnPath) {
            failNextOnPath = null
            throw IOException("simulated vault write failure")
        }
        if (failAllWrites || failNextWrite) {
            failNextWrite = false
            throw IOException("simulated vault write failure")
        }
        writes.add(relativePath)
        if (!dropWrites) fs.writeText(relativePath, text)
    }

    val deletes = mutableListOf<String>()

    /** Paths whose delete answers false, as an inaccessible provider file would; removing one makes the retry work. */
    val failedDeletes = mutableSetOf<String>()

    override suspend fun deleteVaultFile(relativePath: String): Boolean {
        if (relativePath in failedDeletes) return false
        deletes.add(relativePath)
        return fs.delete(relativePath)
    }

    fun text(path: String): String? = fs.readText(path)

    fun readCount(path: String): Int = readCounts[path] ?: 0

    fun pathsUnder(prefix: String): Set<String> = fs.snapshotPaths().filter { it.startsWith(prefix) }.toSet()

    fun writeCount(path: String): Int = writes.count { it == path }
}

/**
 * The two encrypted-preference calls [DeviceIdentity] uses, backed by a map: real Android preference storage
 * would need Robolectric, and the engine only cares about what was stored.
 */
class TestHistoryPrefs(initial: Map<String, String> = emptyMap()) {

    val values = ConcurrentHashMap<String, String>(initial)
    val repository: PreferenceRepository = mockk(relaxed = true)

    init {
        coEvery { repository.getEncryptedString(any()) } answers {
            flowOf(values[firstArg<String>()] ?: "")
        }
        coEvery { repository.putEncryptedStrings(*anyVararg()) } answers {
            record(args)
        }
    }

    /** Values as another install would read them (a clone shares this map). */
    fun snapshot(): Map<String, String> = values.toMap()

    private fun record(args: List<Any?>) {
        for (arg in args) {
            when (arg) {
                is Pair<*, *> -> put(arg)
                is Array<*> -> arg.forEach { if (it is Pair<*, *>) put(it) }
            }
        }
    }

    private fun put(pair: Pair<*, *>) {
        val key = pair.first as? String ?: return
        values[key] = pair.second as? String ?: ""
    }
}

/** A folder backend as [FolderHistoryService] sees it: an [ISyncBackend] that is also a [VaultFileAccess]. */
class TestFolderBackend(access: VaultFileAccess) :
    ISyncBackend by mockk<ISyncBackend>(relaxed = true), VaultFileAccess by access

/** One simulated install: its own encrypted prefs, outbox and clock, sharing the vault tree with the others. */
class TestHistoryDevice(
    val access: TestVaultFileAccess,
    val prefs: TestHistoryPrefs,
    private val outboxDir: File,
) {
    val identity = DeviceIdentity(prefs.repository, model = "Pixel 9", defaultScope = access.historyScope)
    val outbox = HistoryOutbox(File(outboxDir, HistoryOutbox.FILE_NAME))

    /** Wall clock for `t` and `lastSeenDay`; tests move it to prove skew and the daily manifest rule. */
    var now: Long = at("2026-11-10T12:00:00Z")

    val writer: HistoryWriter get() = writer("1.5.14")

    fun writer(appVersion: String): HistoryWriter =
        HistoryWriter(access, identity, outbox, appVersion, now = { now })

    suspend fun id(): String = identity.deviceId(access.historyScope)

    suspend fun record(
        op: String,
        id: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
    ) {
        writer.record(op = op, id = id, path = path, to = to, name = name)
    }

    suspend fun flush(appVersion: String = "1.5.14"): FlushResult = writer(appVersion).flush()
}

/** ISO instant to epoch millis, so tests read as clock times rather than numbers. */
fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

fun eventsPath(deviceId: String, month: String = "2026-11"): String =
    "${HistoryPaths.deviceDir(deviceId)}/events-$month.jsonl"

/** Event lines of one log file, in file order; blank lines ignored. */
fun TestVaultFileAccess.events(path: String): List<HistoryEvent> =
    text(path).orEmpty().lines().filter { it.isNotBlank() }.map {
        HistoryJson.json.decodeFromString<HistoryEvent>(it)
    }

fun TestVaultFileAccess.manifest(deviceId: String): DeviceManifest? =
    text(HistoryPaths.manifestPath(deviceId))?.let {
        HistoryJson.json.decodeFromString<DeviceManifest>(it)
    }
