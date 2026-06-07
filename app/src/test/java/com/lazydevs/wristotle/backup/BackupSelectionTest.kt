// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSelectionTest {

    @Test fun default_isContentAndSettingsOnSecretsOff() {
        val s = BackupSelection()
        assertTrue(s.notes)
        assertTrue(s.weatherSettings)
        assertFalse("secrets default off so a 'just hit Export' doesn't leak", s.weatherApiKey)
        assertFalse(s.mcpAuthHeaders)
        assertFalse(s.askAgentApiKeys)
    }

    @Test fun allContentAndSettingsSelected_trueWhenSecretsOff() {
        // The whole point of the new derived: master "Select all" displays
        // ticked from a fresh-open state (secrets default off) without
        // requiring the user to opt secrets in.
        assertTrue(BackupSelection().allContentAndSettingsSelected)
        assertFalse(BackupSelection().allSelected)  // secrets required
    }

    @Test fun allSelected_trueOnlyForALL() {
        assertTrue(BackupSelection.ALL.allSelected)
        assertFalse(BackupSelection().allSelected)
        assertFalse(BackupSelection.NONE.allSelected)
    }

    @Test fun and_clampsToIntersection() {
        val requested = BackupSelection.ALL
        val available = BackupSelection(
            notes = true, tasks = false, conversations = true,
            reminders = false, nluLearned = false, appAliases = false,
            contactAliases = false, audioRecordings = true,
            appPreferences = false, weatherSettings = false,
            mcpServers = true, askAgentSetup = false,
            weatherApiKey = false, mcpAuthHeaders = false, askAgentApiKeys = false,
        )
        val clamped = requested and available
        assertEquals(available, clamped)
    }

    @Test fun and_isCommutative() {
        val a = BackupSelection(notes = true, tasks = false)
        val b = BackupSelection(notes = false, tasks = true)
        assertEquals(a and b, b and a)
    }

    @Test fun and_withALL_returnsOther() {
        val custom = BackupSelection(notes = true, tasks = false, weatherApiKey = true)
        assertEquals(custom, custom and BackupSelection.ALL)
        assertEquals(custom, BackupSelection.ALL and custom)
    }

    @Test fun and_withNONE_returnsNONE() {
        assertEquals(BackupSelection.NONE, BackupSelection.ALL and BackupSelection.NONE)
    }

    @Test fun legacyFull_isFullyTickedRegardlessOfALL() {
        // Schema-1 ZIPs predate the secrets split; LEGACY_FULL must stay
        // "everything available" even if ALL ever stops including secrets.
        assertTrue(BackupSelection.LEGACY_FULL.allSelected)
    }

    @Test fun anySecretSelected_truePerField() {
        assertFalse(BackupSelection().anySecretSelected)
        assertTrue(BackupSelection(weatherApiKey = true).anySecretSelected)
        assertTrue(BackupSelection(mcpAuthHeaders = true).anySecretSelected)
        assertTrue(BackupSelection(askAgentApiKeys = true).anySecretSelected)
    }
}