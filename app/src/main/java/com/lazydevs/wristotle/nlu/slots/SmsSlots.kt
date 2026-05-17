package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Sms]:
 *   - `contact` — recipient name (possibly multi-word, e.g. "John Smith")
 *   - `body`    — message text
 *
 * Strategy:
 *  1. Strip the longest matching prefix verb ("send a message to ", "text ",
 *     "tell ", "message ", "send sms to ", …).
 *  2. If a "saying"-style conjunction is present (`that` / `saying` /
 *     `telling them`), split there — left is contact, right is body.
 *  3. Else greedy contact-name lookup: try the first N words (N from 3
 *     down to 1) against [ContactsRepository.findContact]; the longest
 *     prefix that resolves to a real contact wins. Remainder is the body.
 *  4. Else fall back to a single-space split (today's behaviour) so the
 *     handler still has *something* to try.
 *
 * (3) fixes the long-standing bug where "text john smith hi" sent to
 * contact "john" with body "smith hi".
 */
class SmsSlots(private val contacts: ContactsRepository) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val lower = query.lowercase().trim()

        // 1. Strip prefix verb.
        val prefix = PREFIXES.firstOrNull { lower.startsWith(it) }
        val rest = (if (prefix != null) lower.substring(prefix.length) else lower).trim()
        if (rest.isEmpty()) return emptyMap()

        // 2. Conjunction split.
        val conjMatch = CONJUNCTIONS.find(rest)
        if (conjMatch != null) {
            val contact = rest.substring(0, conjMatch.range.first).trim()
            val body = rest.substring(conjMatch.range.last + 1).trim()
            if (contact.isNotEmpty() && body.isNotEmpty()) {
                return mapOf("contact" to contact, "body" to body)
            }
        }

        // 3. Greedy contact-name lookup.
        val words = rest.split(Regex("\\s+"))
        for (n in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val candidate = words.subList(0, n).joinToString(" ")
            if (contacts.findContact(candidate) != null) {
                val body = if (n < words.size) words.subList(n, words.size).joinToString(" ").trim() else ""
                if (body.isNotEmpty()) {
                    return mapOf("contact" to candidate, "body" to body)
                }
            }
        }

        // 4. Single-space fallback (today's behaviour).
        val spaceIdx = rest.indexOf(' ')
        if (spaceIdx < 0) return mapOf("contact" to rest)
        val contact = rest.substring(0, spaceIdx).trim()
        val body = rest.substring(spaceIdx + 1).trim()
        return if (body.isEmpty()) mapOf("contact" to contact)
               else mapOf("contact" to contact, "body" to body)
    }

    private companion object {
        // Order matters — longest first so "send a message to" matches before "send".
        val PREFIXES = listOf(
            "send a message to ", "send message to ", "send a message ", "send message ",
            "send sms to ", "send sms ",
            "text ", "tell ", "message ", "let ",
        )
        val CONJUNCTIONS = Regex("(?i)\\b(saying|that|telling (them|him|her))\\b")
        const val MAX_NAME_WORDS = 4
    }
}
