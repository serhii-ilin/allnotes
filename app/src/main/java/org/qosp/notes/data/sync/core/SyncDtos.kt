package org.qosp.notes.data.sync.core

import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultFileOpFence

// Sealed class to represent remote operations
sealed class RemoteOperation {
    abstract val vaultFence: VaultFileOpFence?

    data class Create(
        val note: Note,
        val import: Boolean = false,
        override val vaultFence: VaultFileOpFence? = null,
    ) : RemoteOperation()

    data class Update(
        val note: Note,
        override val vaultFence: VaultFileOpFence? = null,
    ) : RemoteOperation()

    data class Delete(
        val note: Note,
        val removeAssets: Boolean = true,
        override val vaultFence: VaultFileOpFence? = null,
    ) : RemoteOperation()
}

enum class SyncMethod {
    MAPPING,
    TITLE,
}

data class SyncNote(
    val id: Long,
    val idStr: String,
    val content: String?,
    val title: String,
    val lastModified: Long, // Epoch seconds
    val extra: String? = null,
    val category: String = "",
    val favorite: Boolean? = null,
    val readOnly: Boolean = false,
)
