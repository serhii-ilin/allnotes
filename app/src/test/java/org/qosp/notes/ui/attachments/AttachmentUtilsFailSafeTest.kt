package org.qosp.notes.ui.attachments

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class AttachmentUtilsFailSafeTest {

  private val context = mockk<Context>(relaxed = true)

  @Test
  fun vaultrel_missingOnDisk_returnsNull() {
    stopKoin()
    val tree = mockk<VaultTreeAccess> { every { resolveFileUri(any()) } returns null }
    startKoin { modules(module { single { tree } }) }
    try {
      assertNull(getAttachmentUri(context, VaultPaths.vaultRelative("Ttt/ghost.jpg")))
    } finally {
      stopKoin()
    }
  }

  @Test
  fun malformedVaultrel_returnsNull() {
    stopKoin()
    startKoin { modules(module { single { mockk<VaultTreeAccess>(relaxed = true) } }) }
    try {
      assertNull(getAttachmentUri(context, "vaultrel:"))
    } finally {
      stopKoin()
    }
  }

  @Test
  fun rawRelativePath_notPassedToResolver_returnsNull() {
    every { context.filesDir } returns mockk(relaxed = true)
    assertNull(getAttachmentUri(context, "not-a-uri-relative.jpg"))
  }
}
