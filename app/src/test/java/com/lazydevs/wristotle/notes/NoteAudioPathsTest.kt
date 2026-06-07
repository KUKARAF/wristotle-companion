// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAudioPathsTest {

    @Test fun `null parses to empty list`() {
        assertTrue(NoteAudioPaths.parse(null).isEmpty())
    }

    @Test fun `empty string parses to empty list`() {
        assertTrue(NoteAudioPaths.parse("").isEmpty())
    }

    @Test fun `single path round-trips`() {
        val one = "/data/data/.../notes-audio/20260522180432.wav"
        assertEquals(listOf(one), NoteAudioPaths.parse(one))
        assertEquals(one, NoteAudioPaths.encode(listOf(one)))
    }

    @Test fun `multiple paths round-trip via append`() {
        val a = "/audio/a.wav"
        val b = "/audio/b.wav"
        val c = "/audio/c.wav"
        val one = NoteAudioPaths.append(null, a)
        val two = NoteAudioPaths.append(one, b)
        val three = NoteAudioPaths.append(two, c)
        assertEquals(listOf(a, b, c), NoteAudioPaths.parse(three))
    }

    @Test fun `encode empty returns null`() {
        assertNull(NoteAudioPaths.encode(emptyList()))
    }
}