// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.*

import com.lazydevs.wristotle.phone.ContactsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageSlotsTest {

    private fun extractor(knownContacts: Set<String> = emptySet()) =
        SendMessageSlots(findContact = { query ->
            if (query.lowercase() in knownContacts.map(String::lowercase))
                ContactsRepository.Contact(name = query, number = "555-0100")
            else null
        })

    private fun extract(query: String, contacts: Set<String> = setOf("mom", "dad", "alex", "john smith")): Map<String, Any> =
        runBlocking { extractor(contacts).extract(query) }

    // ── App-first phrasing ─────────────────────────────────────────────

    @Test fun `whatsapp app-first with single-word contact`() {
        val s = extract("WhatsApp mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `telegram app-first with single-word contact`() {
        val s = extract("Telegram dad I'll be late")
        assertEquals("Telegram", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("i'll be late", s["body"])
    }

    @Test fun `signal app-first with single-word contact`() {
        val s = extract("Signal alex meeting moved")
        assertEquals("Signal", s["app"])
        assertEquals("alex", s["contact"])
        assertEquals("meeting moved", s["body"])
    }

    @Test fun `multi-word contact resolves greedily`() {
        // Without greedy lookup, "WhatsApp John Smith hi" would pick
        // contact="John", body="Smith hi". The greedy walk catches the
        // 2-word prefix when ContactsRepository has it.
        val s = extract("WhatsApp John Smith hi", setOf("john smith"))
        assertEquals("WhatsApp", s["app"])
        assertEquals("john smith", s["contact"])
        assertEquals("hi", s["body"])
    }

    @Test fun `whisper variant whats app maps to WhatsApp`() {
        // Whisper renders "WhatsApp" with a space about half the time.
        val s = extract("whats app mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    // ── Verb + "on <app>" phrasing ─────────────────────────────────────

    @Test fun `text contact on whatsapp body`() {
        val s = extract("text mom on whatsapp on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `message contact on telegram body`() {
        val s = extract("message dad on telegram see you soon")
        assertEquals("Telegram", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("see you soon", s["body"])
    }

    @Test fun `send a whatsapp message to contact body`() {
        val s = extract("send a whatsapp message to mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `send a signal message to contact body`() {
        val s = extract("send a signal message to alex meeting moved")
        assertEquals("Signal", s["app"])
        assertEquals("alex", s["contact"])
        assertEquals("meeting moved", s["body"])
    }

    // ── SMS fallback (Phase A2) ────────────────────────────────────────
    // Bare-verb shape with no app named falls back to the SMS target.

    @Test fun `text contact body defaults to SMS`() {
        val s = extract("text mom hi")
        assertEquals("SMS", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("hi", s["body"])
    }

    @Test fun `send a message to contact saying body defaults to SMS`() {
        // SmsSlots' conjunction split runs inside the fallback.
        val s = extract("send a message to mom saying running late")
        assertEquals("SMS", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("running late", s["body"])
    }

    @Test fun `tell contact body defaults to SMS`() {
        val s = extract("tell dad the meeting moved")
        assertEquals("SMS", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("the meeting moved", s["body"])
    }

    @Test fun `message multi-word contact defaults to SMS with greedy lookup`() {
        val s = extract("message John Smith about the demo")
        assertEquals("SMS", s["app"])
        assertEquals("john smith", s["contact"])
        assertEquals("about the demo", s["body"])
    }

    /**
     * Regression: the SMS_LIKE_CONJUNCTIONS regex includes "that" so
     * shapes like "text dad that I'll be late" work. But "that" appears
     * naturally in long dictated bodies — *"text John we tested the
     * package that we built"* used to greedy-split there and call
     * findContact("john we tested the package"), which obviously
     * misses. The conjunction split now requires the contact-half to
     * actually resolve before it's accepted, so this falls through to
     * the multi-word loop and finds "john" at n=1.
     */
    @Test fun `text contact with that inside body falls through to multi-word lookup`() {
        val s = extract(
            "text john we tested the package that we built",
            contacts = setOf("john"),
        )
        assertEquals("SMS", s["app"])
        assertEquals("john", s["contact"])
        assertEquals("we tested the package that we built", s["body"])
    }

    @Test fun `text contact that body still works when contact resolves`() {
        // The classic "text dad that I'll be late" shape still uses
        // the conjunction split because the contact half ("dad")
        // resolves to a real contact.
        val s = extract("text dad that I'll be late")
        assertEquals("SMS", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("i'll be late", s["body"])
    }

    /**
     * The slot extractor's findContact lookup is also stashed in
     * SlotKeys.ResolvedContact so the handler + ConfirmSummaryBuilder
     * + PebbleListenerService.enrichResolvedContact don't have to
     * re-query the Contacts provider. Saves up to 2 cursor queries
     * per SendMessage dictation.
     */
    @Test fun `multi-word lookup stashes resolved contact in slots`() {
        val s = extract("text mom on my way")
        val resolved = s["resolvedContact"] as? ContactsRepository.Contact
        assertEquals("mom", resolved?.name)
        assertEquals("555-0100", resolved?.number)
    }

    @Test fun `conjunction split stashes resolved contact in slots`() {
        val s = extract("text dad saying running late")
        val resolved = s["resolvedContact"] as? ContactsRepository.Contact
        assertEquals("dad", resolved?.name)
    }

    @Test fun `space-split fallback does not stash resolvedContact`() {
        // No known contact matches anywhere → the space-split fallback
        // returns the raw first-word as the contact name, unverified.
        // ResolvedContact is intentionally absent so the downstream
        // pass can decide whether to do its own lookup.
        val s = extract("text unknown person whatever", contacts = emptySet())
        assertEquals(null, s["resolvedContact"])
    }

    @Test fun `send sms to contact defaults to SMS`() {
        val s = extract("send sms to alex meeting at five")
        assertEquals("SMS", s["app"])
        assertEquals("alex", s["contact"])
        assertEquals("meeting at five", s["body"])
    }

    // ── Failure modes ──────────────────────────────────────────────────

    @Test fun `empty query returns empty`() {
        val s = extract("")
        assertTrue(s.isEmpty())
    }

    @Test fun `app-first without body returns contact-only against same app`() {
        // "WhatsApp mom" alone — no body. Should commit to WhatsApp
        // (the user named it on purpose), not fall through to SMS.
        // The handler will surface "No message body" against WhatsApp.
        val s = extract("WhatsApp mom")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertNull("body should be absent", s["body"])
    }

    // ── Whisper-comma after verb normalises ────────────────────────────

    @Test fun `comma after WhatsApp is normalised`() {
        val s = extract("WhatsApp, mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `comma after text verb is normalised in SMS fallback`() {
        // Phase A1.5's comma-strip fix applies via SmsSlots delegation.
        val s = extract("text, mom hi")
        assertEquals("SMS", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("hi", s["body"])
    }
}