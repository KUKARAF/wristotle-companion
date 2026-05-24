package com.lazydevs.wristotle.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * One entry per supported messaging app — used by [SendMessageHandler] to
 * resolve a spoken app name into the Android intent that opens its compose
 * screen pre-filled with contact + body.
 *
 * Each target carries its display name (for confirm prompts + chat replies),
 * the Android package id (for installed-check + intent scoping), the set of
 * spoken-name aliases the NLU layer might hand us (including common Whisper
 * mistranscriptions), and a builder for the actual intent.
 *
 * Targets are data — adding a new messaging app is one entry in
 * [MessagingTargets.ALL] plus a Phase 0 smoke test to verify its deep-link
 * scheme actually delivers a pre-filled compose. No new handler, no new
 * intent, no new slot extractor.
 *
 * Phase A2 will move the SMS app into this registry as well (today SMS is
 * handled by the separate [com.lazydevs.wristotle.speech.nlu.Intent.Sms] +
 * [com.lazydevs.wristotle.handlers.SmsHandler] for backward compatibility);
 * the registry shape is already compatible.
 */
data class MessagingTarget(
    val displayName: String,
    val packageId: String,
    /** Lowercased aliases the user might say. Always includes `displayName.lowercase()`. */
    val spokenAliases: Set<String>,
    /** Constructs an intent that opens the target with [phone] selected and [body] pre-filled. */
    val buildIntent: (phone: String, body: String) -> Intent,
)

/**
 * `true` if the target's package is installed on this device. Cheap; the
 * handler checks before firing so we can return a useful error instead of
 * launching an "app not found" picker dialog.
 */
fun MessagingTarget.isInstalled(context: Context): Boolean =
    try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageId, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
