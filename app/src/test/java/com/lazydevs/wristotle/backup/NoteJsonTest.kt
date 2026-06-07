// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.notes.Note
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val US = ''

class NoteJsonTest {

    @Test fun roundTrip_noAudio() {
        val original = Note(
            id = 7,
            body = "pick up milk",
            createdAtEpochMs = 1_700_000_000_000L,
            source = "watch",
            audioFilePath = null,
        )
        val decoded = NoteJson.decode(NoteJson.encode(original), schema = 1)
        assertEquals(original.copy(), decoded)
        assertNull(decoded.audioFilePath)
    }

    @Test fun roundTrip_singleAudio_keepsBasename() {
        val original = Note(
            id = 1,
            body = "x",
            createdAtEpochMs = 0,
            source = "watch",
            audioFilePath = "/data/data/x/files/notes-audio/20260522180345.wav",
        )
        // Encoder emits basename only; on decode the path is still a basename
        // (the importer's job to re-prefix with the local filesDir).
        val decoded = NoteJson.decode(NoteJson.encode(original), schema = 1)
        assertEquals("20260522180345.wav", decoded.audioFilePath)
    }

    @Test fun roundTrip_multipleAudio_keepsAllBasenamesJoinedByUS() {
        // Separate-append mode: audioFilePath stores multiple paths joined
        // by the Unit Separator. The encoder MUST emit each as its own
        // array entry so all clips survive the round-trip.
        val joined = listOf(
            "/data/.../notes-audio/clip1.wav",
            "/data/.../notes-audio/clip2.wav",
            "/data/.../notes-audio/clip3.wav",
        ).joinToString(separator = US.toString())
        val original = Note(id = 1, body = "x", createdAtEpochMs = 0, source = "watch", audioFilePath = joined)

        val json = NoteJson.encode(original)
        val arr = json.getJSONArray("audio_filenames")
        assertEquals(3, arr.length())
        assertEquals("clip1.wav", arr.getString(0))
        assertEquals("clip3.wav", arr.getString(2))

        val decoded = NoteJson.decode(json, schema = 1)
        assertEquals("clip1.wav${US}clip2.wav${US}clip3.wav", decoded.audioFilePath)
    }

    @Test fun decodeLegacySingleAudioFilenameKey() {
        // Backwards compatibility: an earlier dev encoder emitted
        // `audio_filename` as a plain string with a single basename. The
        // current decoder must still read those backups.
        val legacy = JSONObject().apply {
            put("id", 1)
            put("body", "x")
            put("created_at_ms", 0)
            put("source", "watch")
            put("audio_filename", "old.wav")
        }
        val decoded = NoteJson.decode(legacy, schema = 1)
        assertEquals("old.wav", decoded.audioFilePath)
    }

    @Test fun decodeEmptyAudioFilenamesArrayProducesNull() {
        val json = JSONObject().apply {
            put("id", 1)
            put("body", "x")
            put("created_at_ms", 0)
            put("source", "watch")
            put("audio_filenames", org.json.JSONArray())
        }
        assertNull(NoteJson.decode(json, schema = 1).audioFilePath)
    }

    @Test fun missingOptionalFieldsFallBackToSafeDefaults() {
        // A backup row missing the source / created_at_ms keys still decodes
        // (defensive default: empty body, source="unknown", at=0).
        val json = JSONObject().apply { put("id", 1) }
        val decoded = NoteJson.decode(json, schema = 1)
        assertEquals("", decoded.body)
        assertEquals(0L, decoded.createdAtEpochMs)
        assertEquals("unknown", decoded.source)
        assertNull(decoded.audioFilePath)
    }

    @Test fun forwardCompat_unknownSchemaRejected() {
        val json = NoteJson.encode(
            Note(id = 1, body = "x", createdAtEpochMs = 0, source = "watch")
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            NoteJson.decode(json, schema = NoteJson.CURRENT_SCHEMA + 1)
        }
        assertTrue(ex.message!!.contains("Unsupported NoteJson schema"))
    }
}