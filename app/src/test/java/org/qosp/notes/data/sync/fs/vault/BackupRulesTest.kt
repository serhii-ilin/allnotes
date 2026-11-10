package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto Backup must not upload plaintext note copies or prefs whose key is not restored.
 * Parsed from source: Robolectric has no app resources in these tests.
 */
class BackupRulesTest {

    private val expected = setOf(
        "sharedpref:encrypted_prefs.xml",
        "file:vault-scan-cache/",
        "file:vault-recovery/",
    )

    private fun mainDir() = File("src/main")

    private fun excludes(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") + ":" + it.getAttribute("path") }
            .toSet()
    }

    private fun root(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(mainDir(), "res/xml/$name")).documentElement

    @Test
    fun fullBackupContent_excludesPrivateData() {
        assertEquals(expected, excludes(root("backup_rules.xml")))
    }

    @Test
    fun dataExtractionRules_excludePrivateDataFromBothTransfers() {
        val root = root("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val el = root.getElementsByTagName(section).item(0) as Element
            assertEquals(section, expected, excludes(el))
        }
    }

    @Test
    fun manifestReferencesBothRules() {
        val manifest = File(mainDir(), "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@xml/backup_rules"))
        assertTrue(manifest.contains("@xml/data_extraction_rules"))
    }
}
