// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.findphone.FindPhoneRinger
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler

/**
 * Handles [Intent.FindPhone] (the spoken "find my phone") by ringing the phone
 * on the **alarm** stream via [FindPhoneRinger] — audible even when the media
 * volume is 0. The watch-local "find my phone" command reaches the same ringer
 * through the `find_phone` message key (see PebbleListenerService); this handler
 * covers the voice path.
 */
class FindPhoneHandler(private val context: Context) : ActionHandler {

    override val tag: String = "find_phone"
    override val intent: Intent = Intent.FindPhone

    override suspend fun handle(result: IntentResult): String {
        FindPhoneRinger.start(context)
        return "Ringing your phone."
    }
}
