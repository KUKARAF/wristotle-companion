// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.phone.ContactAliasStore
import com.lazydevs.wristotle.phone.ContactRef
import com.lazydevs.wristotle.phone.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One alias for the list UI: the spoken phrase + the contact it points
 * at. [foundInContacts] surfaces the dead-link UX — when the alias's
 * lookup key no longer resolves to a contact (deletion, fresh device
 * after restore), [displayName] falls back to the snapshot and the row
 * is annotated as "(not found)" so the user can prune.
 */
data class ContactAliasRow(
    val phrase: String,
    val ref: ContactRef,
    val displayName: String,
    val number: String,
    val foundInContacts: Boolean,
)

/**
 * One candidate row in the add-form's Contacts picker dropdown. The
 * picker queries the Contacts provider as the user types and surfaces
 * a small set of matches — selecting a row stashes the
 * `(lookupKey, displayName, number)` triplet that the Save button
 * commits as a [ContactRef] via [ContactAliasStore.put].
 */
data class PickableContact(
    val lookupKey: String,
    val displayName: String,
    val number: String,
)

/**
 * State for the "Contact aliases" Settings card. Lists current aliases
 * (resolving each [ContactRef.lookupKey] to its current display name +
 * number via [ContactsRepository]) and exposes a search-driven
 * Contacts picker for the add form. Add/remove writes go through
 * [ContactAliasStore] and trigger a [refresh].
 *
 * Unlike [AppAliasesViewModel], there's no installed-list to enumerate
 * up front — Contacts can be thousands of rows. The picker queries
 * lazily on each keystroke; the UI debounces by only invoking
 * [searchContacts] when the user has typed something.
 */
class ContactAliasesViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ContactAliasStore =
        (app as WristotleApplication).contactAliasStore
    private val contacts = ContactsRepository(app, store)

    private val _aliases = MutableStateFlow<List<ContactAliasRow>>(emptyList())
    val aliases: StateFlow<List<ContactAliasRow>> = _aliases

    /** True iff READ_CONTACTS is granted. Add form should not show the
     *  picker without it (and resolve falls back to snapshots silently). */
    val hasContactsPermission: Boolean get() = contacts.hasPermission()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                store.all().map { (phrase, ref) ->
                    val live = resolveLive(ref.lookupKey)
                    if (live != null) {
                        ContactAliasRow(
                            phrase = phrase,
                            ref = ref,
                            displayName = live.first,
                            number = live.second,
                            foundInContacts = true,
                        )
                    } else {
                        ContactAliasRow(
                            phrase = phrase,
                            ref = ref,
                            displayName = ref.nameSnapshot,
                            number = ref.numberSnapshot,
                            foundInContacts = false,
                        )
                    }
                }
            }
            _aliases.value = rows
        }
    }

    fun add(phrase: String, pick: PickableContact) {
        store.put(
            phrase,
            ContactRef(
                lookupKey = pick.lookupKey,
                nameSnapshot = pick.displayName,
                numberSnapshot = pick.number,
            ),
        )
        refresh()
    }

    fun remove(phrase: String) {
        store.remove(phrase)
        refresh()
    }

    /**
     * Check whether the given alias phrase already matches a Contacts
     * row via the regular score-floor matcher (no alias involved). Used
     * by the add form to surface a "this phrase already matches X —
     * alias will override" warning so users don't accidentally
     * supersede an existing contact's literal name.
     *
     * Returns the conflicting contact's display name, or null if no
     * conflict (phrase wouldn't have meant anyone before the alias is
     * added). Mirrors what the dispatch path would do for the same
     * spoken value.
     */
    suspend fun conflictingContact(phrase: String): String? {
        if (phrase.isBlank() || !hasContactsPermission) return null
        return contacts.findInContacts(phrase)?.name
    }

    /**
     * Search Contacts for the picker dropdown. Returns up to
     * [MAX_PICKER_RESULTS] best matches sorted alphabetically.
     * Each row carries the `lookupKey` so the eventual save persists
     * the stable identifier (not the volatile `_ID`).
     *
     * Returns empty if [query] is blank or READ_CONTACTS is not
     * granted — the picker should already be hidden in that case
     * but defending against the race is cheap.
     */
    suspend fun searchContacts(query: String): List<PickableContact> {
        if (query.isBlank() || !hasContactsPermission) return emptyList()
        return withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $MAX_PICKER_RESULTS",
            ) ?: return@withContext emptyList()

            val out = mutableListOf<PickableContact>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val lookupKey = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    val number = primaryNumberFor(id) ?: continue
                    out += PickableContact(lookupKey, name, number)
                }
            }
            out
        }
    }

    private fun primaryNumberFor(contactId: String): String? {
        val cursor = getApplication<Application>().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        ) ?: return null
        return cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /**
     * Re-resolve a lookup key to its current display name + primary
     * number. Mirrors [ContactsRepository.resolveByLookupKey] but
     * inline here so the VM can call it without exposing private API
     * on the repo. Returns null on dead key.
     */
    private fun resolveLive(lookupKey: String): Pair<String, String>? {
        val resolver = getApplication<Application>().contentResolver
        val lookupUri = ContactsContract.Contacts.getLookupUri(0L, lookupKey)
            ?: return null
        val resolvedUri = ContactsContract.Contacts.lookupContact(resolver, lookupUri)
            ?: return null

        val nameCursor = resolver.query(
            resolvedUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        ) ?: return null
        var id: String? = null
        var name: String? = null
        nameCursor.use { c ->
            if (c.moveToFirst()) {
                id = c.getString(0)
                name = c.getString(1)
            }
        }
        val contactId = id ?: return null
        val displayName = name ?: return null
        val number = primaryNumberFor(contactId) ?: return null
        return displayName to number
    }

    private companion object {
        // Bounded to keep the dropdown navigable; the user can refine
        // by typing more. Matches the App Aliases dropdown UX where
        // the entire installed-apps list is the dataset.
        const val MAX_PICKER_RESULTS = 20
    }
}