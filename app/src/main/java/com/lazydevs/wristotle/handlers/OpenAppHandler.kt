package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.AppLookup
import com.lazydevs.wristotle.apps.launchApp
import com.lazydevs.wristotle.apps.packageLabel
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Launches an installed app by spoken name ("open Spotify",
 * "launch Audible"). The `app` slot from [com.lazydevs.wristotle.nlu.slots.OpenAppSlots]
 * carries the spoken form; [AppIndex] resolves it to a package id.
 *
 * Three failure paths surfaced to the user, since each needs a
 * different fix:
 *   - empty slot          → user said just "open" (no body)
 *   - empty index         → user hasn't scanned installed apps yet
 *   - unresolved          → spoken name doesn't match anything
 */
class OpenAppHandler(
    private val context: Context,
    private val appIndex: AppIndex,
) : ActionHandler {

    override val tag = TAG
    override val intent = Intent.OpenApp

    override suspend fun handle(result: IntentResult): String {
        val name = (result.slots["app"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return "No app specified"
        if (appIndex.count() == 0) {
            return EMPTY_INDEX_HINT
        }
        val pkg = when (val lookup = appIndex.lookup(name)) {
            is AppLookup.Match -> lookup.packageId
            // Generic and NotFound both surface as "couldn't find" —
            // OpenApp has no active-session fallback to defer to.
            AppLookup.Generic, is AppLookup.NotFound -> return "Couldn't find an app called $name"
        }
        return if (launchApp(context, pkg)) "Opening ${packageLabel(context, pkg)}"
        else "Couldn't launch ${packageLabel(context, pkg)}"
    }

    companion object {
        /** Persisted in ConversationEntry.handler. Referenced from the
         *  Conversation chip-routing code in `ui/` — keep in sync if
         *  you ever rename the tag (would require a data migration). */
        const val TAG = "open_app"
    }
}
