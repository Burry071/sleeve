package com.junkfood.seal

import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistNumberingTest {

    private val template = DownloadUtil.OUTPUT_TEMPLATE_DEFAULT

    @Test
    fun `the number is inserted after the last slash`() {
        val withDirectory = "%(uploader)s/$template"
        assertEquals(
            "%(uploader)s/03 - $template",
            TaskFactory.numberedTemplate(withDirectory, index = 3, enabled = true),
        )
        assertEquals(
            "03 - $template",
            TaskFactory.numberedTemplate(template, index = 3, enabled = true),
        )
    }

    @Test
    fun `the index is zero padded`() {
        assertEquals(
            "07 - $template",
            TaskFactory.numberedTemplate(template, index = 7, enabled = true),
        )
    }

    @Test
    fun `a template with autonumber is left alone`() {
        val autonumber = "%(autonumber)s - $template"
        assertEquals(autonumber, TaskFactory.numberedTemplate(autonumber, 2, enabled = true))
    }

    @Test
    fun `the toggle off changes nothing`() {
        assertEquals(template, TaskFactory.numberedTemplate(template, 2, enabled = false))
    }

    @Test
    fun `chapter split and clip batches are never numbered`() {
        assertEquals(
            template,
            TaskFactory.numberedTemplate(template, 2, enabled = true, splitByChapter = true),
        )
        assertEquals(
            template,
            TaskFactory.numberedTemplate(template, 2, enabled = true, hasClips = true),
        )
    }

    @Test
    fun `a template that already carries a section token is left alone`() {
        val chapters = "chapter:%(title).200B/%(section_number)d - %(section_title).200B.%(ext)s"
        assertEquals(chapters, TaskFactory.numberedTemplate(chapters, 2, enabled = true))
        val clips = "%(title).200B [%(section_start)d-%(section_end)d].%(ext)s"
        assertEquals(clips, TaskFactory.numberedTemplate(clips, 2, enabled = true))
    }

    @Test
    fun `index zero means no position`() {
        assertEquals(template, TaskFactory.numberedTemplate(template, 0, enabled = true))
    }
}
