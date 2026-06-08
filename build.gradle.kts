// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Register the KMP plugins at the root so submodule alias() calls
    // can resolve. AGP 9 rejects the legacy (com.android.library +
    // kotlin.multiplatform) stack — for KMP modules use the bespoke
    // android.kotlin.multiplatform.library plugin alongside the
    // kotlin.multiplatform plugin instead. iOS spike S1a — :wristotle-core
    // (formerly :speech-nlu) is the first KMP module in the build.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}