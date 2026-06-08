// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.briefing

import com.lazydevs.wristotle.briefing.UnreadMessagesProvider.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun aggregate(rows: List<Row>): UnreadMessagesProvider.Snapshot =
    UnreadMessagesProvider.aggregate(rows)

/**
 * Pure tests for [UnreadMessagesProvider.aggregate] — the shared
 * partition / per-app counting / sort logic that all three brief paths
 * (snapshot / todayPosts / snapshotPlusTodayPosts) funnel into.
 *
 * Each messaging Section now carries TWO numbers: `conversations`
 * (dedup'd by conversation key) and `messages` (raw). The brief
 * renderer surfaces both when they diverge (one chatty thread); the
 * aggregator just has to compute them faithfully.
 */
class UnreadMessagesAggregateTest {

    @Test fun emptyRowsProducesEmptySnapshot() {
        val s = aggregate(emptyList())
        assertTrue(s.isEmpty)
        assertEquals(0, s.totalConversations)
        assertEquals(0, s.totalMessages)
    }

    @Test fun groupsMessagingByLabelConvDescThenLabelAsc() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.whatsapp", "com.whatsapp/sc/u2"),
            Row("org.thoughtcrime.securesms", "org.thoughtcrime.securesms/sc/u1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
        ))
        // No duplicate keys — conversations and messages both = row count.
        assertEquals(
            listOf(
                Triple("WhatsApp", 2, 2),
                Triple("Messages", 1, 1),
                Triple("Signal", 1, 1),
            ),
            s.messaging.map { Triple(it.label, it.conversations, it.messages) },
        )
        assertEquals(0, s.otherCount)
    }

    @Test fun unmatchedPackagesLandInOtherCount() {
        val s = aggregate(listOf(
            Row("com.weird.app", "com.weird.app/id/1"),
            Row("com.another.app", "com.another.app/id/2"),
        ))
        assertEquals(emptyList<UnreadMessagesProvider.Section>(), s.messaging)
        assertEquals(2, s.otherCount)
    }

    @Test fun dedupesConversationsButCountsMessagesRaw() {
        // Persisted-log path posts a row per incoming SMS within one
        // thread. The aggregator should report:
        //   - conversations = 2 (only 2 distinct threads)
        //   - messages      = 4 (raw count)
        // The renderer then decides whether to surface both numbers.
        val s = aggregate(listOf(
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-2"),
        ))
        val section = s.messaging.single()
        assertEquals("Messages", section.label)
        assertEquals(2, section.conversations)
        assertEquals(4, section.messages)
    }

    @Test fun samePackageMultipleConversationsCountSeparately() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/alice"),
            Row("com.whatsapp", "com.whatsapp/sc/bob"),
        ))
        val section = s.messaging.single()
        assertEquals(2, section.conversations)
        assertEquals(2, section.messages)
    }

    @Test fun mixedMessagingAndOtherAreSegregated() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.weird.app", "com.weird.app/id/1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
            Row("com.another.weird", "com.another.weird/id/2"),
        ))
        assertEquals(
            listOf("Messages", "WhatsApp"),
            s.messaging.map { it.label },
        )
        assertEquals(2, s.otherCount)
    }

    @Test fun totalsIncludeMessagingPlusOther() {
        // Two messaging conversations (1 raw msg each) + 1 other →
        // totalConversations = 2 + 1 = 3, totalMessages = 2 + 1 = 3.
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
            Row("com.weird.app", "com.weird.app/id/1"),
        ))
        assertEquals(3, s.totalConversations)
        assertEquals(3, s.totalMessages)
    }

    @Test fun totalsDivergeWhenAThreadIsChatty() {
        // 1 conversation in WhatsApp with 5 raw messages.
        val rows = (1..5).map {
            Row("com.whatsapp", "com.whatsapp/sc/alice")
        }
        val s = aggregate(rows)
        assertEquals(1, s.totalConversations)
        assertEquals(5, s.totalMessages)
    }

    @Test fun tieBreakerOnLabelAscWhenConvCountsEqual() {
        // Three apps each with one conversation — sort by label-asc.
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
            Row("org.thoughtcrime.securesms", "org.thoughtcrime.securesms/sc/u1"),
        ))
        assertEquals(
            listOf("Messages", "Signal", "WhatsApp"),
            s.messaging.map { it.label },
        )
    }
}
