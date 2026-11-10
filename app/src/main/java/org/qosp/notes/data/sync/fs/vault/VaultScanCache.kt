package org.qosp.notes.data.sync.fs.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent per-vault scan cache in app-private storage: relativePath -> (lastModifiedMs, size, raw).
 * A note whose listing (mtime, size) is unchanged reuses the cached raw text instead of opening the
 * file. Any read/corruption error yields an empty cache (full read), and writes are atomic (temp + rename).
 */
class VaultScanCache(private val cacheFile: File) {

    @Serializable
    data class Entry(val lastModifiedMs: Long, val size: Long, val raw: String)

    /** Cached raw for [relativePath] when its listing (mtime, size) is unchanged, else null. */
    fun lookup(relativePath: String, lastModifiedMs: Long, size: Long): String? {
        val entry = load()[relativePath] ?: return null
        return if (entry.lastModifiedMs == lastModifiedMs && entry.size == size) entry.raw else null
    }

    /** Reads the cache; returns empty on any error (missing/corrupt file). */
    fun load(): Map<String, Entry> {
        if (!cacheFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Entry>>(cacheFile.readText())
        }.getOrElse { emptyMap() }
    }

    /** Atomically replaces the cache contents (temp file + rename). Best-effort: a write failure is ignored. */
    fun save(entries: Map<String, Entry>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val body = json.encodeToString(entries)
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(body)
                tmp.delete()
            }
        }
    }

    /** Drops one path's entry (no-op when the cache is absent, unreadable, or lacks the path). */
    fun remove(relativePath: String) {
        val entries = load()
        if (entries.isEmpty() || relativePath !in entries) return
        save(entries - relativePath)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
