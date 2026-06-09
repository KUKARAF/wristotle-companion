// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.telephony

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.telephony.Telephony
import com.lazydevs.wristotle.speech.nlu.telephony.TelephonyResult
import com.lazydevs.wristotle.util.hasPermission

private const val TAG = "AndroidTelephony"

/**
 * Android impl of [Telephony]. Wraps `TelecomManager.placeCall` +
 * `SmsManager.sendTextMessage`. Permission checks happen at the
 * [canPlaceCall] / [canSendSms] gate so the handler can early-return
 * with a user-actionable error before the system layer is touched.
 */
class AndroidTelephony(context: Context) : Telephony {

    private val appContext = context.applicationContext

    override fun canPlaceCall(): Boolean =
        appContext.hasPermission(Manifest.permission.CALL_PHONE)

    override suspend fun placeCall(number: String): TelephonyResult {
        if (!canPlaceCall()) return TelephonyResult.PermissionDenied
        return runCatching {
            val telecom = appContext.getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            TelephonyResult.Ok
        }.getOrElse { e ->
            Log.w(TAG, "placeCall failed", e)
            TelephonyResult.Failed(e.message ?: "Call failed")
        }
    }

    override fun canSendSms(): Boolean =
        appContext.hasPermission(Manifest.permission.SEND_SMS)

    @Suppress("DEPRECATION") // SmsManager.getDefault is the long-standing API; getSystemService<SmsManager> is API 31+.
    override suspend fun sendSms(number: String, text: String): TelephonyResult {
        if (!canSendSms()) return TelephonyResult.PermissionDenied
        return runCatching {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            TelephonyResult.Ok
        }.getOrElse { e ->
            Log.w(TAG, "sendSms failed", e)
            TelephonyResult.Failed(e.message ?: "SMS send failed")
        }
    }
}
