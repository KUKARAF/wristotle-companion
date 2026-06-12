// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [dedupeRepeatedPhrases] + [stripAnnotationOnly].
 *
 * Most of these cases came from real Whisper output during dictation testing —
 * and three of the assertions here would have failed on earlier iterations of
 * the implementation. Kept in this exact shape so future tweaks have to keep
 * them passing. (Moved from :speech-whisper to :speech when the filters were
 * lifted so the cloud HttpRecognizer path could share them.)
 */
class TranscriptCleanupTest {

    // --- Cases that should collapse --------------------------------------

    @Test fun `two-word phrase repeated twice collapses to one`() {
        assertEquals("call me.", dedupeRepeatedPhrases("call me. call me."))
    }

    @Test fun `multi-word hallucination loop collapses to one copy`() {
        assertEquals(
            "give john dial dio",
            dedupeRepeatedPhrases("give john dial give john dial give john dial dio"),
        )
    }

    @Test fun `four copies of 4-token unit shortest-first finds the 4-token unit`() {
        // Earlier implementation used longest-first and matched k=8 (two copies
        // of an 8-token unit), collapsing 4 copies → 2 instead of 4 → 1.
        val input = "alpha beta gamma delta " + "alpha beta gamma delta ".repeat(3).trim()
        assertEquals("alpha beta gamma delta", dedupeRepeatedPhrases(input))
    }

    @Test fun `trailing partial-copy suffix gets swallowed too`() {
        // Whisper sometimes truncates its last hallucinated repeat. The
        // sustained sequence is collapsed AND the partial tail is dropped
        // because its tokens prefix-match the unit.
        val input = "one two three four " + "one two three four ".repeat(2) + "one two three"
        assertEquals("one two three four", dedupeRepeatedPhrases(input))
    }

    // --- Cases that should NOT collapse (user intent preserved) ----------

    @Test fun `single-word repeats survive (no min-2 unit)`() {
        assertEquals("yes yes yes yes", dedupeRepeatedPhrases("yes yes yes yes"))
    }

    @Test fun `three single-word repeats survive via size-lt-4 early return`() {
        assertEquals("yes yes yes", dedupeRepeatedPhrases("yes yes yes"))
    }

    @Test fun `silence hallucination of single token is left alone`() {
        assertEquals("dio dio dio", dedupeRepeatedPhrases("dio dio dio"))
    }

    @Test fun `four copies of single token survive (all-same guard)`() {
        assertEquals("dio dio dio dio", dedupeRepeatedPhrases("dio dio dio dio"))
    }

    @Test fun `clean transcript with no repeats passes through unchanged`() {
        val clean = "please send a message to mom that I'll be home soon"
        assertEquals(clean, dedupeRepeatedPhrases(clean))
    }

    // --- Comparison semantics --------------------------------------------

    @Test fun `case differences don't block detection`() {
        assertEquals("Call me.", dedupeRepeatedPhrases("Call me. CALL ME."))
    }

    @Test fun `trailing punctuation differences don't block detection`() {
        assertEquals("text dad,", dedupeRepeatedPhrases("text dad, text dad."))
    }

    // --- stripAnnotationOnly ---------------------------------------------

    @Test fun `asterisk-wrapped annotation collapses to empty`() {
        assertEquals("", stripAnnotationOnly("*Door opens*"))
        assertEquals("", stripAnnotationOnly("*sigh*"))
        assertEquals("", stripAnnotationOnly("*DING*"))
        assertEquals("", stripAnnotationOnly("*DAMN*"))
    }

    @Test fun `bracket-wrapped annotation collapses to empty`() {
        assertEquals("", stripAnnotationOnly("[laughter]"))
        assertEquals("", stripAnnotationOnly("[Music playing]"))
    }

    @Test fun `paren-wrapped annotation collapses to empty`() {
        assertEquals("", stripAnnotationOnly("(silence)"))
        assertEquals("", stripAnnotationOnly("(coughs)"))
    }

    @Test fun `chained annotations of mixed flavours collapse to empty`() {
        assertEquals("", stripAnnotationOnly("*sigh* [cough] (silence)"))
        assertEquals("", stripAnnotationOnly("*DING* *DING*"))
    }

    @Test fun `annotation with surrounding whitespace and punctuation collapses`() {
        assertEquals("", stripAnnotationOnly("  *Door opens*  "))
        assertEquals("", stripAnnotationOnly("*sigh*."))
        assertEquals("", stripAnnotationOnly("- *cough* —"))
    }

    @Test fun `mixed annotation plus real speech returns original unchanged`() {
        assertEquals("*sigh* call mom", stripAnnotationOnly("*sigh* call mom"))
        assertEquals("call mom *please*", stripAnnotationOnly("call mom *please*"))
    }

    @Test fun `plain transcript without annotations passes through`() {
        assertEquals("call mom", stripAnnotationOnly("call mom"))
        assertEquals("Door opens", stripAnnotationOnly("Door opens"))
    }

    @Test fun `empty input returns empty`() {
        assertEquals("", stripAnnotationOnly(""))
        assertEquals("", stripAnnotationOnly("   "))
    }
}
