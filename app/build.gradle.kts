// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing config. Three sources, in order of precedence:
//   1. Environment variables (used by CI — Codeberg Actions decodes the
//      base64 keystore secret into a temp file and exports the rest).
//   2. keystore.properties in the project root (gitignored — local dev).
//   3. No config — release builds fall back to the debug signing key so
//      `./gradlew assembleRelease` still works for smoke testing,
//      producing an APK that installs but can't be Play-/Codeberg-released.
val releaseSigning: Map<String, String>? = run {
    val storeFile = System.getenv("WRISTOTLE_KEYSTORE_FILE")
    val storePassword = System.getenv("WRISTOTLE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("WRISTOTLE_KEY_ALIAS")
    val keyPassword = System.getenv("WRISTOTLE_KEY_PASSWORD")
    if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
        mapOf(
            "storeFile" to storeFile,
            "storePassword" to storePassword,
            "keyAlias" to keyAlias,
            "keyPassword" to keyPassword,
        )
    } else {
        val local = rootProject.file("keystore.properties")
        if (local.exists()) {
            val props = Properties().apply { local.inputStream().use(::load) }
            mapOf(
                "storeFile" to props.getProperty("storeFile"),
                "storePassword" to props.getProperty("storePassword"),
                "keyAlias" to props.getProperty("keyAlias"),
                "keyPassword" to props.getProperty("keyPassword"),
            )
        } else null
    }
}

android {
    namespace = "com.lazydevs.wristotle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazydevs.wristotle"
        minSdk = 24
        targetSdk = 36
        // **Single source of truth for the released version.** Both
        // Codeberg CI and F-Droid's metadata scanner build straight off
        // these literals — no env-var override path — so the gradle file,
        // the git tag, and the released APK can't drift apart. Whoever
        // bumps the tag also bumps these two lines; the pre-push hook
        // refuses to push a tag that doesn't match, and CI does the same
        // check up front.
        //
        // Bump scheme: versionCode is 2 digits per component (max 99.99.99).
        // v1.2.3 → versionCode 1*10000 + 2*100 + 3 = 10203.
        versionCode = 11900
        versionName = "1.19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Restrict the APK to arm64-v8a. The :speech-whisper module
            // already filters here, but the ONNX Runtime AAR (pulled in via
            // :wristotle-core) ships .so files for x86_64 / x86 / armeabi-v7a /
            // arm64-v8a — without this app-level filter, the APK ships all
            // four variants of libonnxruntime.so (~75 MB combined) when only
            // arm64-v8a is loadable on the target devices. Adding the filter
            // here drops the unused variants and shrinks the APK by ~56 MB.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        releaseSigning?.let {
            create("release") {
                storeFile = file(it["storeFile"]!!)
                storePassword = it["storePassword"]
                keyAlias = it["keyAlias"]
                keyPassword = it["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking. AGP's bundled
            // proguard-android-optimize.txt + Compose/Room/Kotlin's per-
            // library consumer rules cover the bulk of what we need; our
            // own proguard-rules.pro holds the project-specific keep rules
            // (JNI bridges, PebbleKit2 reflection paths, etc).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing when configured; otherwise leave the
            // default debug signing so local `assembleRelease` still works
            // for quick checks. CI builds will always have it configured.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            // AGP 8.3+ embeds a `META-INF/version-control-info.textproto`
            // containing the build's git commit SHA. The file isn't
            // load-bearing for anything we ship — it's metadata Google
            // Play uses, not anything Wristotle reads — and the embedded
            // SHA breaks reproducibility (every commit produces a
            // different APK content), which we want for the F-Droid
            // distribution path.
            vcsInfo {
                include = false
            }
        }
    }

    // AGP embeds an opaque, Google-signed "dependency metadata" blob in the APK
    // Signing Block (for Play Console dependency reporting). F-Droid's scanner
    // rejects it as a CRITICAL "extra signing block 'Dependency metadata'", and
    // it's encrypted + non-deterministic so it also breaks reproducible builds.
    // Strip it from both the APK and the App Bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // PNG-crunch reproducibility was a real concern under aapt + AGP 7.
    // AGP 8's aapt2 crunches deterministically by default and the legacy
    // `aaptOptions.cruncherEnabled` toggle has been removed. We use
    // `imageVector` Material icons throughout (pure-code, no rasterisation
    // at build time), so the remaining PNG surface is just the launcher
    // and a couple of small assets — low risk. If F-Droid's reproducibility
    // verification later flags PNG handling, revisit with the Variant API.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig surfaces VERSION_NAME / VERSION_CODE / BUILD_TYPE
        // into Kotlin source — DiagnosticsBuilder reads them when
        // assembling the bug-report bundle. AGP 8+ no longer enables
        // BuildConfig generation by default.
        buildConfig = true
    }
    testOptions {
        // Make `android.util.Log` calls no-op rather than throw the
        // "Method not mocked" RuntimeException in JVM unit tests.
        // Lets pure-function code that happens to log (e.g. AppIndex)
        // be unit-tested without dragging in Robolectric.
        unitTests.isReturnDefaultValues = true
        // Cap the forked test JVM so the medium runner has headroom
        // for AGP's on-demand build-tools install + the Gradle daemon
        // + Kotlin daemon. Default is unbounded → cumulative
        // (1.5 GB gradle daemon + 1 GB Kotlin daemon + ≥1 GB SDK
        // install + ≥512 MB test JVM) overshoots ~4 GB on
        // codeberg-medium and the kernel OOM-kills mid-build (v0.15.0
        // hit this twice). 512 MB is plenty for our pure-JUnit cases.
        unitTests.all { test ->
            // 512m -> 384m: trims the forked test JVM further after the release
            // CI's Gradle daemon kept getting OOM-killed on the self-hosted
            // runner. Our tests are pure JUnit; 384m is ample.
            test.maxHeapSize = "384m"
        }
    }

    // Keep ALL bundled `.so` out of AGP's `stripReleaseDebugSymbols`:
    //   - The prebuilt AAR/NDK libs (ONNX runtime, AndroidX graphics-path,
    //     datastore, libc++_shared) ship pre-compiled and byte-identical in
    //     every build; AGP's strip can't process some of their sections anyway.
    //   - The locally-compiled libs (libggml*, libwhisper, libwristotle_speech)
    //     are already fully stripped AT LINK TIME via `-Wl,--strip-all` (see
    //     speech-whisper/build.gradle.kts). AGP's stripReleaseDebugSymbols task
    //     does NOT strip them on the Linux NDK (it does on macOS), so letting it
    //     run would make the APK depend on the build host. Keeping them here
    //     means the APK ships exactly the linker's deterministic stripped output
    //     on every platform — the key to F-Droid reproducible-build verification.
    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libc++_shared.so",
                "**/libdatastore_shared_counter.so",
                "**/libggml.so",
                "**/libggml-base.so",
                "**/libggml-cpu.so",
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libwhisper.so",
                "**/libwristotle_speech.so",
            )
        }
    }
}

