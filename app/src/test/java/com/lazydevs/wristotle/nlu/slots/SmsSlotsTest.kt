package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.phone.ContactsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSlotsTest {

    private fun extractor(
        knownContacts: Set<String> = emptySet(),
    ) = SmsSlots(findContact = { query ->
        if (query.lowercase() in knownContacts.map(String::lowercase))
            ContactsRepository.Contact(name = query, number = "555-0100")
        else null
    })

    private fun extract(query: String, contacts: Set<String> = emptySet()): Map<String, Any> =
        runBlocking { extractor(contacts).extract(query) }

    // --- Conjunction-split path (doesn't need a contact lookup) ----------

    @Test fun `saying conjunction splits cleanly`() {
        val result = extract("text john saying hello")
        assertEquals("john", result["contact"])
        assertEquals("hello", result["body"])
    }

    @Test fun `that conjunction splits cleanly`() {
        // Extractor lowercases the whole query first — that's a deliberate
        // choice so contact-name matching is case-insensitive; downstream
        // SMS-send doesn't care about case in the body.
        val result = extract("text dad that I'll be late")
        assertEquals("dad", result["contact"])
        assertEquals("i'll be late", result["body"])
    }

    @Test fun `telling them conjunction splits cleanly`() {
        val result = extract("message alice telling them about the meeting")
        assertEquals("alice", result["contact"])
        assertEquals("about the meeting", result["body"])
    }

    @Test fun `trailing emphasis on contact side gets stripped, body preserved`() {
        // The case that surfaced during testing: don't shred the body's
        // intentional "yes yes yes" but DO clean the contact side. Needs
        // 2+ trailing emphatic tokens on the contact side to trigger the
        // stripper.
        val result = extract("text john, yes, yes, saying yes yes yes")
        assertEquals("john", result["contact"])
        assertEquals("yes yes yes", result["body"])
    }

    // --- Prefix variants -------------------------------------------------

    @Test fun `'send a message to' prefix beats shorter 'send'`() {
        val result = extract("send a message to bob saying hi")
        assertEquals("bob", result["contact"])
        assertEquals("hi", result["body"])
    }

    @Test fun `'send sms to' prefix`() {
        val result = extract("send sms to alice saying hello")
        assertEquals("alice", result["contact"])
        assertEquals("hello", result["body"])
    }

    // --- Greedy contact-name path (no conjunction) -----------------------

    @Test fun `greedy lookup matches single-word contact, body follows`() {
        val result = extract("text john hello there", contacts = setOf("john"))
        assertEquals("john", result["contact"])
        assertEquals("hello there", result["body"])
    }

    @Test fun `greedy lookup prefers longest matching name (2-word over 1-word)`() {
        // Both "john" and "john smith" exist; the 4→1 word loop tries longest
        // first so "john smith" wins.
        val result = extract(
            "text john smith hello there",
            contacts = setOf("john", "john smith"),
        )
        assertEquals("john smith", result["contact"])
        assertEquals("hello there", result["body"])
    }

    @Test fun `greedy lookup tolerates whisper punctuation on the name token`() {
        // Whisper often outputs "john," with a trailing comma. cleanNameToken
        // strips it so contacts lookup hits.
        val result = extract("text john, hello there", contacts = setOf("john"))
        assertEquals("john", result["contact"])
        assertEquals("hello there", result["body"])
    }

    // --- Fallback path ---------------------------------------------------

    @Test fun `no contact known and no conjunction falls back to first-space split`() {
        // Today's behaviour: at least give the handler *something* to try.
        val result = extract("text unknown name hello")
        assertEquals("unknown", result["contact"])
        assertEquals("name hello", result["body"])
    }
}
