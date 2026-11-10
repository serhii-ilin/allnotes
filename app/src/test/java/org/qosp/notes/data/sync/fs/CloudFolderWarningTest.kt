package org.qosp.notes.data.sync.fs

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudFolderWarningTest {
    private val device = "content://com.android.externalstorage.documents/tree/primary%3ANotes"
    private val cloud = "content://com.example.clouddrive.documents/tree/root%3ANotes"

    private fun warn(tree: String, warned: String) = shouldWarnAboutCloudFolder(Uri.parse(tree), warned)

    @Test
    fun deviceStorage_neverWarns() {
        assertFalse(warn(device, ""))
    }

    @Test
    fun otherProvider_warnsTheFirstTime() {
        assertTrue(warn(cloud, ""))
    }

    @Test
    fun sameTreeAgain_doesNotWarn_evenWithDifferentEncoding() {
        assertFalse(warn(cloud, cloud))
        assertFalse(warn(cloud, "content://com.example.clouddrive.documents/tree/root:Notes"))
    }

    @Test
    fun anotherCloudTree_warnsAgain() {
        assertTrue(warn(cloud, "content://com.example.clouddrive.documents/tree/root%3AOther"))
    }
}
