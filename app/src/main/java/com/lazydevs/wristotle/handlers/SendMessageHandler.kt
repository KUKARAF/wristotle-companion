// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.messaging.MessagingTargets
import com.lazydevs.wristotle.messaging.isInstalled
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.contactSlot
import com.lazydevs.wristotle.speech.nlu.stringSlot

/**
 * Handles [Intent.SendMessage] — the single voice path for sending a
 * message to a contact, regardless of which app delivers it.
 *
 * Two delivery shapes, both expressed uniformly through
 * [com.lazydevs.wristotle.messaging.MessagingTarget.deliver]:
 *  - **SMS** (default when no app is named) — programmatic send via
 *    [android.telephony.SmsManager.sendTextMessage], no user tap.
 *  - **Third-party messaging apps** (WhatsApp / Telegram / Signal) —
 *    assisted-send via ACTION_VIEW deep-link, opens compose pre-filled,
 *    user taps Send. Messaging apps gatekeep their MediaBrowser /
 *    Service / API surfaces against sideloaded callers — only
 *    Google-Assistant-signed apps can drive a programmatic send. The
 *    available pathway is the deep-link scheme.
 *
 * Slots required (from [SendMessageSlots]):
 *   - `app`     — display name of the target (`"SMS"`, `"WhatsApp"`,
 *                  `"Telegram"`, `"Signal"`)
 *   - `contact` — recipient name
 *   - `body`    — message text
 *
 * Failure modes the handler reports back over the watch chat:
 *   - "Don't know that messaging app" — `app` slot missing or unknown
 *   - "Can't send to <App> yet" — target's `enabled` flag is `false`
 *     (currently WhatsApp / Telegram / Signal — see MessagingTargets).
 *     Gates one-tap-only targets until we have an AccessibilityService
 *     to drive their Send button.
 *   - "<App> isn't installed" — registry has it but not on device
 *     (SMS is exempt — system service, always installed)
 *   - "Contacts permission not granted" — gate before lookup
 *   - "Contact not found: <name>" — ContactsRepository returns null
 *   - delivery-specific failures — surfaced verbatim by the target's
 *     `deliver` lambda ("SMS permission not granted", etc.)
 */
class SendMessageHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    override val tag: String = "send-message"
    override val intent: Intent = Intent.SendMessage

    override suspend fun handle(result: IntentResult): String {
        val appName = result.stringSlot(SlotKeys.App)
        if (appName.isEmpty()) return "Don't know that messaging app"

        val target = MessagingTargets.findByDisplayName(appName)
            ?: return "Don't know that messaging app: $appName"

        // Gate disabled targets before the installed-check so the error
        // names the right reason. WhatsApp / Telegram / Signal are
        // currently disabled because their deep-link only opens compose
        // — there's no programmatic send for sideloaded callers. See
        // MessagingTargets' comment block.
        if (!target.enabled) return "Can't send to ${target.displayName} yet"

        if (!target.isInstalled(context)) return "${target.displayName} isn't installed"

        if (!contacts.hasPermission()) return "Contacts permission not granted"

        val contactName = result.stringSlot(SlotKeys.Contact)
        val body = result.stringSlot(SlotKeys.Body)
        if (contactName.isEmpty()) return "No contact specified"
        if (body.isEmpty()) return "No message body"

        // Reuse the upstream resolution when present — the slot extractor
        // already validated this name against the Contacts provider.
        val contact = result.contactSlot()
            ?: contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return target.deliver(context, contact.number, body, contact.name)
    }
}