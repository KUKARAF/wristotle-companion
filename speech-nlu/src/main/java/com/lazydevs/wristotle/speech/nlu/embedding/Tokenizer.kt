package com.lazydevs.wristotle.speech.nlu.embedding

import android.content.Context

/**
 * BERT-style WordPiece tokenizer for MiniLM-L6-v2. Loads the vocab from
 * `R.raw.minilm_vocab` and exposes a single [encode] method that returns
 * input_ids + attention_mask shaped for ONNX inference.
 *
 * Sequence layout: `[CLS] tok... [SEP]`, padded to [maxLength] (default 64,
 * comfortably covers any voice command we'd reasonably see). Truncation
 * keeps `[CLS]` + first (maxLength - 2) tokens + `[SEP]` so the encoder
 * always sees a well-formed sequence.
 *
 * Not a complete BERT tokenizer — skips a few corner cases (non-ASCII
 * casing, Chinese-char splitting) that don't matter for English voice
 * commands. Cross-checked against the canonical Python `transformers`
 * tokenizer on a fixture of ~50 phrases before shipping.
 */
class Tokenizer private constructor(
    private val vocab: Map<String, Int>,
) {
    private val unkId = vocab[UNK] ?: error("vocab missing $UNK")
    private val clsId = vocab[CLS] ?: error("vocab missing $CLS")
    private val sepId = vocab[SEP] ?: error("vocab missing $SEP")
    private val padId = vocab[PAD] ?: error("vocab missing $PAD")

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
    ) {
        /** Same length as [inputIds]; always equal to [maxLength] passed in. */
        val length: Int get() = inputIds.size
    }

    fun encode(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): Encoded {
        require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]" }

        val tokens = mutableListOf<Int>()
        tokens += clsId
        for (word in basicTokenize(text)) {
            val pieces = wordpiece(word)
            // Truncate so we still have room for [SEP] at the end.
            val budget = maxLength - 1 - tokens.size
            if (budget <= 0) break
            tokens += if (pieces.size <= budget) pieces else pieces.subList(0, budget)
            if (tokens.size >= maxLength - 1) break
        }
        tokens += sepId

        val inputIds = LongArray(maxLength) { padId.toLong() }
        val attentionMask = LongArray(maxLength)
        for (i in tokens.indices) {
            inputIds[i] = tokens[i].toLong()
            attentionMask[i] = 1L
        }
        return Encoded(inputIds, attentionMask)
    }

    // ── Basic tokenization (lowercase + whitespace + punctuation split) ───

    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.lowercase().trim()
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> {
                    if (buf.isNotEmpty()) { out += buf.toString(); buf.clear() }
                }
                ch.isPunctuation() -> {
                    if (buf.isNotEmpty()) { out += buf.toString(); buf.clear() }
                    out += ch.toString()
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun Char.isPunctuation(): Boolean {
        // BERT's notion of punctuation: ASCII punctuation + Unicode categories.
        val code = code
        return (code in 33..47) || (code in 58..64) ||
               (code in 91..96) || (code in 123..126) ||
               category in PUNCT_CATEGORIES
    }

    // ── WordPiece subword tokenization ────────────────────────────────────

    /** Greedy longest-match-first; falls back to [UNK] for a whole word. */
    private fun wordpiece(word: String): List<Int> {
        if (word.length > MAX_INPUT_CHARS_PER_WORD) return listOf(unkId)
        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matchedId: Int? = null
            while (end > start) {
                val sub = word.substring(start, end).let {
                    if (start > 0) "##$it" else it
                }
                val id = vocab[sub]
                if (id != null) { matchedId = id; break }
                end--
            }
            if (matchedId == null) return listOf(unkId)   // whole-word fallback
            pieces += matchedId
            start = end
        }
        return pieces
    }

    companion object {
        private const val PAD = "[PAD]"
        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val DEFAULT_MAX_LENGTH = 64
        private const val MAX_INPUT_CHARS_PER_WORD = 100

        private val PUNCT_CATEGORIES = setOf(
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
        )

        /** Loads the vocab from the bundled raw resource. */
        fun fromContext(context: Context, vocabResId: Int): Tokenizer {
            val vocab = HashMap<String, Int>(30_522)
            context.resources.openRawResource(vocabResId).bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, raw -> vocab[raw.trim()] = index }
            }
            return Tokenizer(vocab)
        }
    }
}
