package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SAF-backed [VaultFs] using an existing tree root. */
class SafVaultFs(
    private val context: Context,
    val root: DocumentFile,
    override val scopeId: String = SafVaultWriteStore.vaultScopeFromRoot(root),
    private val caseInsensitiveLookup: Boolean = true,
    /** Invoked before any mutation (write/delete/rename/ensure-directory) so callers can track self-writes. */
    private val onWrite: () -> Unit = {},
) : VaultFs {

    /** Children of one folder: the raw list plus first-wins maps by exact and lowercase name. */
    private class DirectoryIndex(
        val entries: List<SafDirectoryListing.Entry>,
        val byExactName: Map<String, SafDirectoryListing.Entry>,
        val byLowerName: Map<String, SafDirectoryListing.Entry>,
    )

    private var directoryCache: Map<String, DirectoryIndex>? = null

    /** Snapshots folder listings so lookups stop querying the provider. Only [StorageBackend.getAll] enables it. */
    fun beginDirectoryCache() {
        directoryCache = buildDirectoryCache(rootDocumentId())
    }

    fun endDirectoryCache() {
        directoryCache = null
    }

    override fun canRead(): Boolean = root.canRead()
    override fun canWrite(): Boolean = root.canWrite()

    // Snapshot-backed lookups (scan path): resolve to an entry URI without building a DocumentFile,
    // so a SingleDocumentFile is never asked to list/find children.

    override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> {
        // A folder that does not exist yet is empty (e.g. a note's asset folder before its first attachment).
        val dir = findDirEntry(relativeDir) ?: return emptyList()
        // A null listing of an existing folder is a provider failure; it must not look like an empty folder.
        val children = listDirectory(dir) ?: throw IOException("Cannot list vault directory: $relativeDir")
        return children.map { entry ->
            VaultFs.VaultFsEntry(
                name = entry.name,
                relativePath = childRelative(relativeDir, entry.name),
                isDirectory = entry.isDirectory,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
            )
        }
    }

    override fun isFile(relativePath: String): Boolean = findEntry(relativePath) != null

    override fun isDirectory(relativePath: String): Boolean {
        val norm = VaultPaths.normalize(relativePath)
        if (norm.isEmpty()) return root.isDirectory
        return findDirEntry(norm) != null
    }

    override fun readText(relativePath: String): String? {
        val entry = findEntry(relativePath) ?: return null
        return context.contentResolver.openInputStream(entry.documentUri)?.use {
            it.bufferedReader().readText()
        }
    }

    override fun openRead(relativePath: String): InputStream? {
        val entry = findEntry(relativePath) ?: return null
        return context.contentResolver.openInputStream(entry.documentUri)
    }

    // DocumentFile-tree lookups (write path): these use the full DocumentFile API (createFile,
    // renameTo, delete), so they must stay backed by the tree, never a single-document snapshot entry.

    override fun writeText(relativePath: String, content: String, mimeType: String) {
        writeBytes(relativePath, content.encodeToByteArray(), mimeType)
    }

    override fun openWrite(relativePath: String, mimeType: String): OutputStream? {
        onWrite()
        val path = VaultPaths.normalize(relativePath)
        val existing = findByRelativePath(path)
        if (existing != null && existing.isFile) {
            return context.contentResolver.openOutputStream(existing.uri, "wt")
        }
        val parentPath = VaultPaths.parentPath(path)
        val parent = if (parentPath.isEmpty()) root else VaultDocumentTree.ensureDirectory(root, parentPath)
        val fileName = VaultPaths.fileName(path)
        val created = parent.createFile(mimeType, fileName.substringBeforeLast('.'))
            ?: throw IOException("cannot create $fileName")
        if (created.name != fileName) created.renameTo(fileName)
        val target = findFileCaseInsensitive(parent, fileName) ?: created
        return context.contentResolver.openOutputStream(target.uri, "wt")
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String) {
        onWrite()
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
        onWrite()
        if (relativeDir.isNotEmpty()) VaultDocumentTree.ensureDirectory(root, relativeDir)
    }

    override fun delete(relativePath: String): Boolean {
        onWrite()
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
        onWrite()
        val from = VaultPaths.normalize(fromRelative)
        val to = VaultPaths.normalize(toRelative)
        val src = findByRelativePath(from)
            ?: throw IOException("missing $from")
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            val output = openWrite(to, VaultFs.mimeForPath(to)) ?: throw IOException("cannot write $to")
            output.use { input.copyTo(it) }
        } ?: throw IOException("unreadable $from")
        if (deleteSource) src.delete()
    }

    override fun renameFile(fromRelative: String, toRelative: String) {
        onWrite()
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

    override fun renameDirectory(fromRelative: String, toRelative: String) {
        onWrite()
        val from = VaultPaths.normalize(fromRelative)
        val to = VaultPaths.normalize(toRelative)
        if (from == to) return
        if (!VaultPaths.parentPath(from).equals(VaultPaths.parentPath(to), ignoreCase = true)) {
            throw IOException("Cannot move folder $from to $to")
        }
        val source = resolveDir(from) ?: return
        val fromName = VaultPaths.fileName(from)
        val toName = VaultPaths.fileName(to)
        // Case-insensitive providers report the target name as existing, so go through a temporary name.
        if (!source.renameTo("$fromName.__case_rename__")) throw IOException("Cannot rename folder $from")
        if (!source.renameTo(toName)) {
            source.renameTo(fromName)
            throw IOException("Cannot rename folder $from to $toName")
        }
    }

    fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { return it }
        if (!caseInsensitiveLookup) return null
        return parent.listFiles()?.firstOrNull { child ->
            child.name?.equals(name, ignoreCase = true) == true
        }
    }

    /** Children of [dir] from the active snapshot (raw list), or a single directory query when no snapshot is active. */
    private fun listDirectory(dir: SafDirectoryListing.Entry): List<SafDirectoryListing.Entry>? =
        directoryCache?.get(dir.docId)?.let { it.entries }
            ?: queryDirectory(dir.docId)

    /** Walks the whole tree once, one [DocumentsContract] query per directory. */
    private fun buildDirectoryCache(startDocId: String): Map<String, DirectoryIndex> {
        val out = mutableMapOf<String, DirectoryIndex>()
        fun walk(docId: String) {
            val entries = queryDirectory(docId) ?: return
            val byExactName = mutableMapOf<String, SafDirectoryListing.Entry>()
            val byLowerName = mutableMapOf<String, SafDirectoryListing.Entry>()
            // First wins for both maps, so listing and lookup agree on duplicate names.
            entries.forEach { e ->
                byExactName.putIfAbsent(e.name, e)
                byLowerName.putIfAbsent(e.name.lowercase(), e)
            }
            out[docId] = DirectoryIndex(entries, byExactName, byLowerName)
            entries
                .filter { it.isDirectory && !VaultHousekeeping.shouldSkipDirectory(it.name) }
                .forEach { walk(it.docId) }
        }
        walk(startDocId)
        return out
    }

    /** Lists children of [parentDocId] with a single query; null means a provider failure (not an empty folder). */
    private fun queryDirectory(parentDocId: String): List<SafDirectoryListing.Entry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null) ?: return null
        cursor.use { return SafDirectoryListing.fromCursor(it, root.uri) }
    }

    /** Document id of the tree root; `root.uri` is already a document-in-tree URI (`tree/X/document/X`). */
    private fun rootDocumentId(): String = DocumentsContract.getTreeDocumentId(root.uri)

    private fun findEntry(relativePath: String): SafDirectoryListing.Entry? {
        val norm = VaultPaths.normalizeNotePath(relativePath)
        var current: SafDirectoryListing.Entry? = null
        var parentDocId = rootDocumentId()
        val parts = norm.split('/')
        for ((index, segment) in parts.withIndex()) {
            val child = lookupEntry(parentDocId, segment) ?: return null
            if (index < parts.lastIndex) {
                if (!child.isDirectory) return null
            } else if (child.isDirectory) return null
            current = child
            parentDocId = child.docId
        }
        return current
    }

    private fun findDirEntry(relativeDir: String): SafDirectoryListing.Entry? {
        val norm = VaultPaths.normalize(relativeDir)
        if (norm.isEmpty()) {
            return SafDirectoryListing.Entry(rootDocumentId(), "", root.uri, isDirectory = true, 0L, -1L)
        }
        var current: SafDirectoryListing.Entry? = null
        var parentDocId = rootDocumentId()
        for (segment in norm.split('/').filter { it.isNotEmpty() }) {
            val child = lookupEntry(parentDocId, segment) ?: return null
            if (!child.isDirectory) return null
            current = child
            parentDocId = child.docId
        }
        return current
    }

    private fun lookupEntry(parentDocId: String, name: String): SafDirectoryListing.Entry? {
        directoryCache?.get(parentDocId)?.let { index ->
            index.byExactName[name]?.let { return it }
            return if (caseInsensitiveLookup) index.byLowerName[name.lowercase()] else null
        }
        val entries = queryDirectory(parentDocId) ?: return null
        entries.firstOrNull { it.name == name }?.let { return it }
        return if (caseInsensitiveLookup) entries.firstOrNull { it.name.equals(name, ignoreCase = true) } else null
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
