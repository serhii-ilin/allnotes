package org.qosp.notes.data.sync.fs.vault

/** Ids that every device derives the same way, so independent writes produce identical bytes. */
object VaultNoteIds {
    fun forFile(normalizedPath: String, created: Long?): String =
        java.util.UUID.nameUUIDFromBytes("allnotes:$normalizedPath:${created ?: ""}".toByteArray()).toString()
}
