package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only access to the device contacts database. */
class ContactsRepository(
    private val context: Context,
    private val aliasStore: ContactAliasStore = ContactAliasStore(context),
) {

    /** Resolved contact used by call and SMS handlers. */
    data class Contact(val name: String, val number: String)

    /** Returns true if READ_CONTACTS permission has been granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns the best-matching contact for [query], or null if none is found.
     *
     * Consults [aliasStore] first: a user-defined alias is an explicit
     * choice that beats the LIKE matcher (the alias mechanism's whole
     * point is fixing Whisper mishearings + nicknames that don't appear
     * as substrings in any Contacts row). On an alias hit, the stored
     * `lookupKey` is re-resolved via the Contacts provider so the user
     * gets the contact's CURRENT name + number — see [resolveByLookupKey]
     * for what happens when the key is dead.
     *
     * Falls back to a LIKE %query% filter, scoring every candidate with
     * [contactMatchScore] and taking the highest — but only if it clears
     * [CONTACT_MATCH_FLOOR]. This rejects the case where a garbled query
     * is only a mid-word substring of an unrelated name (e.g. "al" →
     * "Michael"): rather than silently call/text the wrong person,
     * return null so the handler reports "Contact not found".
     * The first phone number on record is used when a contact has several.
     *
     * Runs on [Dispatchers.IO] — ContentResolver queries are blocking.
     */
    suspend fun findContact(query: String): Contact? = withContext(Dispatchers.IO) {
        aliasStore.resolve(normalizePhrase(query))?.let { ref ->
            resolveByLookupKey(ref)?.let { return@withContext it }
            // Alias matched but the lookup key didn't resolve — contact
            // was deleted (or this is a backup that landed on a fresh
            // Contacts DB before the relink pass ran). Fall through to
            // the LIKE matcher; that probably also fails (the alias
            // phrase wouldn't be in Contacts — that's why it existed),
            // so the caller surfaces a not-found state. The Settings
            // card flags the dead alias so the user can prune.
        }
        findInProvider(query)
    }

    /**
     * Provider-only lookup, bypassing the alias store. Same matcher
     * `findContact` uses on alias miss — exposed publicly so the
     * Settings card can ask "would this phrase already mean someone?"
     * when the user is creating an alias, without involving aliases
     * (which by definition haven't been saved yet).
     *
     * Returns null when the query doesn't clear [CONTACT_MATCH_FLOOR],
     * same as `findContact` — the "(none of your contacts come close)"
     * outcome.
     */
    suspend fun findInContacts(query: String): Contact? =
        withContext(Dispatchers.IO) { findInProvider(query) }

    /** Single implementation of the LIKE %query% + score-floor matcher.
     *  Must be called from an IO dispatcher. */
    private fun findInProvider(query: String): Contact? {
        val nameCursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return null

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

        if (bestScore < CONTACT_MATCH_FLOOR) return null
        val id   = bestId   ?: return null
        val name = bestName ?: return null

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

    /**
     * Re-resolves a [ContactRef.lookupKey] to the contact's CURRENT
     * display name + primary number via the Contacts provider. Returns
     * null when the lookup key no longer points at a contact — deletion,
     * un-aggregation, or (most often after a backup landed on a new
     * device) a fresh Contacts DB with completely different keys.
     *
     * Uses the documented `Contacts.getLookupUri` +
     * `Contacts.lookupContact` pair so the framework handles the
     * lookup-key → current row-id translation; we don't need to track
     * the volatile `_ID` ourselves. The caller already holds the IO
     * dispatcher (this is only invoked from [findContact]).
     */
    private fun resolveByLookupKey(ref: ContactRef): Contact? {
        // Row-ID is intentionally 0 — `lookupContact` re-derives it
        // from the lookup key. The two-arg form is what the docs
        // recommend when the caller only has the key on hand.
        val lookupUri = ContactsContract.Contacts.getLookupUri(0L, ref.lookupKey)
            ?: return null
        val resolvedUri = ContactsContract.Contacts.lookupContact(
            context.contentResolver, lookupUri,
        ) ?: return null

        val nameCursor = context.contentResolver.query(
            resolvedUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        ) ?: return null

        var id: String? = null
        var name: String? = null
        nameCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                id = cursor.getString(0)
                name = cursor.getString(1)
            }
        }
        val contactId = id ?: return null
        val displayName = name ?: return null

        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        ) ?: return null

        val number = phoneCursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null

        return Contact(displayName, number)
    }
}
