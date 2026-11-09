package org.qosp.notes.ui.utils.coil

import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.fs.vault.resolveMarkwonImage
import org.qosp.notes.data.sync.fs.vault.resolveVaultStoredPath
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

/**
 * Resolves Markwon/Coil image destinations without passing unresolved relative paths to Coil.
 */
internal object VaultMarkwonImageLoader {

    fun resolveLoadData(
        destination: String?,
        vaultContext: VaultMarkwonContext?,
        vaultTreeAccess: VaultTreeAccess,
    ): Any? = when {
        destination.isNullOrBlank() -> null
        VaultPaths.isVaultRelativeAttachment(destination) ->
            resolveVaultStoredPath(vaultTreeAccess, destination)
        destination.contains("://") -> destination
        else -> resolveMarkwonImage(vaultTreeAccess, vaultContext, destination)
    }
}
