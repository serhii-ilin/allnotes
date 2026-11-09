package org.qosp.notes.data.sync.fs.vault

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory vault tree for integration tests (no SAF).
 */
class InMemoryVaultFs(override val scopeId: String = "mem-vault") : VaultFs {

    private data class Node(
        var bytes: ByteArray? = null,
        var isDir: Boolean = false,
        var mtimeSec: Long = System.currentTimeMillis() / 1000,
        var mime: String = "application/octet-stream",
    )

    private val nodes = ConcurrentHashMap<String, Node>()

    init {
        nodes[""] = Node(isDir = true)
    }

    private fun norm(path: String): String = VaultPaths.normalize(path)

    private fun parent(path: String): String = VaultPaths.parentPath(path)

  private fun ensureParents(path: String) {
        var p = parent(path)
        while (true) {
            if (p.isEmpty()) {
                nodes.putIfAbsent("", Node(isDir = true))
                break
            }
            nodes.compute(p) { _, cur -> cur?.copy(isDir = true) ?: Node(isDir = true) }
            p = parent(p)
            if (p.isEmpty()) break
        }
    }

    override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> {
        val dir = norm(relativeDir)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val seen = mutableSetOf<String>()
        val out = mutableListOf<VaultFs.VaultFsEntry>()
        for (key in nodes.keys) {
            if (key.isEmpty()) continue
            if (dir.isNotEmpty() && !key.startsWith(prefix)) continue
            val rest = if (dir.isEmpty()) key else key.removePrefix(prefix)
            if (rest.contains('/')) {
                val name = rest.substringBefore('/')
                val childPath = if (dir.isEmpty()) name else "$dir/$name"
                if (seen.add(childPath)) {
                    val isDir = nodes[childPath]?.isDir == true ||
                        nodes.keys.any { it.startsWith("$childPath/") }
                    out.add(VaultFs.VaultFsEntry(name, childPath, isDir))
                }
            } else if (rest.isNotEmpty()) {
                val node = nodes[key]!!
                if (seen.add(key)) {
                    out.add(VaultFs.VaultFsEntry(rest, key, node.isDir))
                }
            }
        }
        return out
    }

    override fun isFile(relativePath: String): Boolean =
        nodes[norm(relativePath)]?.let { !it.isDir && it.bytes != null } == true

    override fun isDirectory(relativePath: String): Boolean {
        val n = norm(relativePath)
        if (n.isEmpty()) return true
        return nodes[n]?.isDir == true || listChildren(n).isNotEmpty()
    }

    override fun readText(relativePath: String): String? =
        nodes[norm(relativePath)]?.bytes?.toString(Charsets.UTF_8)

    override fun openRead(relativePath: String): InputStream? {
        val b = nodes[norm(relativePath)]?.bytes ?: return null
        return ByteArrayInputStream(b)
    }

    override fun writeText(relativePath: String, content: String, mimeType: String) {
        writeBytes(relativePath, content.encodeToByteArray(), mimeType)
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String) {
        val path = norm(relativePath)
        ensureParents(path)
        nodes[path] = Node(bytes = bytes, mime = mimeType, mtimeSec = System.currentTimeMillis() / 1000)
    }

    override fun ensureDirectory(relativeDir: String) {
        val d = norm(relativeDir)
        if (d.isEmpty()) return
        ensureParents(d)
        nodes[d] = nodes[d]?.copy(isDir = true) ?: Node(isDir = true)
    }

    override fun delete(relativePath: String): Boolean {
        val path = norm(relativePath)
        return nodes.remove(path) != null
    }

    override fun lastModifiedEpochSec(relativePath: String): Long =
        nodes[norm(relativePath)]?.mtimeSec ?: 0L

    override fun copyFile(fromRelative: String, toRelative: String, deleteSource: Boolean) {
        val from = norm(fromRelative)
        val to = norm(toRelative)
        val src = nodes[from] ?: throw IOException("missing $from")
        val bytes = src.bytes
        if (src.isDir || bytes == null) throw IOException("not a file $from")
        writeBytes(to, bytes, src.mime)
        if (deleteSource) delete(from)
    }

    override fun renameFile(fromRelative: String, toRelative: String) {
        copyFile(fromRelative, toRelative, deleteSource = true)
    }

    fun snapshotPaths(): Set<String> = nodes.keys.filter { nodes[it]?.bytes != null }.toSet()
}
