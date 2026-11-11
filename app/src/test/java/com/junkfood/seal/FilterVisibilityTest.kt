package com.junkfood.seal

import com.junkfood.seal.ui.page.downloadv2.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterVisibilityTest {

    @Test
    fun `batch cards are visible on Playlist only`() {
        assertEquals(listOf(Filter.Playlist), Filter.entries.filter { it.showsBatchCards })
    }

    @Test
    fun `task cards are visible on every filter except Playlist`() {
        assertEquals(
            listOf(Filter.All, Filter.Downloading, Filter.Canceled, Filter.Finished),
            Filter.entries.filter { it.showsTaskCards },
        )
    }
}
