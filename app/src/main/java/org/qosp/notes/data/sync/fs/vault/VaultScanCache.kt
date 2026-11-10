package org.qosp.notes.data.sync.fs.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent per-vault scan cache in app-private storage: relativePath -> (lastModifiedMs, size, raw).
 * A note whose listing (mtime, size) is unchanged reuses the cached raw text instead of opening the
 * file. The map is loaded from disk once and kept in memory; a corrupt/missing file yields an empty
 * cache (full read). [save] is atomic (temp file + rename) and best-effort.
 */
class VaultScanCache(private val cacheFile: File) {

    @Serializable
    data class Entry(val lastModifiedMs: Long, val size: Long, val raw: String)

    @Volatile
    private var entries: Map<String, Entry>? = null

    /** Cached raw for [relativePath] when its listing (mtime, size) is unchanged, else null. */
    fun lookup(relativePath: String, lastModifiedMs: Long, size: Long): String? {
        val entry = load()[relativePath] ?: return null
        return if (entry.lastModifiedMs == lastModifiedMs && entry.size == size) entry.raw else null
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

    /** Replaces the in-memory map and atomically persists it (best-effort). */
    fun save(newEntries: Map<String, Entry>) {
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
