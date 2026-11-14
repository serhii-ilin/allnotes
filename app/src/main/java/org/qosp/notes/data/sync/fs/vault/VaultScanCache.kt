package org.qosp.notes.data.sync.fs.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent per-vault scan cache in app-private storage: relativePath -> (lastModifiedMs, size, raw).
 * A note whose listing (mtime, size) is unchanged reuses the cached raw text instead of opening the
 * file. The map is loaded from disk once and kept in memory; a corrupt/missing file yields an empty
 * cache (full read). [save] is atomic (temp file + rename), best-effort, and skipped when nothing changed.
 */
class VaultScanCache(private val cacheFile: File) {

    @Serializable
    data class Entry(
        val lastModifiedMs: Long,
        val size: Long,
        val raw: String,
        /** Wall-clock time (ms) when this entry was written; guards coarse mtimes. */
        val scanTimeMs: Long = 0L,
    )

    @Volatile
    private var entries: Map<String, Entry>? = null

    /** Cached raw for [relativePath] when its listing (mtime, size) is unchanged and trustworthy. */
    fun lookup(relativePath: String, lastModifiedMs: Long, size: Long): String? =
        reusableEntry(relativePath, lastModifiedMs, size)?.raw

    /**
     * The cached entry to reuse for [relativePath], or null when the listing is unknown, changed, or
     * too recent to trust. Entries with unknown metadata (`lastModifiedMs <= 0 || size < 0`) are never
     * reused, and a file whose mtime is still inside the racy window at scan time is re-read.
     */
    fun reusableEntry(relativePath: String, lastModifiedMs: Long, size: Long): Entry? {
        val entry = load()[relativePath] ?: return null
        if (entry.lastModifiedMs <= 0 || entry.size < 0) return null
        if (entry.lastModifiedMs != lastModifiedMs || entry.size != size) return null
        if (entry.lastModifiedMs + RACY_WINDOW_MS >= entry.scanTimeMs) return null
        return entry
    }

    /** Returns the in-memory map, loading it from disk once; empty on any error (missing/corrupt file). */
    fun load(): Map<String, Entry> {
        entries?.let { return it }
        val loaded = runCatching {
            if (!cacheFile.exists()) emptyMap()
            else json.decodeFromString<Map<String, Entry>>(cacheFile.readText())
        }.getOrElse { emptyMap() }
        entries = loaded
        return loaded
    }

    /** Replaces the in-memory map and atomically persists it (best-effort); skips the write when unchanged. */
    fun save(newEntries: Map<String, Entry>) {
        if (entries == newEntries) return
        entries = newEntries
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val body = json.encodeToString(newEntries)
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(body)
                tmp.delete()
            }
        }
    }

    /** Drops every cached entry (in memory and on disk); the next scan re-reads all files. */
    fun clear() {
        entries = emptyMap()
        runCatching { cacheFile.delete() }
    }

    companion object {
        /** Files with an mtime this close to scan time are not trusted (coarse FAT/MTP mtimes). */
        const val RACY_WINDOW_MS = 3_000L

        private val json = Json { ignoreUnknownKeys = true }
    }
}
