package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.speech.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixHintsTest {

    @Test fun `text prefix maps to Sms`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("text John saying yes"))
    }

    @Test fun `send a message to maps to Sms`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("send a message to dad"))
    }

    @Test fun `call prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("call mom"))
    }

    @Test fun `dial prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("dial 911"))
    }

    @Test fun `ring prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("ring my mom"))
    }

    @Test fun `remind prefix maps to Reminder`() {
        assertEquals(Intent.Reminder, PrefixHints.hintFor("remind me at 5pm"))
    }

    @Test fun `cancel prefix maps to Cancel`() {
        assertEquals(Intent.Cancel, PrefixHints.hintFor("cancel reminder"))
    }

    @Test fun `find phone variants map to FindPhone`() {
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("find my phone"))
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("where is my phone"))
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("where's my phone"))
    }

    @Test fun `non-prefix occurrence is ignored`() {
        // "call" mid-sentence shouldn't trigger Call hint.
        assertNull(PrefixHints.hintFor("when should I call back"))
    }

    @Test fun `unknown phrase returns null`() {
        assertNull(PrefixHints.hintFor("what's the weather"))
    }

    @Test fun `leading whitespace doesn't block detection`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("   call mom"))
    }

    @Test fun `case-insensitive matching`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("Text John"))
        assertEquals(Intent.Sms, PrefixHints.hintFor("TEXT JOHN"))
    }
}
