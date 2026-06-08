// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.apps

/**
 * Platform-agnostic surface for launching installed apps by package /
 * bundle id, and for reading their user-facing label. Android impl
 * wraps `PackageManager.getLaunchIntentForPackage` +
 * `getApplicationLabel`; an iOS impl would wrap `UIApplication.open(_:)`
 * with the app's URL scheme (Apple doesn't expose a generic
 * "launch app by bundle id" — discovery + association would have to
 * happen at registration time).
 *
 * R4 batch 7.
 */
interface AppLauncher {
    /**
     * Launch the app identified by [packageId]. Returns true if the
     * launch intent was dispatched (the app may still error inside its
     * own process; that's invisible to the caller).
     */
    fun launchApp(packageId: String): Boolean

    /**
     * Human-readable label for [packageId] — used in user-facing
     * response strings ("Opening Spotify"). Returns the [packageId]
     * itself when the platform has no better label.
     */
    fun packageLabel(packageId: String): String
}
