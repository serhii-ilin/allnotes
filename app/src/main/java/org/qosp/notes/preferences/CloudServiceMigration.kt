package org.qosp.notes.preferences

/** Maps legacy sync preferences to folder-vault mode. */
fun CloudService.toVaultMode(): CloudService = when (this) {
    CloudService.FILE_STORAGE -> CloudService.FILE_STORAGE
    CloudService.DISABLED,
    CloudService.NEXTCLOUD,
    -> CloudService.FILE_STORAGE
}
