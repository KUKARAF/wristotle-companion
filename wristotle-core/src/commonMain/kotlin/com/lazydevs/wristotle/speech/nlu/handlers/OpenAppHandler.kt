// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.apps.AppCatalog
import com.lazydevs.wristotle.speech.nlu.apps.AppLauncher
import com.lazydevs.wristotle.speech.nlu.apps.AppLookup
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys

/**
 * Launches an installed app by spoken name ("open Spotify",
 * "launch Audible"). The `app` slot from `OpenAppSlots` carries the
 * spoken form; [AppCatalog] resolves it to a package id, [AppLauncher]
 * dispatches the launch.
 *
 * Three failure paths surfaced to the user, since each needs a
 * different fix:
 *   - empty slot          → user said just "open" (no body)
 *   - empty index         → user hasn't scanned installed apps yet
 *   - unresolved          → spoken name doesn't match anything
 *
 * R4 batch 7 — lifted from :app. The Android-side AppIndex implements
 * AppCatalog; AndroidAppLauncher wraps PackageManager.
 *
 * The user-visible hint when the catalog is empty lives in the host
 * app's localised resources (was a string constant in :app); since
 * commonMain can't read Android resources, the host passes it in as
 * [emptyIndexHint]. iOS impl wires its own equivalent.
 */
class OpenAppHandler(
    private val launcher: AppLauncher,
    private val appCatalog: AppCatalog,
    private val emptyIndexHint: String,
) : ActionHandler {

    override val tag = TAG
    override val intent = Intent.OpenApp

    override suspend fun handle(result: IntentResult): String {
        val name = (result.slots[SlotKeys.App] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return "No app specified"
        if (appCatalog.count() == 0) return emptyIndexHint
        val pkg = when (val lookup = appCatalog.lookup(name)) {
            is AppLookup.Match -> lookup.packageId
            // Generic + NotFound both surface as "couldn't find" —
            // OpenApp has no active-session fallback to defer to.
            AppLookup.Generic, is AppLookup.NotFound -> return "Couldn't find an app called $name"
        }
        return if (launcher.launchApp(pkg)) "Opening ${launcher.packageLabel(pkg)}"
        else "Couldn't launch ${launcher.packageLabel(pkg)}"
    }

    companion object {
        /** Persisted in ConversationEntry.handler. Referenced from the
         *  Conversation chip-routing code in `ui/` — keep in sync if
         *  you ever rename the tag (would require a data migration). */
        const val TAG = "open_app"
    }
}
