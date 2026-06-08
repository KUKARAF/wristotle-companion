// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.contacts.ContactsResolver
import com.lazydevs.wristotle.speech.nlu.contacts.ResolvedContact
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.telephony.Telephony
import com.lazydevs.wristotle.speech.nlu.telephony.TelephonyResult

/**
 * Handles [Intent.Call] — places a phone call via the [Telephony] seam
 * to the contact named in `slots[SlotKeys.Contact]`.
 *
 * Slot extractor (`CallSlots`) strips call/dial/phone/ring trigger
 * words + fillers and hands back the residue as the contact name. The
 * resolver preserves the prefix-preferred LIKE matching used today.
 *
 * R4 batch 5 — lifted from :app. The Android-specific
 * `TelecomManager.placeCall` call lives behind [Telephony.placeCall]
 * now; this handler is the same shape everywhere.
 */
class CallHandler(
    private val contacts: ContactsResolver,
    private val telephony: Telephony,
) : ActionHandler {

    override val tag: String = "call"
    override val intent: Intent = Intent.Call

    override suspend fun handle(result: IntentResult): String {
        if (!contacts.hasPermission()) return "Contacts permission not granted"
        if (!telephony.canPlaceCall()) return "Call permission not granted"

        val contactName = (result.slots[SlotKeys.Contact] as? String)?.trim().orEmpty()
        if (contactName.isEmpty()) return "No contact specified"

        // PebbleListenerService.enrichResolvedContact already looked
        // this name up before the confirm-gate; reuse that match.
        val contact = (result.slots[SlotKeys.ResolvedContact] as? ResolvedContact)
            ?: contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return when (val r = telephony.placeCall(contact.number)) {
            TelephonyResult.Ok -> "Calling ${contact.name}"
            TelephonyResult.PermissionDenied -> "Call permission not granted"
            TelephonyResult.Unsupported -> "Calls aren't supported on this device"
            is TelephonyResult.Failed -> "Could not call ${contact.name}"
        }
    }
}
