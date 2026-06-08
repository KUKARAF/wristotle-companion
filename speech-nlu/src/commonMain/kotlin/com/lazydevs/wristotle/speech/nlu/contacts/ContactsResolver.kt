// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.contacts

/**
 * A contact looked up by name + (optionally) refined via the alias
 * store. Slot extractors put this into the slot map under
 * `SlotKeys.ResolvedContact` so the handler doesn't have to re-look-up.
 *
 * R3 batch 2 — multiplatform replacement for the Android-only
 * `ContactsRepository.Contact`. The Android-side keeps a typealias
 * `Contact = ResolvedContact` so existing handlers (CallHandler,
 * SendMessageHandler, PebbleListenerService.enrichResolvedContact)
 * compile without source changes.
 */
data class ResolvedContact(
    val name: String,
    val number: String,
)

/**
 * Multiplatform contact lookup seam. Android impl wraps the existing
 * `ContactsRepository.findContact` (ContentProvider query). iOS impl
 * will wrap CNContactStore when the port lands.
 *
 * Return null when no contact matches — slot extractors interpret
 * that as "try the next candidate" or "fall back to the literal
 * spoken name".
 */
interface ContactsResolver {
    suspend fun findContact(query: String): ResolvedContact?

    /**
     * Whether the platform permission gate currently allows reading
     * contacts. Handlers check this BEFORE [findContact] to surface a
     * user-actionable error ("Contacts permission not granted") instead
     * of a generic not-found.
     */
    fun hasPermission(): Boolean
}
