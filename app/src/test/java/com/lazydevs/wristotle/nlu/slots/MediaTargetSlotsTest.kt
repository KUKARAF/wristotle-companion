package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTargetSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { MediaTargetSlots().extract(query) }

    @Test fun `pause with app name returns app slot`() {
        assertEquals("spotify", extract("pause spotify")["app"])
    }

    @Test fun `bare pause returns empty map`() {
        assertTrue(extract("pause").isEmpty())
    }

    @Test fun `pause the song is stripped to empty`() {
        // "the" and "song" are both fillers in MediaTargetSlots' set.
        assertTrue(extract("pause the song").isEmpty())
    }

    @Test fun `next with app name returns app slot`() {
        assertEquals("youtube", extract("next youtube")["app"])
    }

    @Test fun `next with track filler is stripped to empty`() {
        assertTrue(extract("next track").isEmpty())
    }

    @Test fun `previous with app name returns app slot`() {
        assertEquals("spotify", extract("previous spotify")["app"])
    }

    @Test fun `halt and stop are recognised as pause verbs`() {
        assertEquals("absorb", extract("halt absorb")["app"])
        assertEquals("audible", extract("stop audible")["app"])
    }

    @Test fun `skip is recognised as a next verb`() {
        assertEquals("podcast", run {
            // "podcast" isn't in FILLERS for this extractor (it's in
            // AppIndex.GENERIC_NOUNS, but slot extraction is upstream).
            extract("skip podcast")["app"] as String
        })
    }

    @Test fun `multi-word go back recognised as verb`() {
        assertEquals("spotify", extract("go back spotify")["app"])
    }

    @Test fun `case and trailing comma normalised`() {
        assertEquals("youtube", extract("Pause, YouTube,")["app"])
    }
}
