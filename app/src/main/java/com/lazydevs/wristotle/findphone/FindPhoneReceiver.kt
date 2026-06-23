// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.findphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Stops the [FindPhoneRinger] when the user taps **Stop** on the find-my-phone
 * notification. Registered in the manifest (exported=false — internal only).
 */
class FindPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FindPhoneRinger.ACTION_STOP) {
            FindPhoneRinger.stop(context)
        }
    }
}
