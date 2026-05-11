package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

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
     * The lookup uses a LIKE %query% filter so partial names work ("mom", "john").
     * Among multiple matches, a contact whose display name starts with [query]
     * (case-insensitive) is preferred over one that merely contains it.
     * The first phone number on record is used when a contact has several.
     */
    fun findContact(query: String): Contact? {
        val nameCursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return null

        var contactId: String? = null
        var contactName: String? = null

        nameCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val id   = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                // Prefer a prefix match ("mom" → "Mom") over a substring match ("Tommy").
                if (contactId == null || name.lowercase().startsWith(query.lowercase())) {
                    contactId   = id
                    contactName = name
                    if (name.lowercase().startsWith(query.lowercase())) break
                }
            }
        }

        val id   = contactId   ?: return null
        val name = contactName ?: return null

        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        ) ?: return null

        val number = phoneCursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null

        return Contact(name, number)
    }
}
