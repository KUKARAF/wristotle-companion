// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

plugins {
    alias(libs.plugins.android.library)
}

// When -PskipNativeBuild=true, the externalNativeBuild blocks are
// skipped entirely and AGP packages whatever `.so` files already exist
// in src/main/jniLibs/arm64-v8a/. CI uses this with prebuilts fetched
// from the build-natives workflow so the release build doesn't have
// to recompile whisper.cpp + ggml on every tag push (which is what
// kept blowing through the medium runner's memory + time budget).
val skipNative: Boolean =
    providers.gradleProperty("skipNativeBuild").orNull?.toBoolean() == true

android {
    namespace = "com.lazydevs.wristotle.speech.whisper"
    compileSdk = 36
    // Pinned to a complete NDK install (28.2.13676358 is corrupted, missing
    // source.properties). Bump if/when 28.2 is reinstalled cleanly.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 24
        ndk {
            // Only ship arm64-v8a — all modern Pebble-companion-friendly phones.
            // Add x86_64 here if you need emulator support; armeabi-v7a if you need
            // to ship to 32-bit devices.
            abiFilters += "arm64-v8a"
        }
        if (!skipNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    // Reproducible-build flags so the .so stays byte-identical
                    // across rebuilds (required for F-Droid):
                    //   - `-ffile-prefix-map=…=.` strips absolute build paths
                    //     from embedded debug strings.
                    //   - `-Wl,--build-id=none` (passed in `arguments` below as a
                    //     linker flag) drops the per-build random build-id that
                    //     otherwise lands in every shared library.
                    cppFlags += "-ffile-prefix-map=${rootDir}=."
                    cppFlags += "-ffile-prefix-map=${projectDir}=."
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_ARM_NEON=ON",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none",
                        "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,--build-id=none",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,--build-id=none",
                    )
                }
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        logger.lifecycle(":speech-whisper using prebuilt natives from src/main/jniLibs/")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":speech"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}