// Reproducible-build fix: Compose's baseline-profile generation
// (`generateReleaseBaselineProfile…` / `ArtProfile…` tasks) embeds a
// `baseline.profm` whose contents aren't deterministic across builds. We
// don't ship one today (the perf win is minor for a UI-light app), so
// disable the generation tasks entirely. Cheap, side-effect-free, and
// keeps the F-Droid rebuild byte-identical to ours.
tasks.matching { it.name.contains("ArtProfile") }.configureEach {
    enabled = false
}

// Ktor (pulled in by the MCP client) brings slf4j-api 2.x, and
// prettytime-nlp ships an older slf4j shaded inside its own jar — D8
// then fails with `Duplicate class org.slf4j.*`. Drop Ktor's slf4j-api
// since the shaded copy already satisfies the API references at
// runtime. Logging from Ktor is verbose-debug only and isn't load-bearing.
configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

// Verify that HelpTimeline.kt is in sync with data/features.json — i.e.
// running the codegen would not change the file. The pre-push git hook
// already catches drift before a tag push lands on Codeberg, but CI
// doesn't run hooks, so this Gradle task is the CI-side enforcement.
//
// Gated to release builds only:
//   - debug builds (dev loop) skip the check, so an in-progress
//     features.json edit doesn't break `./gradlew :app:installDebug`
//   - assembleRelease (used by release.yml + release-prebuilts.yml)
//     hard-fails if the maintainer pushed features.json without
//     running the codegen.
//
// Requires `python3` on PATH. CI runners (codeberg-medium, Debian-
// derived) ship with it; if you're building release locally on a
// machine without python3, the task fails with a clear error.
val verifyHelpTimeline = tasks.register("verifyHelpTimeline") {
    description = "Verifies app/.../HelpTimeline.kt is in sync with data/features.json."
    group = "verification"
    doLast {
        val repoRoot = rootProject.projectDir
        // Skip cleanly if python3 isn't on PATH — same posture as the
        // pre-push hook. CI runners have it; non-Linux maintainer boxes
        // mostly do too. If yours doesn't, you get a clear hint instead
        // of a confusing "command not found".
        val process = try {
            ProcessBuilder("python3", "tools/regenerate_help_timeline.py", "--check")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw GradleException(
                "verifyHelpTimeline: cannot start python3 — install Python 3 " +
                    "or pass -x verifyHelpTimeline to skip this check.\n" +
                    "Underlying error: ${e.message}",
            )
        }
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException(
                "verifyHelpTimeline failed:\n${output.trim()}\n\n" +
                    "Fix: python3 tools/regenerate_help_timeline.py — then commit the regenerated file.",
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyHelpTimeline)
}

dependencies {
    // Live barcode/QR scanner for capturing a card — pure ZXing, NO Play
    // Services (F-Droid-friendly, matches the app's AOSP stance).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.pebblekit)
    implementation(libs.prettytime.nlp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.zip4j)
    implementation(project(":speech"))
    implementation(project(":speech-whisper"))
    implementation(project(":wristotle-core"))
    // Generic sports-data library (composite-build substitution → ./sportskapi).
    // :app consumes the provider-neutral SportDataSource for SportHandler +
    // the seam impls; wristotle-core keeps it `implementation` so :app needs
    // its own declaration.
    implementation("com.lazydevs.sportskapi:sportskapi:0.1.0")
    // MCP client (phase A of AskAgent) — official SDK over Ktor's OkHttp engine.
    // Pure-package under app/.../mcp/; no separate module per the
    // module-vs-package check (single consumer, no NDK, no model lifecycle).
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // org.json is bundled with Android but absent from the empty android.jar
    // used by unit tests. Pulling the upstream JVM artifact lets the backup
    // codec tests exercise the real JSONObject behaviour (vs. the no-op stubs
    // that `unitTests.isReturnDefaultValues = true` would otherwise hand back).
    testImplementation(libs.org.json)
    // TodayRangeTest (and future tests of code lifted to :wristotle-core commonMain)
    // construct kotlinx-datetime instants for fixture inputs.
    testImplementation(libs.kotlinx.datetime)
    // Test-only: ZXing decodes the generated QR/Code128 matrices to prove they
    // round-trip (correctness + orientation). NOT shipped — encoding is pure
    // Kotlin (qrcode-kotlin for QR, codes/Code128 for 1D).
    testImplementation("com.google.zxing:core:3.5.3")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}