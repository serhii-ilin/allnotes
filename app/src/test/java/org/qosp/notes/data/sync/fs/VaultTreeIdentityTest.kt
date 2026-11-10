package org.qosp.notes.data.sync.fs

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultTreeIdentityTest {

    private val external = "com.android.externalstorage.documents"

    private fun same(a: String, b: String) = isSameVaultTree(Uri.parse(a), Uri.parse(b))

    @Test
    fun sameTree_differentEncoding_isSame() {
        assertTrue(same("content://$external/tree/primary%3ANotes", "content://$external/tree/primary:Notes"))
    }

    @Test
    fun sameTree_documentUriOfTheTree_isSame() {
        assertTrue(
            same(
                "content://$external/tree/primary%3ANotes",
                "content://$external/tree/primary%3ANotes/document/primary%3ANotes",
            ),
        )
    }

    @Test
    fun differentDocumentId_isDifferent() {
        assertFalse(same("content://$external/tree/primary%3ANotes", "content://$external/tree/primary%3AOther"))
    }

    @Test
    fun differentAuthority_isDifferent() {
        assertFalse(same("content://$external/tree/primary%3ANotes", "content://com.other.provider/tree/primary%3ANotes"))
    }

    @Test
    fun nonTreeUris_matchOnlyWhenEqual() {
        assertTrue(same("content://x/y", "content://x/y"))
        assertFalse(same("content://x/y", "content://x/z"))
    }
}
