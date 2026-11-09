package org.qosp.notes.data.sync.fs.vault

import java.security.MessageDigest

object VaultContentHash {
    fun ofBytes(bytes: ByteArray): String = digest(bytes)

    fun ofText(text: String): String = digest(text.encodeToByteArray())

    private fun digest(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
