package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultMappingExtrasTest {
    @Test
    fun parse_baselineOnly() {
        val p = VaultMappingExtras.parse("abc123")
        assertEquals("abc123", p.baselineHash)
        assertNull(p.conflictRemoteHash)
    }

    @Test
    fun roundTrip_conflict() {
        val encoded = VaultMappingExtras.withConflict("base", "remoteHash", "Work/a (conflict).md")
        val p = VaultMappingExtras.parse(encoded)
        assertEquals("base", p.baselineHash)
        assertEquals("remoteHash", p.conflictRemoteHash)
        assertEquals("Work/a (conflict).md", p.conflictCopyPath)
    }
}
