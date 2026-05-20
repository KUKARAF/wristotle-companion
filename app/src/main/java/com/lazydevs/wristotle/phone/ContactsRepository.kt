package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only access to the device contacts database. */
class ContactsRepository(private val context: Context) {

    /** Resolved contact used by call and SMS handlers. */
    data class Contact(val name: String, val number: String)

    /** Returns true if READ_CONTACTS permission has been granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns the best-matching contact for [query], or null if none is found.
     *
     * The lookup uses a LIKE %query% filter so partial names work ("mom",
     * "john"), then scores every candidate with [contactMatchScore] and takes
     * the highest — but only if it clears [CONTACT_MATCH_FLOOR]. This rejects
     * the case where a garbled query is only a mid-word substring of an
     * unrelated name (e.g. "al" → "Michael"): rather than silently call/text
     * the wrong person, return null so the handler reports "Contact not found".
     * The first phone number on record is used when a contact has several.
     *
     * Runs on [Dispatchers.IO] — ContentResolver queries are blocking.
     */
    suspend fun findContact(query: String): Contact? = withContext(Dispatchers.IO) {
        val nameCursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return@withContext null

        var bestId: String? = null
        var bestName: String? = null
        var bestScore = 0f

        nameCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val id   = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val score = contactMatchScore(query, name)
                if (score > bestScore) {
                    bestScore = score
                    bestId    = id
                    bestName  = name
                }
            }
        }

        if (bestScore < CONTACT_MATCH_FLOOR) return@withContext null
        val id   = bestId   ?: return@withContext null
        val name = bestName ?: return@withContext null

        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        ) ?: return@withContext null

        val number = phoneCursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@withContext null

        Contact(name, number)
    }
}
