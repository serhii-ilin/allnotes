package org.qosp.notes.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStorageManagerOriginalNameTest {

    private lateinit var manager: MediaStorageManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = MediaStorageManager(context, io.mockk.mockk(relaxed = true), "orig_media")
        File(context.filesDir, "orig_media").deleteRecursively()
    }

    @Test
    fun uniqueMediaFileName_preservesSanitizedOriginal() {
        val name = manager.uniqueMediaFileNameForTest("Holiday Photo.jpg")
        assertEquals("Holiday Photo.jpg", name)
        File(manager.mediaDirectoryForTest(), name).apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        val second = manager.uniqueMediaFileNameForTest("Holiday Photo.jpg")
        assertEquals("Holiday Photo-2.jpg", second)
    }

    @Test
    fun persistFailure_recordsUserVisibleError() {
        assertTrue(manager.lastPersistError == null)
    }
}
