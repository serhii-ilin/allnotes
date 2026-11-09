package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream

/** SAF-backed [VaultFs] using an existing tree root. */
class SafVaultFs(
    private val context: Context,
    private val root: DocumentFile,
    override val scopeId: String = SafVaultWriteStore.vaultScopeFromRoot(root),
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
        VaultDocumentTree.findByRelativePath(root, relativePath)?.isFile == true

    override fun isDirectory(relativePath: String): Boolean {
        val norm = VaultPaths.normalize(relativePath)
        if (norm.isEmpty()) return root.isDirectory
        val file = VaultDocumentTree.findDirectory(root, norm)
        return file != null
    }

    override fun readText(relativePath: String): String? {
        val file = VaultDocumentTree.findByRelativePath(root, relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun openRead(relativePath: String): InputStream? {
        val file = VaultDocumentTree.findByRelativePath(root, relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    override fun writeText(relativePath: String, content: String, mimeType: String) {
        writeBytes(relativePath, content.encodeToByteArray(), mimeType)
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String) {
        val path = VaultPaths.normalize(relativePath)
        val existing = VaultDocumentTree.findByRelativePath(root, path)
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
        val file = VaultDocumentTree.findByRelativePath(root, relativePath)
            ?: VaultDocumentTree.findDirectory(root, relativePath)
            ?: return false
        return file.delete()
    }

    override fun lastModifiedEpochSec(relativePath: String): Long {
        val file = VaultDocumentTree.findByRelativePath(root, relativePath)
            ?: VaultDocumentTree.findDirectory(root, relativePath)
            ?: return 0L
        return file.lastModified() / 1000
    }

    override fun copyFile(fromRelative: String, toRelative: String, deleteSource: Boolean) {
        val from = VaultPaths.normalize(fromRelative)
        val to = VaultPaths.normalize(toRelative)
        val src = VaultDocumentTree.findByRelativePath(root, from)
            ?: throw IOException("missing $from")
        val bytes = context.contentResolver.openInputStream(src.uri)?.use { it.readBytes() }
            ?: throw IOException("unreadable $from")
        writeBytes(to, bytes, VaultFs.mimeForPath(to))
        if (deleteSource) src.delete()
    }

    override fun renameFile(fromRelative: String, toRelative: String) {
        copyFile(fromRelative, toRelative, deleteSource = true)
    }

    private fun resolveDir(relativeDir: String): DocumentFile? {
        val norm = VaultPaths.normalize(relativeDir)
        return if (norm.isEmpty()) root else VaultDocumentTree.findDirectory(root, norm)
    }

    private fun childRelative(parentDir: String, childName: String): String {
        val p = VaultPaths.normalize(parentDir)
        return if (p.isEmpty()) childName else "$p/$childName"
    }
}
