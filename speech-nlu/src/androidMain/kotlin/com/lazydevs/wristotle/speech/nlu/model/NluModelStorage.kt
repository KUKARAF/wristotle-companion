// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.model

import android.content.Context
import com.lazydevs.wristotle.speech.model.ModelFileStorage

/**
 * NLU sentence-encoder model storage — files under
 * `filesDir/nlu-models/` named `minilm-{id}.onnx`. Sibling of
 * [com.lazydevs.wristotle.speech.whisper.ModelStorage]; both share
 * [ModelFileStorage] so the on-disk + active-id plumbing lives in
 * one place.
 */
class NluModelStorage(context: Context) : ModelFileStorage(
    context = context,
    dirName = "nlu-models",
    prefsName = "nlu_models",
    filenamePrefix = "minilm-",
    filenameExtension = ".onnx",
)