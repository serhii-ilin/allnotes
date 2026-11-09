package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VaultContentHashTest {
    @Test
    fun sameText_sameHash() {
        val a = VaultContentHash.ofText("hello")
        val b = VaultContentHash.ofText("hello")
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    @Test
    fun differentText_differentHash() {
        assertNotEquals(VaultContentHash.ofText("a"), VaultContentHash.ofText("b"))
    }
}
