package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Resolves vault-relative paths to SAF [Uri]s. Intended for background threads (Coil, sync I/O).
 */
class VaultTreeAccess(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
) {
  @Volatile
  private var treeUri: Uri? = null

  @Volatile
  private var root: DocumentFile? = null

  fun resolveFileUri(vaultRelative: String): Uri? {
    val rel = VaultPaths.normalize(vaultRelative)
    val file = rootDocument()?.let { VaultDocumentTree.findByRelativePath(it, rel) } ?: return null
    return file.uri
  }

  fun rootDocument(): DocumentFile? {
    val currentUri = treeUri ?: loadTreeUri()
    if (currentUri == null) return null
    val cached = root
    if (cached != null && cached.uri == currentUri) return cached
    return DocumentFile.fromTreeUri(context, currentUri)?.also {
      treeUri = currentUri
      root = it
    }
  }

  fun invalidate() {
    treeUri = null
    root = null
  }

  suspend fun warmFrom(preferenceRepository: PreferenceRepository) {
    val s = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
    if (s.isBlank()) {
      invalidate()
      return
    }
    warmFrom(Uri.parse(s))
  }

  fun warmFrom(uri: Uri) {
    treeUri = uri
    root = DocumentFile.fromTreeUri(context, uri)
  }

  private fun loadTreeUri(): Uri? = treeUri
}
