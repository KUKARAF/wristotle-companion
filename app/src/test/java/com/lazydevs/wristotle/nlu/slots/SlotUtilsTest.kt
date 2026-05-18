package com.lazydevs.wristotle.nlu.slots

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotUtilsTest {

    // --- stripTrailingEmphasis ----------------------------------------------

    @Test fun `single emphatic word is preserved (could be polite filler)`() {
        // "john please" might be a polite contact suffix; one word isn't
        // enough signal to strip. The threshold is 2+ for ambiguity.
        assertEquals("john please", stripTrailingEmphasis("john please"))
    }

    @Test fun `two trailing emphatic words get stripped`() {
        assertEquals("john", stripTrailingEmphasis("john yes yes"))
    }

    @Test fun `three trailing emphatic words get stripped`() {
        assertEquals("john", stripTrailingEmphasis("john, yes, yes, yes"))
    }

    @Test fun `comma-separated emphatic run gets stripped`() {
        assertEquals("call john", stripTrailingEmphasis("call john, yes, yes, yes."))
    }

    @Test fun `mixed emphatic vocabulary gets stripped`() {
        assertEquals("text mom", stripTrailingEmphasis("text mom ok ok ok please"))
    }

    @Test fun `nothing to strip leaves text alone`() {
        assertEquals("call mom", stripTrailingEmphasis("call mom"))
    }

    @Test fun `trailing punctuation alone is trimmed`() {
        assertEquals("call john", stripTrailingEmphasis("call john."))
    }

    // --- cleanNameToken -----------------------------------------------------

    @Test fun `trailing comma gets stripped from a name token`() {
        assertEquals("john", cleanNameToken("john,"))
    }

    @Test fun `quoted name token gets cleaned`() {
        assertEquals("john", cleanNameToken("\"john\""))
    }

    @Test fun `leading and trailing whitespace gets trimmed`() {
        assertEquals("john", cleanNameToken("  john  "))
    }

    @Test fun `clean token passes through unchanged`() {
        assertEquals("John", cleanNameToken("John"))
    }

    // --- stripVerbBody ------------------------------------------------------

    private val verbs = Regex("(?i)\\b(play|resume)\\b")
    private val fillers = Regex("(?i)\\b(the|some|please|on)\\b")

    @Test fun `stripVerbBody strips a leading verb and returns the body`() {
        assertEquals("spotify", stripVerbBody("play spotify", verbs, fillers))
    }

    @Test fun `stripVerbBody strips fillers anywhere in the query`() {
        assertEquals("youtube", stripVerbBody("play the youtube please", verbs, fillers))
    }

    @Test fun `stripVerbBody lowercases the input`() {
        assertEquals("spotify", stripVerbBody("Play SPOTIFY", verbs, fillers))
    }

    @Test fun `stripVerbBody collapses runs of whitespace`() {
        assertEquals("spotify", stripVerbBody("play    spotify", verbs, fillers))
    }

    @Test fun `stripVerbBody trims trailing sentence punctuation`() {
        assertEquals("spotify", stripVerbBody("play spotify.", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify,", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify!", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify?", verbs, fillers))
    }

    @Test fun `stripVerbBody returns empty when only the verb is present`() {
        assertEquals("", stripVerbBody("play", verbs, fillers))
    }

    @Test fun `stripVerbBody returns empty when verb plus only fillers`() {
        assertEquals("", stripVerbBody("play the some please", verbs, fillers))
    }
}
