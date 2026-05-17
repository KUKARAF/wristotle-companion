package com.lazydevs.wristotle.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lazydevs.wristotle.phone.ContactsRepository

private const val TAG = "CallHandler"

/**
 * Handles "call [name]" and "dial [name]" queries by placing a phone call
 * via [TelecomManager]. Requires READ_CONTACTS and CALL_PHONE permissions.
 */
class CallHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    override val tag: String = "call"

    override fun canHandle(query: String): Boolean {
        val lower = query.lowercase()
        return lower.startsWith("call ") || lower.startsWith("dial ")
    }

    override suspend fun handle(query: String): String {
        if (!contacts.hasPermission()) return "Contacts permission not granted"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) return "Call permission not granted"

        // Everything after the first word ("call" / "dial") is the contact name.
        val contactName = query.substringAfter(" ").trim()
        val contact = contacts.findContact(contactName)
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
