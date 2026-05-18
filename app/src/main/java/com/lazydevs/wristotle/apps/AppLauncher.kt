package com.lazydevs.wristotle.apps

import android.content.Context
import android.content.Intent
import com.lazydevs.wristotle.logging.WristotleLog as Log

private const val TAG = "AppLauncher"

/**
 * Shared launcher used by both `OpenAppHandler` (pure launch) and
 * `MediaPlayHandler.launchAndPlay` (launch then start playback).
 * Returns false when the package has no launcher activity, or when
 * `startActivity` is refused (background-launch restrictions, missing
 * permission, etc.). All failure paths log and the caller decides
 * how to phrase the error to the user.
 */
internal fun launchApp(context: Context, packageId: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageId)
    if (intent == null) {
        Log.w(TAG, "no launch intent for $packageId")
        return false
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "startActivity($packageId) failed", t)
        false
    }
}

/**
 * User-facing display name for [packageId]. Falls back to the package
 * id when PackageManager can't resolve a label (uninstalled mid-flight,
 * disabled, etc.) so the response string is never blank.
 */
internal fun packageLabel(context: Context, packageId: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageId, 0)).toString()
} catch (t: Throwable) {
    packageId
}
