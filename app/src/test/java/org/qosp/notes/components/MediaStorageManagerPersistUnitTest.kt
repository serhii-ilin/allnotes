package org.qosp.notes.components

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStorageManagerPersistUnitTest {

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var manager: MediaStorageManager

    private val mediaDir = "test_media"

    @Before
    fun setup() {
        context = spyk(ApplicationProvider.getApplicationContext())
        resolver = spyk(context.contentResolver)
        every { context.contentResolver } returns resolver
        manager = MediaStorageManager(context, mockk(relaxed = true), mediaDir)
        File(context.filesDir, mediaDir).deleteRecursively()
    }

    private fun sourceUri(name: String) = Uri.parse("content://unit.persist/$name")

    private fun stubDest(spied: MediaStorageManager, ext: String = "jpg"): Pair<Uri, File> {
        val destFile = File(context.filesDir, "$mediaDir/img_stub.$ext").apply {
            parentFile?.mkdirs()
        }
        val destUri = Uri.parse("content://unit.persist/dest.$ext")
        coEvery { spied.createMediaFile(any(), any()) } returns (destUri to destFile)
        every { resolver.openOutputStream(destUri) } answers {
            FileOutputStream(destFile)
        }
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        return destUri to destFile
    }

    @Test
    fun persistFromUri_missingSource_returnsNull() = runTest {
        val missing = sourceUri("missing")
        every { resolver.getType(missing) } returns "image/jpeg"
        every { resolver.openInputStream(missing) } returns null
        assertNull(manager.persistFromUri(missing))
    }

    @Test
    fun persistFromUri_emptyFile_returnsNull_sourceNotCopied() = runTest {
        val uri = sourceUri("empty")
        every { resolver.getType(uri) } returns "image/jpeg"
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(ByteArray(0))
        val spied = spyk(manager)
        val (_, destFile) = stubDest(spied)
        assertNull(spied.persistFromUri(uri))
        assertTrue(destFile.length() == 0L)
    }

    @Test
    fun persistFromUri_success_copiesVerified() = runTest {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val uri = sourceUri("camera-shot")
        every { resolver.getType(uri) } returns "image/jpeg"
        every { resolver.openInputStream(uri) } returnsMany listOf(
            ByteArrayInputStream(bytes),
            ByteArrayInputStream(bytes),
        )
        val spied = spyk(manager)
        stubDest(spied)
        val before = spied.listMediaFiles().size
        val dest = spied.persistFromUri(uri)
        assertNotNull(dest)
        assertEquals(before + 1, spied.listMediaFiles().size)
        val copied = File(context.filesDir, "$mediaDir/img_stub.jpg")
        assertEquals(VaultContentHash.ofBytes(bytes), VaultContentHash.ofBytes(copied.readBytes()))
    }

    @Test
    fun persistFromUri_unknownMime_usesGenericBinStaging() = runTest {
        val bytes = byteArrayOf(9, 8, 7)
        val uri = sourceUri("unknown")
        every { resolver.getType(uri) } returns "application/x-custom"
        every { resolver.openInputStream(uri) } returnsMany listOf(
            ByteArrayInputStream(bytes),
            ByteArrayInputStream(bytes),
        )
        val spied = spyk(manager)
        val destFile = File(context.filesDir, "$mediaDir/file_stub.bin").apply {
            parentFile?.mkdirs()
        }
        val destUri = Uri.parse("content://unit.persist/dest.bin")
        coEvery { spied.createMediaFile(MediaStorageManager.MediaType.GENERIC, ".bin") } returns (destUri to destFile)
        every { resolver.openOutputStream(destUri) } answers { FileOutputStream(destFile) }
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        val dest = spied.persistFromUri(uri)
        assertNotNull(dest)
        assertTrue(destFile.name.endsWith(".bin"))
    }

    @Test
    fun persistFromUri_hashMismatch_deletesDest() = runTest {
        val good = byteArrayOf(1, 2, 3)
        val bad = byteArrayOf(4, 5, 6)
        val uri = sourceUri("mismatch")
        every { resolver.getType(uri) } returns "image/jpeg"
        every { resolver.openInputStream(uri) } returnsMany listOf(
            ByteArrayInputStream(good),
            ByteArrayInputStream(bad),
        )
        val spied = spyk(manager)
        val (_, destFile) = stubDest(spied)
        val before = spied.listMediaFiles().size
        assertNull(spied.persistFromUri(uri))
        assertTrue(!destFile.exists() || destFile.length() == 0L)
        assertEquals(before, spied.listMediaFiles().size)
    }
}
