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
 * partition/dedup/sort logic that both `snapshot()` and `todayPosts()`
 * funnel into. Covers the regressions that surfaced during v1.4.0
 * Morning Brief development plus a new dedup case for the persisted-log
 * path where the same conversation can post multiple times in a day.
 */
class UnreadMessagesAggregateTest {

    @Test fun emptyRowsProducesEmptySnapshot() {
        val s = aggregate(emptyList())
        assertTrue(s.isEmpty)
        assertEquals(0, s.totalCount)
    }

    @Test fun groupsMessagingByLabelCountDescThenLabelAsc() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.whatsapp", "com.whatsapp/sc/u2"),
            Row("org.thoughtcrime.securesms", "org.thoughtcrime.securesms/sc/u1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
        ))
        assertEquals(
            listOf("WhatsApp" to 2, "Messages" to 1, "Signal" to 1),
            s.messaging.map { it.label to it.count },
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

    @Test fun dedupesByConversationKey() {
        // Persisted-log path posts a row per incoming SMS within one
        // thread; the aggregate must collapse them via the shared
        // conversation key so "8 SMS in 2 threads" reads as 2 not 8.
        val s = aggregate(listOf(
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-1"),
            Row("com.android.messaging", "com.android.messaging/ch/thread-2"),
        ))
        assertEquals(listOf("Messages" to 2), s.messaging.map { it.label to it.count })
    }

    @Test fun samePackageMultipleConversationsCountSeparately() {
        // Sanity: two different conversation keys in the same app should
        // count as two, not be collapsed by package.
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/alice"),
            Row("com.whatsapp", "com.whatsapp/sc/bob"),
        ))
        assertEquals(listOf("WhatsApp" to 2), s.messaging.map { it.label to it.count })
    }

    @Test fun mixedMessagingAndOtherAreSegregated() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.weird.app", "com.weird.app/id/1"),
            Row("com.android.messaging", "com.android.messaging/ch/t1"),
            Row("com.another.weird", "com.another.weird/id/2"),
        ))
        assertEquals(
            listOf("Messages" to 1, "WhatsApp" to 1),
            s.messaging.map { it.label to it.count },
        )
        assertEquals(2, s.otherCount)
    }

    @Test fun totalCountIncludesMessagingPlusOther() {
        val s = aggregate(listOf(
            Row("com.whatsapp", "com.whatsapp/sc/u1"),
            Row("com.weird.app", "com.weird.app/id/1"),
        ))
        assertEquals(2, s.totalCount)
    }

    @Test fun tieBreakerOnLabelAscWhenCountsEqual() {
        // Three apps each with one notification — count is tied at 1, so
        // label-asc decides the order. "Messages" < "WhatsApp" so it wins.
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
