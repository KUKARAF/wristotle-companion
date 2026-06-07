// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [ContactAliasCodec]. No Android imports —
 * runs as a JVM JUnit test, no Robolectric.
 *
 * The codec is `org.json` not kotlinx-serialization (same convention
 * as [com.lazydevs.wristotle.backup.BackupManifestCodec]); decoder is
 * deliberately forgiving so a single bad row doesn't lose the whole
 * user-data blob.
 */
class ContactAliasCodecTest {

    @Test fun emptyMapRoundTrips() {
        assertEquals(emptyMap<String, ContactRef>(), ContactAliasCodec.decode(ContactAliasCodec.encode(emptyMap())))
    }

    @Test fun fullRoundTrip() {
        val original = mapOf(
            "mom" to ContactRef("0r1-AAAA", "Aparna Smith", "+15551234567"),
            "boss" to ContactRef("0r5-BBBB", "Jane Doe", "+15559876543"),
        )
        assertEquals(original, ContactAliasCodec.decode(ContactAliasCodec.encode(original)))
    }

    @Test fun emptyRawDecodesAsEmpty() {
        // Returned by SharedPrefs when the key has never been written.
        assertEquals(emptyMap<String, ContactRef>(), ContactAliasCodec.decode(""))
    }

    @Test fun malformedJsonDecodesAsEmpty() {
        // Better to lose the (presumably corrupted) alias map than to
        // crash the Settings card. Same forgiveness as the apps codec.
        assertEquals(emptyMap<String, ContactRef>(), ContactAliasCodec.decode("not json"))
        assertEquals(emptyMap<String, ContactRef>(), ContactAliasCodec.decode("{partial"))
    }

    @Test fun missingPhraseFieldSkipsRow() {
        // Each row needs phrase / lookup_key / number to be useful;
        // any missing required field skips just that row.
        val raw = """[
            {"lookup_key": "k1", "name": "n", "number": "+1"},
            {"phrase": "ok", "lookup_key": "k2", "name": "n", "number": "+2"}
        ]"""
        val decoded = ContactAliasCodec.decode(raw)
        assertEquals(setOf("ok"), decoded.keys)
    }

    @Test fun missingLookupKeySkipsRow() {
        val raw = """[
            {"phrase": "broken", "name": "n", "number": "+1"},
            {"phrase": "ok", "lookup_key": "k", "name": "n", "number": "+2"}
        ]"""
        assertEquals(setOf("ok"), ContactAliasCodec.decode(raw).keys)
    }

    @Test fun missingNumberSkipsRow() {
        // Number is the dispatchable bit — an alias without one would
        // fail at call/text time, so don't keep it.
        val raw = """[
            {"phrase": "broken", "lookup_key": "k", "name": "n"},
            {"phrase": "ok", "lookup_key": "k2", "name": "n", "number": "+2"}
        ]"""
        assertEquals(setOf("ok"), ContactAliasCodec.decode(raw).keys)
    }

    @Test fun missingNameFieldStillDecodes() {
        // Name snapshot is best-effort metadata (powers the dead-link
        // UX); the runtime resolve path uses the lookup key. So an
        // alias without a name still loads and just renders as
        // "(not found)" when its key is also dead.
        val raw = """[
            {"phrase": "no-name", "lookup_key": "k", "number": "+1"}
        ]"""
        val decoded = ContactAliasCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("", decoded["no-name"]?.nameSnapshot)
    }

    @Test fun encodeIsSortedForStablePrefsBlobs() {
        // Encoded order matches sorted-by-phrase iteration so the
        // backed prefs string doesn't churn between writes when the
        // map's iteration order changes (HashMap can reorder).
        val out = ContactAliasCodec.encode(
            mapOf(
                "zebra" to ContactRef("kz", "Z", "+1"),
                "apple" to ContactRef("ka", "A", "+2"),
                "mango" to ContactRef("km", "M", "+3"),
            ),
        )
        val applePos = out.indexOf("\"apple\"")
        val mangoPos = out.indexOf("\"mango\"")
        val zebraPos = out.indexOf("\"zebra\"")
        assertTrue("apple before mango", applePos < mangoPos)
        assertTrue("mango before zebra", mangoPos < zebraPos)
    }
}