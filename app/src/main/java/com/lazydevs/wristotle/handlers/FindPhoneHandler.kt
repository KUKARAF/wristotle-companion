// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "FindPhoneHandler"

/**
 * Handles [Intent.FindPhone] — currently a stub that logs the request and
 * returns a friendly response. Future iterations will actually ring the
 * phone (needs MODIFY_AUDIO_SETTINGS + a MediaPlayer/Ringtone choice +
 * a stop-ringing UI surface on the watch).
 *
 * Intentional minimal scope per the Phase 3 plan — we want the routing /
 * learning machinery in flight first, then ship the ringer separately.
 */
class FindPhoneHandler : ActionHandler {

    override val tag: String = "find_phone"
    override val intent: Intent = Intent.FindPhone

    override suspend fun handle(result: IntentResult): String {
        Log.d(TAG, "find phone request: ${result.rawQuery}")
        return "Find phone request received"
    }
}