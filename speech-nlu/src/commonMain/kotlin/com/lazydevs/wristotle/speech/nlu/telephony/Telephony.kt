// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.telephony

/**
 * Platform-agnostic surface for placing phone calls + sending SMS.
 * Android impl wraps `TelecomManager` + `SmsManager`. iOS has no
 * equivalent for programmatic SMS send, and `tel:` URLs for calls
 * still open the dialer rather than dialling silently — an iOS impl
 * would have to surface that as a `tel:` URL handoff or
 * [TelephonyResult.Failed].
 *
 * The [canPlaceCall] / [canSendSms] capability checks let handlers
 * fail fast with a user-friendly message ("Call permission not
 * granted", "SMS isn't supported on this device") before attempting
 * the action.
 *
 * R4 batch 5 — the formal seam. CallHandler now consumes this; the
 * SMS branch of MessagingTarget.deliver continues to call SmsManager
 * directly for now (it would gain little from going through this
 * interface until iOS port begins).
 */
interface Telephony {
    /** Can this device place phone calls (permission + telephony service)? */
    fun canPlaceCall(): Boolean

    /** Place a call to the dial string [number] (E.164 preferred). */
    suspend fun placeCall(number: String): TelephonyResult

    /** Can this device send SMS programmatically? */
    fun canSendSms(): Boolean

    /** Send an SMS to [number] with body [text]. */
    suspend fun sendSms(number: String, text: String): TelephonyResult
}

/**
 * Result of a [Telephony] action. Distinguishes "user / platform won't
 * let us" from "we tried and it failed" so handlers can route the right
 * copy back to the watch chat.
 */
sealed interface TelephonyResult {
    data object Ok : TelephonyResult

    /** The platform's permission gate refused; user-actionable. */
    data object PermissionDenied : TelephonyResult

    /** The platform doesn't support this op (iOS programmatic SMS, etc.). */
    data object Unsupported : TelephonyResult

    /** Tried, system layer failed. [message] is the platform's error string. */
    data class Failed(val message: String) : TelephonyResult
}
