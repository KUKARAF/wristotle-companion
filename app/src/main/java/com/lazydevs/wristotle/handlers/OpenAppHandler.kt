package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.Intent as AndroidIntent
import android.util.Log
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "OpenAppHandler"

/**
 * Launches an installed app by spoken name ("open Spotify",
 * "launch Audible"). The `app` slot from [com.lazydevs.wristotle.nlu.slots.OpenAppSlots]
 * carries the raw spoken form; resolution to a real package id happens
 * here via [AppIndex.find].
 *
 * "App not found in the index" branches:
 *   - the user hasn't tapped "Scan installed apps" yet → AppIndex is
 *     empty (count() == 0) → response nudges them to do that
 *   - the index is populated but no label matches → simple "Couldn't
 *     find <name>" so the watch shows something useful
 */
class OpenAppHandler(
    private val context: Context,
    private val appIndex: AppIndex,
) : ActionHandler {

    override val tag = "open_app"
    override val intent = Intent.OpenApp

    override suspend fun handle(result: IntentResult): String {
        val name = (result.slots["app"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return "No app specified"
        if (appIndex.count() == 0) {
            return "App index is empty — open Settings and tap Scan installed apps."
        }
        val pkg = appIndex.find(name) ?: return "Couldn't find an app called $name"
        return launch(pkg, displayName = name)
    }

    private fun launch(packageId: String, displayName: String): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageId)
        if (launchIntent == null) {
            Log.w(TAG, "no launch intent for $packageId")
            return "Couldn't launch $displayName"
        }
        launchIntent.addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            "Opening $displayName"
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity($packageId) failed", t)
            "Couldn't launch $displayName"
        }
    }
}
