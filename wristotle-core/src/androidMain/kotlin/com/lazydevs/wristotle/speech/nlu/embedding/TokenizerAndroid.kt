// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import android.content.Context

/**
 * Android-side adapter: loads the bundled `R.raw.minilm_vocab` resource
 * and wraps the pure [Tokenizer.fromVocabLines] factory. iOS-side
 * loading (NSBundle / DocumentDirectory) lives separately and uses the
 * same pure factory.
 */
fun Tokenizer.Companion.fromContext(context: Context, vocabResId: Int): Tokenizer =
    context.resources.openRawResource(vocabResId).bufferedReader().useLines { lines ->
        Tokenizer.fromVocabLines(lines)
    }
