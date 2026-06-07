// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WordPiece tokenizer correctness. The real vocab has 30 522 rows and
 * lives in `R.raw.minilm_vocab` — this test builds a tiny vocab covering
 * just the special tokens + a handful of words / subwords. Catches:
 *  - lost specials (CLS / SEP / PAD / UNK)
 *  - lowercase / punctuation splitting drift
 *  - WordPiece subword fallback (`##` continuation)
 *  - sequence padding + attention mask shape
 *  - truncation that would corrupt the CLS / SEP framing
 */
class TokenizerTest {

    // Vocab IDs chosen to be human-readable in failure messages.
    // BERT-style: [PAD]=0, [UNK]=100, [CLS]=101, [SEP]=102.
    private val tokenizer = Tokenizer(
        vocab = mapOf(
            "[PAD]" to 0,
            "[UNK]" to 100,
            "[CLS]" to 101,
            "[SEP]" to 102,
            // Whole-word tokens.
            "hello" to 200,
            "world" to 201,
            "the" to 202,
            "weather" to 203,
            // Subword pieces — for "playing" → "play" + "##ing".
            "play" to 300,
            "##ing" to 301,
            // Punctuation.
            "?" to 400,
            "," to 401,
        ),
    )

    private val PAD = 0L
    private val UNK = 100L
    private val CLS = 101L
    private val SEP = 102L

    // ── Shape + framing ───────────────────────────────────────────────────

    @Test fun `encoded sequence always starts with CLS and ends at SEP`() {
        val e = tokenizer.encode("hello world", maxLength = 16)
        assertEquals(CLS, e.inputIds[0])
        // Find the last non-pad position; it must be SEP.
        val lastReal = e.inputIds.indexOfLast { it != PAD }
        assertEquals(SEP, e.inputIds[lastReal])
    }

    @Test fun `inputIds length equals maxLength`() {
        val e = tokenizer.encode("hello", maxLength = 16)
        assertEquals(16, e.inputIds.size)
        assertEquals(16, e.attentionMask.size)
        assertEquals(16, e.length)
    }

    @Test fun `unused tail is padded with PAD id`() {
        val e = tokenizer.encode("hello world", maxLength = 8)
        // Real tokens: [CLS, hello, world, SEP] = 4. Tail (4 slots) = PAD.
        assertEquals(PAD, e.inputIds[4])
        assertEquals(PAD, e.inputIds[7])
    }

    @Test fun `attention mask is 1 over real tokens and 0 over pad`() {
        val e = tokenizer.encode("hello world", maxLength = 8)
        // 4 real tokens (CLS hello world SEP), 4 pad.
        assertArrayEquals(
            longArrayOf(1, 1, 1, 1, 0, 0, 0, 0),
            e.attentionMask,
        )
    }

    // ── Lowercasing + punctuation ────────────────────────────────────────

    @Test fun `lowercases input`() {
        val a = tokenizer.encode("HELLO World", maxLength = 8).inputIds
        val b = tokenizer.encode("hello world", maxLength = 8).inputIds
        assertArrayEquals(b, a)
    }

    @Test fun `punctuation splits into its own token`() {
        val e = tokenizer.encode("hello, world?", maxLength = 16)
        // Expect: [CLS, hello, ',', world, '?', SEP, PAD…]
        val real = e.inputIds.takeWhile { it != PAD }
        assertEquals(
            listOf(CLS, 200L, 401L, 201L, 400L, SEP),
            real,
        )
    }

    // ── WordPiece subword fallback ───────────────────────────────────────

    @Test fun `unknown word in-vocab falls back to UNK`() {
        // "frobnicate" is not in our tiny vocab, no subword pieces either.
        val e = tokenizer.encode("frobnicate", maxLength = 8)
        // [CLS, UNK, SEP, PAD…]
        assertEquals(CLS, e.inputIds[0])
        assertEquals(UNK, e.inputIds[1])
        assertEquals(SEP, e.inputIds[2])
    }

    @Test fun `subword continuation splits on ## pieces`() {
        // "playing" → play + ##ing.
        val e = tokenizer.encode("playing", maxLength = 8)
        val real = e.inputIds.takeWhile { it != PAD }
        assertEquals(listOf(CLS, 300L, 301L, SEP), real)
    }

    // ── Truncation ───────────────────────────────────────────────────────

    @Test fun `truncates to maxLength while keeping CLS and SEP intact`() {
        // 6 real words but maxLength = 4 leaves room for [CLS] + 2 + [SEP].
        val e = tokenizer.encode("hello world the weather", maxLength = 4)
        assertEquals(4, e.inputIds.size)
        assertEquals(CLS, e.inputIds[0])
        assertEquals(SEP, e.inputIds[3])
        // The middle two slots come from the truncated tokens.
        assertTrue("middle should be real tokens", e.attentionMask[1] == 1L && e.attentionMask[2] == 1L)
    }

    @Test fun `truncation at exact fit produces no UNK`() {
        // 2 words + CLS + SEP = 4 tokens, maxLength = 4 — exact fit.
        val e = tokenizer.encode("hello world", maxLength = 4)
        assertArrayEquals(longArrayOf(CLS, 200, 201, SEP), e.inputIds)
    }

    @Test fun `maxLength below 2 is rejected`() {
        try {
            tokenizer.encode("hello", maxLength = 1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CLS") || e.message!!.contains("SEP"))
        }
    }

    // ── Whitespace edge cases ────────────────────────────────────────────

    @Test fun `collapsing extra whitespace`() {
        val a = tokenizer.encode("hello   world", maxLength = 8).inputIds
        val b = tokenizer.encode("hello world", maxLength = 8).inputIds
        assertArrayEquals(b, a)
    }

    @Test fun `leading and trailing whitespace are stripped`() {
        val a = tokenizer.encode("   hello   ", maxLength = 8).inputIds
        val b = tokenizer.encode("hello", maxLength = 8).inputIds
        assertArrayEquals(b, a)
    }
}