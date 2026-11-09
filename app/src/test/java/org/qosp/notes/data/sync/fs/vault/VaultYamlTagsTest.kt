package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultYamlTagsTest {

    @Test
    fun commaInsideQuotedTag_isSingleTag() {
        assertEquals(
            listOf("a, b", "c"),
            VaultYamlTags.parseInlineList("\"a, b\", c"),
        )
    }
}
