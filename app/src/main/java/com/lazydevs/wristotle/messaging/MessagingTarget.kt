package com.lazydevs.wristotle.messaging

import android.content.Context
import android.content.pm.PackageManager

/**
 * One entry per supported messaging target — used by
 * [com.lazydevs.wristotle.handlers.SendMessageHandler] to translate a
 * spoken app name (plus contact + body slots) into a dispatched message.
 *
 * Phase A2 unified SMS into this registry. The shape supports two
 * delivery mechanisms behind the same handler call:
 *
 *  - **Intent-based** (WhatsApp / Telegram / Signal etc.) — `deliver`
 *    builds and fires an [android.content.Intent] that lands the user on
 *    a pre-filled compose screen; one tap to send. This is the only
 *    pathway sideloaded apps have, since these apps gate their service /
 *    MediaBrowser / SDK surfaces against unknown callers.
 *  - **Programmatic** (SMS) — `deliver` calls
 *    [android.telephony.SmsManager.sendTextMessage] directly with the
 *    `SEND_SMS` runtime permission; no user tap required. SMS is the one
 *    "messaging app" that has a system service exposing a real send API.
 *
 * Returning a string from [deliver] keeps the handler dumb: it doesn't
 * branch on which dispatch path was taken, just forwards the response
 * back over the watch chat.
 *
 * Adding a new target = one data-only entry in [MessagingTargets]. No
 * new handler, no new intent, no new slot extractor.
 */
data class MessagingTarget(
    val displayName: String,
    /**
     * Android package id, or empty string for system-service targets like
     * SMS where "is the app installed" doesn't apply. [isInstalled]
     * special-cases the empty value as always-true.
     */
    val packageId: String,
    /** Lowercased aliases the user might say. May be empty for the default fallback target. */
    val spokenAliases: Set<String>,
    /**
     * Sends the message and returns the user-facing response string
     * (e.g. *"Sent to Mom"*, *"Opened WhatsApp for Mom"*,
     * *"SMS permission not granted"*). Failure modes return a non-empty
     * string explaining the failure — never throw past the handler.
     */
    val deliver: suspend (context: Context, phone: String, body: String, contactName: String) -> String,
)

/**
 * `true` if the target's package is installed on this device, or if the
 * target is the system-service SMS sentinel (empty [packageId]).
 *
 * Used by the handler to short-circuit with a useful error before
 * attempting delivery — avoids "no activity found to handle this intent"
 * picker dialogs leaking through to the user.
 */
fun MessagingTarget.isInstalled(context: Context): Boolean {
    if (packageId.isEmpty()) return true
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageId, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
