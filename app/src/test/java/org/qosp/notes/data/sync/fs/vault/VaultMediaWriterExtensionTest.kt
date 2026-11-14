package org.qosp.notes.data.sync.fs.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Attachment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultMediaWriterExtensionTest {

    @Test
    fun copyAttachmentToVaultFs_labelWithoutExtension_getsM4a() {
        val context = spyk(ApplicationProvider.getApplicationContext<Context>())
        val resolver = spyk(context.contentResolver)
        every { context.contentResolver } returns resolver

        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val file = File(context.cacheDir, "audio_temp.m4a").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val uri = Uri.fromFile(file)
        every { resolver.getType(uri) } returns "audio/mp4"
        every { resolver.openInputStream(uri) } returnsMany listOf(
            ByteArrayInputStream(bytes),
            ByteArrayInputStream(bytes),
        )

        val fs = InMemoryVaultFs()
        val attachment = Attachment(
            Attachment.Type.AUDIO,
            uri.toString(),
            "Recorded clip",
        )
        val result = VaultMediaWriter.copyAttachmentToVaultFs(
            context,
            fs,
            "Work/Plan.md",
            attachment,
            mutableSetOf(),
        )
        val name = VaultPaths.fileName(VaultPaths.toVaultRelative(result.path))
        assertTrue("Expected .m4a vault file, got $name", name.endsWith(".m4a"))
    }
}
