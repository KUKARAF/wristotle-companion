// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "PhoneLocation"

/**
 * Thin wrapper around [LocationManager] that returns the **last-known**
 * device location without requesting a fresh fix.
 *
 * Deliberately uses the AOSP [LocationManager] instead of Google Play
 * Services' `FusedLocationProviderClient` so the app stays sideload-friendly
 * (microG / de-Googled phones / non-GMS builds keep working).
 *
 * Used by [com.lazydevs.wristotle.handlers.WeatherHandler] for bare
 * *"what's the weather"* queries: the response should land sub-second, so
 * waiting on a fresh GPS fix isn't an option. A cached fix from any provider
 * (PASSIVE → NETWORK → GPS) is good enough for city-level weather.
 */
class PhoneLocation(private val context: Context) {

    /** True iff `ACCESS_COARSE_LOCATION` is held. The handler reads this
     *  separately from [lastKnown] so it can return a permission-specific
     *  hint when no location is available. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the freshest cached location across the platform providers, or
     * null when the permission isn't granted, no provider has anything
     * cached, or every cached fix is older than [maxAgeMs] (default 1 hour
     * — old enough to seed weather, new enough that you haven't crossed a
     * time zone since).
     *
     * Walks PASSIVE → NETWORK → GPS so it returns whatever the OS already
     * has without consuming battery to start a fresh fix. SecurityException
     * is caught defensively even though [hasPermission] is checked first,
     * because the user could revoke the grant between the check and the
     * call on Android 11+.
     */
    fun lastKnown(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Location? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val now = System.currentTimeMillis()
        var freshest: Location? = null
        for (provider in PROVIDERS) {
            val fix = try {
                @Suppress("MissingPermission") // checked above
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "lastKnown($provider) denied", e); null
            } catch (e: IllegalArgumentException) {
                // Some OEMs/versions don't expose PASSIVE_PROVIDER.
                null
            } ?: continue
            if (now - fix.time > maxAgeMs) continue
            if (freshest == null || fix.time > freshest.time) freshest = fix
        }
        return freshest
    }

    private companion object {
        // PASSIVE returns whatever any other app most recently received,
        // for free (no provider activation), so it's the right first stop.
        // NETWORK / GPS provide their own cached fixes as fallbacks.
        val PROVIDERS: List<String> = buildList {
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            // FUSED is Android 12+ and may be preferred on newer devices,
            // but it's optional — the three above already cover everything.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }

        const val DEFAULT_MAX_AGE_MS: Long = 60 * 60 * 1000L  // 1 hour
    }
}