package com.lazydevs.wristotle.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.lazydevs.wristotle.logging.WristotleLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AppIndexer"

/**
 * Snapshots the device's launcher apps into the [InstalledAppDao].
 *
 * Triggered on demand from the Settings card — never on every voice
 * command, so the `PackageManager.queryIntentActivities` enumeration
 * (which can take 50–300 ms on a phone with many apps) is amortised.
 *
 * Requires the `QUERY_ALL_PACKAGES` permission declared in the
 * manifest; without it Android 11+ returns a filtered list dominated
 * by the few apps we've already interacted with.
 */
class AppIndexer(
    private val context: Context,
    private val dao: InstalledAppDao,
) {

    /**
     * Re-enumerate launcher apps and replace the index. Returns the
     * number of apps written. Safe to call from any dispatcher — does
     * its own IO hop.
     */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = try {
            // MATCH_ALL is the documented "no filtering" flag; on Android
            // 11+ this still hides packages we're not allowed to see, but
            // with QUERY_ALL_PACKAGES granted we get everything.
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivities failed", t)
            return@withContext 0
        }
        val now = System.currentTimeMillis()
        // Dedupe by package: an app with multiple launcher activities
        // (rare but it happens — e.g. settings-style apps) becomes one
        // row keyed on package id. First wins, which gives us the
        // primary launcher activity's label.
        val seen = mutableSetOf<String>()
        val rows = resolveInfos.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (!seen.add(pkg)) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            InstalledApp(
                packageId = pkg,
                label = label,
                normalizedLabel = normalizeForIndex(label),
                normalizedPackage = normalizeForPackageId(pkg),
                lastScannedAtMs = now,
            )
        }
        dao.replaceAll(rows)
        Log.d(TAG, "indexed ${rows.size} launcher apps")
        rows.size
    }
}
