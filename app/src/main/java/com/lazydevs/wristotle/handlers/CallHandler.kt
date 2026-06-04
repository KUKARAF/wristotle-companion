package com.lazydevs.wristotle.handlers

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.util.hasPermission

private const val TAG = "CallHandler"

/**
 * Handles [Intent.Call] — places a phone call via [TelecomManager] to the
 * contact named in `slots[SlotKeys.Contact]`. Requires READ_CONTACTS and CALL_PHONE
 * permissions.
 *
 * Slot extractor (`CallSlots`) strips call/dial/phone/ring trigger words +
 * fillers and hands back the residue as the contact name. ContactsRepository
 * preserves the prefix-preferred LIKE matching used today.
 */
class CallHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    override val tag: String = "call"
    override val intent: Intent = Intent.Call

    override suspend fun handle(result: IntentResult): String {
        if (!contacts.hasPermission()) return "Contacts permission not granted"
        if (!context.hasPermission(Manifest.permission.CALL_PHONE)) return "Call permission not granted"

        val contactName = (result.slots[SlotKeys.Contact] as? String)?.trim().orEmpty()
        if (contactName.isEmpty()) return "No contact specified"

        // PebbleListenerService.enrichResolvedContact already looked
        // this name up before the confirm-gate; reuse that match.
        val contact = (result.slots[SlotKeys.ResolvedContact] as? ContactsRepository.Contact)
            ?: contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.fromParts("tel", contact.number, null), Bundle())
            "Calling ${contact.name}"
        } catch (e: Exception) {
            Log.w(TAG, "placeCall failed for ${contact.name}", e)
            "Could not call ${contact.name}"
        }
    }
}
