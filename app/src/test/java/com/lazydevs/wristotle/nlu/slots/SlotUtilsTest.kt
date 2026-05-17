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
}
