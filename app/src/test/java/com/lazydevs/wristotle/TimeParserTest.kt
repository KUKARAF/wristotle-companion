package com.lazydevs.wristotle

import com.lazydevs.wristotle.handlers.parseTime
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class TimeParserTest {

    private fun Calendar.hourOfDay() = get(Calendar.HOUR_OF_DAY)
    private fun Calendar.minute() = get(Calendar.MINUTE)

    private fun parsedCal(text: String): Calendar? {
        val date = parseTime(text)?.date ?: return null
        return Calendar.getInstance().apply { time = date }
    }

    @Test fun `ten pm parses as 22h`() {
        val cal = parsedCal("remind me at ten pm")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 22) { "expected 22, got ${cal.hourOfDay()}" }
    }

    @Test fun `ten thirty pm parses as 22h 30m`() {
        val cal = parsedCal("remind me at ten thirty pm")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 22) { "expected 22, got ${cal.hourOfDay()}" }
        assert(cal.minute() == 30) { "expected 30, got ${cal.minute()}" }
    }

    @Test fun `ten fifteen am parses as 10h 15m`() {
        val cal = parsedCal("remind me at ten fifteen am")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 10) { "expected 10, got ${cal.hourOfDay()}" }
        assert(cal.minute() == 15) { "expected 15, got ${cal.minute()}" }
    }

    @Test fun `ten forty-five pm parses as 22h 45m`() {
        val cal = parsedCal("take meds at ten forty-five pm")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 22) { "expected 22, got ${cal.hourOfDay()}" }
        assert(cal.minute() == 45) { "expected 45, got ${cal.minute()}" }
    }

    @Test fun `nine am parses as 9h`() {
        val cal = parsedCal("remind me at nine am")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 9) { "expected 9, got ${cal.hourOfDay()}" }
    }

    @Test fun `twelve pm parses as noon`() {
        val cal = parsedCal("remind me at twelve pm")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 12) { "expected 12, got ${cal.hourOfDay()}" }
    }

    @Test fun `in thirty minutes parses as relative`() {
        val before = System.currentTimeMillis()
        val result = parseTime("remind me in thirty minutes")
        assertNotNull(result)
        val ms = result!!.date.time - before
        assert(ms in 29 * 60 * 1000L..31 * 60 * 1000L) { "expected ~30min from now, got ${ms/60000}min" }
    }

    @Test fun `digit form still works`() {
        val cal = parsedCal("remind me at 10:30 pm")
        assertNotNull(cal)
        assert(cal!!.hourOfDay() == 22) { "expected 22, got ${cal.hourOfDay()}" }
        assert(cal.minute() == 30) { "expected 30, got ${cal.minute()}" }
    }

    @Test fun `no time returns null`() {
        assertNull(parseTime("remind me to buy groceries"))
    }

    // Bug: codeberg.org/wristotle/wristotle-companion/issues/8 —
    // "set an alarm for an hour from now" was firing at the current
    // time because prettytime can't quantify the indefinite article.
    // normalizeNumbers now rewrites `(a|an) (second|minute|hour|…)`
    // → `1 (second|minute|hour|…)` before handing off.
    // Bug: codeberg.org/wristotle/wristotle-companion/issues/8 —
    // "an hour from now" in the stripped form now parses correctly
    // after normalizeNumbers rewrites the indefinite article to "1".
    @Test fun `bare an hour from now parses as relative one hour`() {
        val before = System.currentTimeMillis()
        val result = parseTime("an hour from now")
        assertNotNull(result)
        val ms = result!!.date.time - before
        assert(ms in 55 * 60 * 1000L..65 * 60 * 1000L) {
            "expected ~1h from now, got ${ms / 60000}min (delta ${ms}ms, parsed=${result.date})"
        }
    }

    @Test fun `bare a minute parses as relative one minute`() {
        val before = System.currentTimeMillis()
        val result = parseTime("a minute from now")
        assertNotNull(result)
        val ms = result!!.date.time - before
        assert(ms in 50 * 1000L..70 * 1000L) {
            "expected ~1min from now, got ${ms / 1000}s"
        }
    }

    // The article rewrite must NOT touch articles that aren't part of
    // a time-unit phrase. "Remind me to buy a book" doesn't have a
    // time, so the rewrite shouldn't change the absence-of-time signal.
    @Test fun `article before non-time-noun stays untouched`() {
        assertNull(parseTime("remind me to buy a book"))
        assertNull(parseTime("set an alarm to call mom"))
    }
}
