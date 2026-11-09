package org.qosp.notes.data.sync.fs.vault

import android.net.Uri
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

fun resolveVaultStoredPath(treeAccess: VaultTreeAccess, storedPath: String): Uri? {
    return runCatching {
        if (!VaultPaths.isVaultRelativeAttachment(storedPath)) return null
        val relative = VaultPaths.toVaultRelative(storedPath)
        if (relative.isBlank()) return null
        treeAccess.resolveFileUri(relative)
    }.getOrNull()
}

fun resolveMarkwonImage(
    treeAccess: VaultTreeAccess,
    vaultContext: VaultMarkwonContext?,
    destination: String,
): Uri? {
    return runCatching {
        if (destination.contains("://")) return null
        val notePath = vaultContext?.noteVaultRelativePath ?: return null
        if (notePath.isBlank()) return null
        val vaultRelative = VaultLinkResolver.resolve(notePath, destination) ?: return null
        treeAccess.resolveFileUri(vaultRelative)
    }.getOrNull()
}
