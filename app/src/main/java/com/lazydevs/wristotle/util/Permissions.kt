package com.lazydevs.wristotle.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Compact wrapper over [ContextCompat.checkSelfPermission] so callers
 * read `context.hasPermission(Manifest.permission.CALL_PHONE)` instead
 * of the 3-line boilerplate. No behavioural change — same permission
 * check, just one expression.
 */
internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
