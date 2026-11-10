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
) : VaultFs {

    /** One child document, captured from a single directory query (no per-file provider calls). */
    private data class SnapshotEntry(
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val lastModifiedMs: Long,
        val size: Long,
    )

    // URIs come from buildDocumentUriUsingTree, so fromSingleUri always succeeds.
    private fun SnapshotEntry.document(): DocumentFile = DocumentFile.fromSingleUri(context, uri)!!

    /** Children of one folder, by exact name and by lowercase name (first wins for case-only duplicates). */
    private class DirectoryIndex(
        val byExactName: Map<String, SnapshotEntry>,
        val byLowerName: Map<String, SnapshotEntry>,
    )

    private var directoryCache: Map<String, DirectoryIndex>? = null

    /** Snapshots folder listings so lookups stop querying the provider. Only [StorageBackend.getAll] enables it. */
    fun beginDirectoryCache() {
        directoryCache = buildDirectoryCache(root)
    }

    fun endDirectoryCache() {
        directoryCache = null
    }

    override fun canRead(): Boolean = root.canRead()
    override fun canWrite(): Boolean = root.canWrite()

    override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> {
        // A folder that does not exist yet is empty (e.g. a note's asset folder before its first attachment).
        val dir = resolveDir(relativeDir) ?: return emptyList()
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

    override fun openWrite(relativePath: String, mimeType: String): OutputStream? {
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
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            val output = openWrite(to, VaultFs.mimeForPath(to)) ?: throw IOException("cannot write $to")
            output.use { input.copyTo(it) }
        } ?: throw IOException("unreadable $from")
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

    override fun renameDirectory(fromRelative: String, toRelative: String) {
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
        directoryCache?.get(parent.uri.toString())?.let { index ->
            index.byExactName[name]?.let { return it.document() }
            return if (caseInsensitiveLookup) index.byLowerName[name.lowercase()]?.document() else null
        }
        parent.findFile(name)?.let { return it }
        if (!caseInsensitiveLookup) return null
        return parent.listFiles()?.firstOrNull { child ->
            child.name?.equals(name, ignoreCase = true) == true
        }
    }

    /** Children of [dir] from the active snapshot, or a single directory query when no snapshot is active. */
    private fun listDirectory(dir: DocumentFile): List<SnapshotEntry>? =
        directoryCache?.get(dir.uri.toString())?.let { it.byExactName.values.toList() }
            ?: queryDirectory(dir)

    /** Walks the whole tree once, one [DocumentsContract] query per directory. */
    private fun buildDirectoryCache(start: DocumentFile): Map<String, DirectoryIndex> {
        val out = mutableMapOf<String, DirectoryIndex>()
        fun walk(dir: DocumentFile) {
            val entries = queryDirectory(dir) ?: return
            val byLowerName = mutableMapOf<String, SnapshotEntry>()
            entries.forEach { e -> byLowerName.putIfAbsent(e.name.lowercase(), e) }
            out[dir.uri.toString()] = DirectoryIndex(
                byExactName = entries.associateBy { it.name },
                byLowerName = byLowerName,
            )
            entries
                .filter { it.isDirectory && !VaultHousekeeping.shouldSkipDirectory(it.name) }
                .forEach { walk(it.document()) }
        }
        walk(start)
        return out
    }

    /** Lists [dir] children with a single query; null means a provider failure (not an empty folder). */
    private fun queryDirectory(dir: DocumentFile): List<SnapshotEntry>? {
        val parentId = documentIdOf(dir.uri) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null) ?: return null
        cursor.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val mtimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val out = mutableListOf<SnapshotEntry>()
            while (c.moveToNext()) {
                val docId = if (idCol >= 0) c.getString(idCol) else null ?: continue
                val name = if (nameCol >= 0) c.getString(nameCol) else null ?: continue
                val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                val lastModified = if (mtimeCol >= 0 && !c.isNull(mtimeCol)) c.getLong(mtimeCol) else 0L
                val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else -1L
                out.add(
                    SnapshotEntry(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(root.uri, docId),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        lastModifiedMs = lastModified,
                        size = size,
                    ),
                )
            }
            return out
        }
    }

    private fun documentIdOf(uri: Uri): String? = runCatching {
        if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
        else DocumentsContract.getDocumentId(uri)
    }.getOrNull()

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
