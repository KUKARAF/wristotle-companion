// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    // --- isActionable ---

    @Test fun plainNotificationIsActionable() {
        assertTrue(NotificationFilter.isActionable("com.example.app", 0))
    }

    @Test fun ourOwnPackageIsNotActionable() {
        // Defensively skipped — WatchMessageService's FGS notification is
        // already covered by the flag below, but a future non-FGS
        // notification we add shouldn't inflate the brief either.
        assertFalse(NotificationFilter.isActionable("com.lazydevs.wristotle", 0))
    }

    @Test fun groupSummaryIsSkipped() {
        // Otherwise we'd double-count: the leaf notifications carry the
        // real content and are in the active set too.
        assertFalse(NotificationFilter.isActionable("com.example.app", Notification.FLAG_GROUP_SUMMARY))
    }

    @Test fun ongoingEventIsSkipped() {
        assertFalse(NotificationFilter.isActionable("com.example.app", Notification.FLAG_ONGOING_EVENT))
    }

    @Test fun foregroundServiceIsSkipped() {
        assertFalse(NotificationFilter.isActionable("com.example.app", Notification.FLAG_FOREGROUND_SERVICE))
    }

    @Test fun noClearIsSkipped() {
        assertFalse(NotificationFilter.isActionable("com.example.app", Notification.FLAG_NO_CLEAR))
    }

    @Test fun multipleFlagsAnySkippedFlagSkips() {
        val flags = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_AUTO_CANCEL
        assertFalse(NotificationFilter.isActionable("com.example.app", flags))
    }

    // --- conversationKey ---

    @Test fun shortcutIdWins() {
        val key = NotificationFilter.conversationKey(
            packageName = "com.whatsapp",
            shortcutId = "user-42",
            channelId = "messages",
            tag = "thread-99",
            id = 7,
        )
        assertEquals("com.whatsapp/sc/user-42", key)
    }

    @Test fun channelIdNextWhenShortcutMissing() {
        val key = NotificationFilter.conversationKey(
            packageName = "com.android.messaging",
            shortcutId = null,
            channelId = "thread-12345",
            tag = null,
            id = 99,
        )
        // AOSP Messaging stuffs the conversation id in channelId — this
        // collapses many incoming SMS in one thread into a single brief
        // row. Regression hits surfaced in v1.4.0 testing.
        assertEquals("com.android.messaging/ch/thread-12345", key)
    }

    @Test fun tagNextWhenChannelMissing() {
        val key = NotificationFilter.conversationKey(
            packageName = "org.thoughtcrime.securesms",
            shortcutId = null,
            channelId = null,
            tag = "sender-bob",
            id = 1,
        )
        assertEquals("org.thoughtcrime.securesms/tag/sender-bob", key)
    }

    @Test fun idIsLastResort() {
        val key = NotificationFilter.conversationKey(
            packageName = "com.legacy.app",
            shortcutId = null,
            channelId = null,
            tag = null,
            id = 42,
        )
        assertEquals("com.legacy.app/id/42", key)
    }

    @Test fun blankShortcutIdDoesNotWin() {
        // Some apps return "" rather than null. Don't let an empty string
        // outrank a real channelId.
        val key = NotificationFilter.conversationKey(
            packageName = "com.app",
            shortcutId = "",
            channelId = "fallback",
            tag = null,
            id = 1,
        )
        assertEquals("com.app/ch/fallback", key)
    }

    @Test fun blankTagDoesNotWin() {
        val key = NotificationFilter.conversationKey(
            packageName = "com.app",
            shortcutId = null,
            channelId = null,
            tag = "",
            id = 7,
        )
        assertEquals("com.app/id/7", key)
    }

    @Test fun keysAreDistinctAcrossPackagesEvenWhenIdsClash() {
        // Two apps both fall through to the id branch with the same id —
        // the package prefix keeps them distinct so the dedup doesn't
        // merge "notification 1 from App A" with "notification 1 from App B".
        val a = NotificationFilter.conversationKey("com.a", null, null, null, 1)
        val b = NotificationFilter.conversationKey("com.b", null, null, null, 1)
        assertEquals("com.a/id/1", a)
        assertEquals("com.b/id/1", b)
    }
}
