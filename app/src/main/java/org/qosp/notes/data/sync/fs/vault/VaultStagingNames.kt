package org.qosp.notes.data.sync.fs.vault

import java.io.IOException

/** SAF staging/backup document names after [DocumentFile.createFile]. */
object VaultStagingNames {
    /**
     * @param createdName provider-assigned name (must be non-null)
     * @param findAfterRename result of [androidx.documentfile.provider.DocumentFile.findFile] after rename, if any
     */
    fun resolveAfterCreate(createdName: String, desired: String, renameSucceeded: Boolean, findAfterRename: String?): String {
        if (createdName == desired) return createdName
        if (!renameSucceeded) return createdName
        return findAfterRename ?: createdName
    }

    fun requireCreatedName(createdName: String?): String =
        createdName ?: throw IOException("createFile returned null name")
}
