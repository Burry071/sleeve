package com.junkfood.seal

import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.OutputTemplate
import com.junkfood.seal.util.OutputTemplate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTemplateTest {

    private fun blocks(template: String) =
        OutputTemplate.validate(template).filter { it.severity == Severity.BLOCK }

    private fun warns(template: String) =
        OutputTemplate.validate(template).filter { it.severity == Severity.WARN }

    @Test
    fun `the built-in templates validate clean`() {
        assertTrue(OutputTemplate.validate(DownloadUtil.OUTPUT_TEMPLATE_DEFAULT).isEmpty())
        assertTrue(OutputTemplate.validate(DownloadUtil.OUTPUT_TEMPLATE_ID).isEmpty())
    }

    @Test
    fun `a lone percent blocks at its offset`() {
        val template = "100% %(title).200B.%(ext)s"
        val issue = blocks(template).single()
        assertEquals(3, issue.start)
    }

    @Test
    fun `a percent without a field or conversion blocks`() {
        assertTrue(blocks("%(title).200B.%(ext)s 50% off").isNotEmpty())
    }

    @Test
    fun `a literal percent and a field token pass`() {
        assertTrue(blocks("%%(title).200B.%(ext)s").isEmpty())
        assertTrue(warns("%%(title).200B.%(ext)s").isEmpty())
    }

    @Test
    fun `an unknown token warns with its offset and length`() {
        val template = "%(title).200B.%(ext)s %(nonsense)s"
        val issue = warns(template).single()
        assertEquals(template.indexOf("%(nonsense)"), issue.start)
        assertEquals("%(nonsense)s".length, issue.length)
    }

    @Test
    fun `a slash is legal here`() {
        assertTrue(warns("%(uploader)s/%(title).200B.%(ext)s").isEmpty())
    }

    @Test
    fun `characters illegal on FAT32 warn at their offset`() {
        val template = "%(title)s: copy.%(ext)s"
        val issue = warns(template).single()
        assertEquals(template.indexOf(':'), issue.start)
        assertEquals(1, issue.length)
    }

    @Test
    fun `the two existing requirements still block`() {
        assertTrue(blocks("just-a-name.%(ext)s").any { it.length == 0 })
        assertTrue(blocks("%(title).200B").any { it.length == 0 })
        assertTrue(blocks("").any { it.length == 0 })
    }

    @Test
    fun `the preview substitutes the sample for the default name`() {
        assertEquals(
            "Example video [dQw4w9WgXcQ].mp4",
            OutputTemplate.preview(DownloadUtil.OUTPUT_TEMPLATE_ID),
        )
    }

    @Test
    fun `the preview keeps the template directory`() {
        assertEquals(
            "Example channel/Example video.mp4",
            OutputTemplate.preview("%(uploader)s/%(title).200B.%(ext)s"),
        )
    }
}
