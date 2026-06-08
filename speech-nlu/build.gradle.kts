// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 + Kotlin 2.x KMP module shape. Both plugins required:
    //   - kotlin.multiplatform: provides the `kotlin { }` DSL +
    //     iOS targets (iosX64 / iosArm64 / iosSimulatorArm64).
    //   - android.kotlin.multiplatform.library: provides the
    //     `kotlin.android { }` sub-block — replaces the legacy
    //     com.android.library, which AGP 9 rejects when combined
    //     with kotlin.multiplatform.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// iOS spike S1a: first KMP module in the build. The pure NLU brain
// stays in androidMain for now (S1b lifts it to commonMain). iOS
// targets are wired with empty source sets that S1c-d will fill
// with expect/actual stubs (MiniLmEmbedder, NluModelStorage).
//
// Source layout:
//   src/commonMain/kotlin       — pure Kotlin (S1b target)
//   src/androidMain/kotlin      — Android-specific (currently everything)
//   src/androidMain/res         — Android resources (minilm_vocab.txt)
//   src/androidHostTest/kotlin  — Android unit tests (pure-JUnit)
//   src/iosMain/kotlin          — iOS-specific (stubs / native bridge — S1c-d)
//
// Room-backed learning persistence moved into :app/nlu/learning/
// (separate commit f651628) so this module is now Room-free and
// KSP-free — the constraints that blocked the first S1a attempt.

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.lazydevs.wristotle.speech.nlu"
        compileSdk = 36
        minSdk = 24

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        // res/raw/minilm_vocab.txt is bundled as an Android resource.
        androidResources {
            enable = true
        }

        // Unit-test sources land in src/androidHostTest/kotlin/...
        // Mirror :app's setting so pure-Kotlin code that happens to
        // log on the JVM (e.g. classifier warm-up paths) doesn't
        // drag in Robolectric just to satisfy android.util.Log.
        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // kotlinx-coroutines-core provides Mutex / withContext /
            // Dispatchers.Default for every target. The Android side
            // pulls in the android-specific Main dispatcher
            // separately via kotlinx-coroutines-android.
            implementation(libs.kotlinx.coroutines.core)
            // kotlinx-serialization-json for the lifted MCP layer
            // (JsonObject / JsonElement). Multiplatform; safe on iOS.
            implementation(libs.kotlinx.serialization.json)
            // kotlinx-datetime — replaces java.util.{Date, Calendar, TimeZone}
            // + java.text.SimpleDateFormat for the lifted briefing pieces
            // (TodayRange today, MorningBriefRenderer + Date-using slots next).
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(project(":speech"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.onnxruntime.android)
        }
        iosMain.dependencies {
            // stubs for S1; real ONNX iOS wiring lands in S2
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
