package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FMT-11: a name that becomes a file or folder segment must be valid on every file system. */
class VaultPathsNameValidationTest {

    private val blank = VaultPaths.NameProblem.Blank
    private val illegalChar = VaultPaths.NameProblem.IllegalChar
    private val leadingDot = VaultPaths.NameProblem.LeadingDot
    private val trailingDotOrSpace = VaultPaths.NameProblem.TrailingDotOrSpace
    private val reservedName = VaultPaths.NameProblem.ReservedName
    private val tooLong = VaultPaths.NameProblem.TooLong

    private fun assertProblem(expected: VaultPaths.NameProblem?, name: String) {
        assertEquals("name=<$name>", expected, VaultPaths.validateSegmentName(name))
    }

    @Test
    fun validateSegmentName_validNames_returnNull() {
        listOf("Work", "Мої нотатки", "a b", "a.b", "console", "COM10", "NULL", "con_", "_trash", " a")
            .forEach { assertProblem(null, it) }
    }

    @Test
    fun validateSegmentName_blank_isBlank() {
        listOf("", "   ", "\t\n").forEach { assertProblem(blank, it) }
    }

    @Test
    fun validateSegmentName_illegalChars_isIllegalChar() {
        listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\u0000", "\u001f").forEach {
            assertProblem(illegalChar, "a${it}b")
        }
        assertProblem(illegalChar, "a/b")
        assertProblem(illegalChar, "a\\b")
    }

    @Test
    fun validateSegmentName_leadingDot_isLeadingDot() {
        listOf(".trash", ".allnotes-staging", ".", "..", ".hidden").forEach {
            assertProblem(leadingDot, it)
        }
    }

    @Test
    fun validateSegmentName_trailingDotOrSpace_isTrailingDotOrSpace() {
        listOf("Work.", "Work ", "Work .", "a..", "a  ").forEach { assertProblem(trailingDotOrSpace, it) }
    }

    @Test
    fun validateSegmentName_reservedNames_isReservedName() {
        listOf("CON", "con", "Prn", "AUX", "NUL", "NUL.md", "COM1", "com9.txt", "LPT1", "lpt9.md").forEach {
            assertProblem(reservedName, it)
        }
    }

    @Test
    fun validateSegmentName_longName_isTooLong() {
        assertProblem(null, "a".repeat(200))
        assertProblem(null, "é".repeat(100))
        assertProblem(tooLong, "a".repeat(201))
        assertProblem(tooLong, "é".repeat(101))
        assertProblem(tooLong, "🙂".repeat(51))
    }

    @Test
    fun validateSegmentName_problemOrder_isStable() {
        assertProblem(blank, " ")
        assertProblem(illegalChar, "a/b.")
        assertProblem(leadingDot, ".")
        assertProblem(leadingDot, ". ")
        assertProblem(trailingDotOrSpace, "CON.")
        assertProblem(trailingDotOrSpace, "a".repeat(201) + ".")
        assertProblem(illegalChar, "a".repeat(201) + "/")
        assertProblem(tooLong, "a".repeat(201))
    }

    @Test
    fun collisionKey_ignoresCase() {
        assertEquals(VaultPaths.collisionKey("Work"), VaultPaths.collisionKey("work"))
        assertEquals(VaultPaths.collisionKey("Work"), VaultPaths.collisionKey("WORK"))
        assertEquals("work", VaultPaths.collisionKey("Work"))
        assertEquals(VaultPaths.collisionKey("I"), VaultPaths.collisionKey("i"))
    }

    @Test
    fun collisionKey_normalizesToNfc() {
        val nfc = "caf\u00e9"
        val nfd = "cafe\u0301"
        assertEquals(nfc, VaultPaths.collisionKey(nfd))
        assertEquals(VaultPaths.collisionKey(nfc), VaultPaths.collisionKey(nfd))
        assertEquals(VaultPaths.collisionKey("Мої нотатки"), VaultPaths.collisionKey("МОЇ НОТАТКИ"))
    }

    @Test
    fun sanitizeFileName_illegalChars_areReplaced() {
        assertProblem(illegalChar, "vacation: photo?")
        val sanitized = VaultPaths.sanitizeFileName("vacation: photo?.jpg")
        assertEquals("vacation_ photo_.jpg", sanitized)
        assertNull(VaultPaths.validateSegmentName(sanitized))
    }

    @Test
    fun sanitizeFileName_leadingDot_isReplaced() {
        assertEquals("_trash", VaultPaths.sanitizeFileName(".trash"))
        assertEquals("_allnotes-staging", VaultPaths.sanitizeFileName(".allnotes-staging"))
        assertEquals("_NET notes", VaultPaths.sanitizeFileName(".NET notes"))
        assertEquals("_", VaultPaths.sanitizeFileName("."))
        assertEquals("_", VaultPaths.sanitizeFileName(".."))
    }

    @Test
    fun sanitizeFileName_trimsTrailingDotAndSpace() {
        assertEquals("Work", VaultPaths.sanitizeFileName("Work."))
        assertEquals("Work", VaultPaths.sanitizeFileName("Work ."))
        assertEquals("Work", VaultPaths.sanitizeFileName("Work  "))
        assertEquals("Work", VaultPaths.sanitizeFileName(" Work."))
        assertEquals("a.b", VaultPaths.sanitizeFileName("a.b."))
    }

    @Test
    fun sanitizeFileName_reservedStem_getsUnderscore() {
        assertEquals("CON_.md", VaultPaths.sanitizeFileName("CON.md"))
        assertEquals("con_", VaultPaths.sanitizeFileName("con"))
        assertEquals("nul_", VaultPaths.sanitizeFileName("nul"))
        assertEquals("NUL_.txt", VaultPaths.sanitizeFileName("NUL.txt"))
        assertEquals("COM1_", VaultPaths.sanitizeFileName("COM1"))
        assertEquals("console", VaultPaths.sanitizeFileName("console"))
        assertEquals("console.md", VaultPaths.sanitizeFileName("console.md"))
    }

    @Test
    fun sanitizeFileName_keepsNamesThatWereAlreadyFine() {
        listOf("Plan", "photo.jpg", "Мої нотатки.md", "a b", "a.b", "console", "COM10").forEach {
            assertEquals(it, VaultPaths.sanitizeFileName(it))
        }
    }

    @Test
    fun sanitizeFileName_unicode_isNfc() {
        assertEquals("Caf\u00e9.md", VaultPaths.sanitizeFileName("Cafe\u0301.md"))
    }

    @Test
    fun sanitizeFileName_capsAt200Utf8Bytes() {
        val ascii = VaultPaths.sanitizeFileName("a".repeat(300))
        assertTrue(ascii.toByteArray(Charsets.UTF_8).size <= 200)

        val unicode = VaultPaths.sanitizeFileName("é".repeat(150))
        assertEquals("é".repeat(100), unicode)
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size <= 200)

        val withExtension = VaultPaths.sanitizeFileName("a".repeat(300) + ".md")
        assertTrue(withExtension.endsWith(".md"))
        assertTrue(withExtension.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun titleToNotePath_sanitizesOnlyTheFileName() {
        assertEquals("vacation_ photo_.md", VaultPaths.titleToNotePath("vacation: photo?", markdown = true))

        val cjkTitle = "日本語の長いタイトル" + "漢字".repeat(20)
        val cjkPath = VaultPaths.titleToNotePath(cjkTitle, markdown = true)
        assertTrue(cjkPath.endsWith(".md"))
        assertTrue(cjkPath.toByteArray(Charsets.UTF_8).size <= 200)
        assertNull(VaultPaths.validateSegmentName(cjkPath))
    }

    @Test
    fun uniqueSegmentName_collisionSuffixStillFitsTheByteLimit() {
        val long = "a".repeat(199) + "/"
        val sanitized = VaultPaths.sanitizeFileName(long)
        val unique = VaultPaths.uniqueSegmentName(long, setOf(VaultPaths.collisionKey(sanitized)))

        assertTrue(unique.endsWith("-2"))
        assertTrue(unique.toByteArray(Charsets.UTF_8).size <= 200)
        assertNull(VaultPaths.validateSegmentName(unique))
    }

    @Test
    fun sanitizeFileName_alwaysProducesAValidSegment() {        val inputs = listOf(
            "",
            " ",
            "   ",
            ".",
            "..",
            "...",
            ".hidden",
            ".trash",
            ".allnotes-staging",
            ".CON",
            "CON",
            "con",
            "CON.md",
            "NUL.md",
            "com1.txt",
            "CON.",
            "CON ",
            "CON .",
            "CON." + "x".repeat(196),
            "Work.",
            "Work ",
            "a/b",
            "a\\b",
            "a:b*c?d\"e<f>g|h",
            "\u0000\u001f",
            "Work",
            "Мої нотатки",
            "🙂 emoji 🙂",
            "Cafe\u0301",
            "a".repeat(300),
            "é".repeat(150),
            "会議".repeat(100),
            "a ".repeat(200),
            "a.".repeat(100),
            "CON." + "x".repeat(300),
            "plan." + "x".repeat(300),
            "." + "x".repeat(300),
        )
        inputs.forEach { input ->
            val sanitized = VaultPaths.sanitizeFileName(input)
            assertNotNull("input=<$input>", sanitized)
            assertNull("input=<$input> sanitized=<$sanitized>", VaultPaths.validateSegmentName(sanitized))
        }
        assertNull(VaultPaths.validateSegmentName(VaultPaths.sanitizeFileName("", "CON")))
    }
}
