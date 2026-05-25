package com.lazydevs.wristotle.phone

/**
 * Pointer to a contact stored in an alias entry. Three fields together
 * make the alias robust to both runtime drift (contact gets renamed /
 * number changes / accounts re-aggregated) AND device migration
 * (backup → restore on a fresh device with new Contacts row IDs).
 *
 *  - [lookupKey] is Android's documented stable contact identifier
 *    (`ContactsContract.Contacts.LOOKUP_KEY`). `Contacts.lookupContact`
 *    can re-resolve it even when the underlying `_ID` shifts — the
 *    common case after a sync re-aggregation or contact-merge edit.
 *  - [nameSnapshot] / [numberSnapshot] are the display name and primary
 *    phone number captured AT ALIAS-CREATION TIME. The runtime resolve
 *    path doesn't use them on the happy path (current values come from
 *    Contacts), but they pull weight in two failure modes:
 *      1. Backup → restore on a new device: lookup key probably
 *         doesn't re-resolve. The restore relink pass (Phase C) tries
 *         exact-name then exact-number to find a matching contact and
 *         updates the alias to the new key.
 *      2. Contact deleted in Contacts: lookup key resolves to null,
 *         the alias is dead. The Settings card surfaces the snapshot
 *         (`"phrase" → John Smith (not found)`) so the user knows
 *         what they're pruning.
 */
data class ContactRef(
    val lookupKey: String,
    val nameSnapshot: String,
    val numberSnapshot: String,
)
