package org.qosp.notes.data.sync.fs

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.qosp.notes.R
import org.qosp.notes.preferences.PreferenceRepository

data class StorageConfig(val location: Uri) {

    companion object {
        fun storageLocation(prefRepo: PreferenceRepository): Flow<StorageConfig?> =
            prefRepo.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).map {
                runCatching { it.toUri() }.getOrNull()?.let { l -> StorageConfig(l) }
            }
    }
}

fun Uri.toFriendlyString(context: Context): String {
    // Get the provider name (app name)
    val packageManager = context.packageManager
    val providerName = packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS)
        ?.firstOrNull { it?.providers?.any { p -> p?.authority == this.authority } ?: false }
        ?.applicationInfo
        ?.let { packageManager.getApplicationLabel(it) }?.toString() ?: this.authority ?: "Unknown"

    // Try to get the directory name using DocumentFile
    val documentFile = DocumentFile.fromTreeUri(context, this)
    val directoryName = documentFile?.name ?: this.lastPathSegment?.substringAfterLast('/') ?: ""

    // Combine provider name and directory name
    return if (directoryName.isNotEmpty()) {
        "$providerName: $directoryName"
    } else {
        providerName
    }
}

/**
 * Whether two picked URIs are the same SAF tree. The URI string can differ for one tree (encoding of the document
 * id), so compare the authority and the decoded tree document id. Unparseable URIs only match when equal.
 */
fun isSameVaultTree(a: Uri, b: Uri): Boolean {
    if (a == b) return true
    val idA = vaultTreeIdentity(a) ?: return false
    return idA == vaultTreeIdentity(b)
}

/** Stable identity of a SAF tree (authority plus decoded tree document id), or null when [uri] isn't parseable. */
fun vaultTreeIdentity(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return "${uri.authority}|$id"
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Whether to warn that [tree] is provided by another app (a cloud drive, say). Once per tree: [warnedTree] is the
 * tree the user was already told about (blank when none), and device storage never warns.
 */
fun shouldWarnAboutCloudFolder(tree: Uri, warnedTree: String): Boolean {
    if (tree.authority == EXTERNAL_STORAGE_AUTHORITY) return false
    return warnedTree.isBlank() || !isSameVaultTree(tree, Uri.parse(warnedTree))
}

/** Human-readable path for a SAF tree document id, or null when the provider layout isn't understood. */
fun formatVaultTreePath(authority: String?, treeDocumentId: String?, internalStorageLabel: String): String? {
    if (treeDocumentId == null) return null
    if (treeDocumentId.startsWith("raw:")) return treeDocumentId.removePrefix("raw:")
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return null

    val volume = treeDocumentId.substringBefore(':')
    val path = treeDocumentId.substringAfter(':', "").trim('/')
    val root = if (volume == "primary") internalStorageLabel else volume
    return if (path.isEmpty()) root else "$root/$path"
}

fun Uri.toDisplayPath(context: Context): String {
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
    return formatVaultTreePath(authority, treeId, context.getString(R.string.storage_internal_label))
        ?: toFriendlyString(context)
}

/** Folder name for a device-storage tree document id (`primary:Documents/Notes` -> `Notes`); the volume root maps to its label. */
fun formatExternalStorageFolderName(treeDocumentId: String, internalStorageLabel: String): String {
    val volume = treeDocumentId.substringBefore(':')
    val folder = treeDocumentId.substringAfter(':', "").trim('/').substringAfterLast('/')
    return folder.ifEmpty { if (volume == "primary") internalStorageLabel else volume }
}

/** Short name for the nav drawer: just the folder for device storage, provider and folder for other providers. */
fun Uri.toVaultFolderName(context: Context): String {
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return toFriendlyString(context)
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
        ?: return toFriendlyString(context)
    val label = context.getString(R.string.storage_internal_label)
    val isRoot = treeId.substringAfter(':', "").trim('/').isEmpty()
    if (isRoot) return formatExternalStorageFolderName(treeId, label)
    return DocumentFile.fromTreeUri(context, this)?.name?.takeIf { it.isNotEmpty() }
        ?: formatExternalStorageFolderName(treeId, label)
}
