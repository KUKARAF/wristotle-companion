// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.AppLookup
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Handler-side resolution of the `app` slot: collapses [AppLookup] +
 * "no slot at all" into the four actions a handler actually needs:
 *
 *   - [Specific]   — user named an app we found; act on that package.
 *   - [Fallback]   — no body OR a generic media noun ("pause the song");
 *                    act on the currently-active session.
 *   - [NotFound]   — user named something we don't recognise; refuse to
 *                    silently substitute the active session.
 *   - [EmptyIndex] — user named something but the AppIndex hasn't been
 *                    populated yet. Distinct from NotFound so handlers
 *                    can prompt the user to run *Scan installed apps*
 *                    instead of just saying "couldn't find."
 *
 * Shared by MediaPlay / MediaPause / MediaNext / MediaPrevious so the
 * `when` branch lives in one place.
 */
internal sealed interface AppTarget {
    data class Specific(val packageId: String) : AppTarget
    data object Fallback : AppTarget
    data class NotFound(val spoken: String) : AppTarget
    data object EmptyIndex : AppTarget
}

/** Shared user-facing hint for the [AppTarget.EmptyIndex] case. */
internal const val EMPTY_INDEX_HINT =
    "App index is empty — open Settings → Learning → Installed apps and tap Scan."

internal suspend fun IntentResult.resolveAppTarget(appIndex: AppIndex): AppTarget {
    val appQuery = (slots[SlotKeys.App] as? String)?.trim()
    if (appQuery.isNullOrEmpty()) return AppTarget.Fallback
    if (appIndex.count() == 0) return AppTarget.EmptyIndex
    return when (val lookup = appIndex.lookup(appQuery)) {
        is AppLookup.Match -> AppTarget.Specific(lookup.packageId)
        AppLookup.Generic -> AppTarget.Fallback
        is AppLookup.NotFound -> AppTarget.NotFound(lookup.spoken)
    }
}