package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream

/** SAF-backed [VaultFs] using an existing tree root. */
class SafVaultFs(
    private val context: Context,
    val root: DocumentFile,
    override val scopeId: String = SafVaultWriteStore.vaultScopeFromRoot(root),
    private val caseInsensitiveLookup: Boolean = true,
) : VaultFs {

    override fun canRead(): Boolean = root.canRead()
    override fun canWrite(): Boolean = root.canWrite()

    override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> {
        val dir = resolveDir(relativeDir) ?: return emptyList()
        return (dir.listFiles() ?: return emptyList()).mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val rel = childRelative(relativeDir, name)
            VaultFs.VaultFsEntry(name, rel, child.isDirectory)
        }
    }

    override fun isFile(relativePath: String): Boolean =
        findByRelativePath(relativePath)?.isFile == true

    override fun isDirectory(relativePath: String): Boolean {
        val norm = VaultPaths.normalize(relativePath)
        if (norm.isEmpty()) return root.isDirectory
        return resolveDir(norm) != null
    }

    override fun readText(relativePath: String): String? {
        val file = findByRelativePath(relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun openRead(relativePath: String): InputStream? {
        val file = findByRelativePath(relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    override fun writeText(relativePath: String, content: String, mimeType: String) {
        writeBytes(relativePath, content.encodeToByteArray(), mimeType)
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String) {
        val path = VaultPaths.normalize(relativePath)
        val existing = findByRelativePath(path)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("cannot write $path")
            return
        }
        val parentPath = VaultPaths.parentPath(path)
        val parent = if (parentPath.isEmpty()) root else VaultDocumentTree.ensureDirectory(root, parentPath)
        val fileName = VaultPaths.fileName(path)
        val created = parent.createFile(mimeType, fileName.substringBeforeLast('.'))
            ?: throw IOException("cannot create $fileName")
        if (created.name != fileName) {
            created.renameTo(fileName)
        }
        val target = parent.findFile(fileName) ?: created
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("cannot write new $path")
    }

    override fun ensureDirectory(relativeDir: String) {
        if (relativeDir.isNotEmpty()) VaultDocumentTree.ensureDirectory(root, relativeDir)
    }

    override fun delete(relativePath: String): Boolean {
        val file = findByRelativePath(relativePath)
            ?: resolveDir(relativePath)
            ?: return false
        return file.delete()
    }

    override fun lastModifiedEpochSec(relativePath: String): Long {
        val file = findByRelativePath(relativePath)
            ?: resolveDir(relativePath)
            ?: return 0L
        return file.lastModified() / 1000
    }

    override fun copyFile(fromRelative: String, toRelative: String, deleteSource: Boolean) {
        val from = VaultPaths.normalize(fromRelative)
        val to = VaultPaths.normalize(toRelative)
        val src = findByRelativePath(from)
            ?: throw IOException("missing $from")
        val bytes = context.contentResolver.openInputStream(src.uri)?.use { it.readBytes() }
            ?: throw IOException("unreadable $from")
        writeBytes(to, bytes, VaultFs.mimeForPath(to))
        if (deleteSource) src.delete()
    }

    override fun renameFile(fromRelative: String, toRelative: String) {
        val from = VaultPaths.normalize(fromRelative)
        val to = VaultPaths.normalize(toRelative)
        if (from.equals(to, ignoreCase = true) && from != to) {
            val temp = VaultPaths.normalize("$from.__case_rename__")
            copyFile(from, temp, deleteSource = true)
            copyFile(temp, to, deleteSource = true)
            return
        }
        copyFile(fromRelative, toRelative, deleteSource = true)
    }

    fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { return it }
        if (!caseInsensitiveLookup) return null
        return parent.listFiles()?.firstOrNull { child ->
            child.name?.equals(name, ignoreCase = true) == true
        }
    }

    private fun resolveDir(relativeDir: String): DocumentFile? {
        val norm = VaultPaths.normalize(relativeDir)
        if (norm.isEmpty()) return root
        var current = root
        norm.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val child = findFileCaseInsensitive(current, segment) ?: return null
            if (!child.isDirectory) return null
            current = child
        }
        return current
    }

    private fun findByRelativePath(relativePath: String): DocumentFile? {
        val norm = VaultPaths.normalizeNotePath(relativePath)
        var current: DocumentFile = root
        val parts = norm.split('/')
        parts.forEachIndexed { index, segment ->
            val child = findFileCaseInsensitive(current, segment) ?: return null
            current = child
            if (index < parts.lastIndex && !current.isDirectory) return null
        }
        return current.takeIf { it.isFile }
    }

    private fun childRelative(parentDir: String, childName: String): String {
        val p = VaultPaths.normalize(parentDir)
        return if (p.isEmpty()) childName else "$p/$childName"
    }
}
