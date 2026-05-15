package com.lazydevs.wristotle.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lazydevs.wristotle.phone.ContactsRepository

private const val TAG = "SmsHandler"

/**
 * Handles SMS queries in the form "[prefix] [contact] [message body]".
 *
 * Supported prefixes: "send message to", "send message", "text", "message".
 * The first word after the prefix is treated as the contact name; everything
 * after that is the message body. Requires READ_CONTACTS and SEND_SMS permissions.
 */
class SmsHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    // Ordered longest-first so the more specific prefixes are stripped correctly
    // (e.g. "send message to" must be checked before "send message").
    private val prefixes = listOf("send message to ", "send message ", "text ", "message ")

    override fun canHandle(query: String): Boolean = matchPrefix(query) != null

    override suspend fun handle(query: String): String {
        if (!contacts.hasPermission()) return "Contacts permission not granted"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return "SMS permission not granted"

        // Strip the matched prefix, then split on the first space to separate
        // the contact name (one word) from the message body (everything else).
        val prefix = matchPrefix(query) ?: return "Unrecognized SMS command"
        val rest = query.substring(prefix.length).trim()

        val spaceIdx = rest.indexOf(' ')
        if (spaceIdx < 0) return "No message body"

        val contactName = rest.substring(0, spaceIdx).trim()
        val body        = rest.substring(spaceIdx + 1).trim()
        if (body.isBlank()) return "No message body"

        val contact = contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(contact.number, null, body, null, null)
            "Sent to ${contact.name}"
        } catch (e: Exception) {
            Log.w(TAG, "sendTextMessage failed for ${contact.name}", e)
            "Could not send to ${contact.name}"
        }
    }

    /** Returns the prefix matched by [query], or null if no prefix matches. */
    private fun matchPrefix(query: String): String? {
        val lower = query.lowercase()
        return prefixes.firstOrNull { lower.startsWith(it) }
    }
}
