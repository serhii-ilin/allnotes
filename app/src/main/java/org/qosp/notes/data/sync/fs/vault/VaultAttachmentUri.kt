package org.qosp.notes.data.sync.fs.vault

import android.net.Uri
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

fun resolveVaultStoredPath(treeAccess: VaultTreeAccess, storedPath: String): Uri? {
    if (!VaultPaths.isVaultRelativeAttachment(storedPath)) return null
    val relative = VaultPaths.toVaultRelative(storedPath)
    return treeAccess.resolveFileUri(relative)
}

fun resolveMarkwonImage(
    treeAccess: VaultTreeAccess,
    vaultContext: VaultMarkwonContext?,
    destination: String,
): Uri? {
    if (destination.contains("://")) return null
    val notePath = vaultContext?.noteVaultRelativePath ?: return null
    val vaultRelative = VaultLinkResolver.resolve(notePath, destination) ?: return null
    return treeAccess.resolveFileUri(vaultRelative)
}
