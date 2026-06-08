// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

plugins {
    alias(libs.plugins.android.library)
}

// iOS spike refactor (2026-06-07): the Room-backed ExampleBank /
// ExampleDao / NluDatabase / ExampleEntry moved out of this module
// into app/.../nlu/learning/ — the bank is an Android persistence
// concern that lives in the consuming app, not in the shared NLU
// brain. The classifier now asks for learned rows via the pure
// `loadLearned: suspend () -> List<LearnedExample>` lambda instead
// of taking an ExampleBank dependency. Net effect: this module is
// Room-free and KSP-free, which unblocks the next attempt at the
// KMP restructure (iOS spike S1a).
//
// Still Android-only here because of MiniLmEmbedder (ONNX Runtime
// Android binding) + the res/raw/minilm_vocab.txt resource. The
// next pass at S1a re-introduces KMP with these as the only
// expect/actual seams.

android {
    namespace = "com.lazydevs.wristotle.speech.nlu"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        ndk { abiFilters += "arm64-v8a" }   // mirror :speech-whisper for consistency
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // No-op `android.util.Log` so pure-Kotlin code that happens to
        // log tests on the JVM without dragging in Robolectric.
        // Mirrors :app.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":speech"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
