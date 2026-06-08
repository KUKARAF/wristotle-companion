// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.notifier

/**
 * Pure body-text generator for the persistent-reminder nag notification.
 *
 * [attemptsRemainingAtFire] is the value AT the moment the notification
 * fires, BEFORE the receiver decrements it. So a record with
 * `attemptsRemaining = 5` produces "4 more nags after this".
 *
 * R4 batch 2 — lifted from `PersistentReminderReceiver.buildBody`. Pure
 * Kotlin string formatting; iOS impl renders the same text.
 */
object PersistentReminderNagFormatter {
    fun body(attemptsRemainingAtFire: Int): String {
        val afterThis = (attemptsRemainingAtFire - 1).coerceAtLeast(0)
        return when (afterThis) {
            0 -> "Persistent reminder · last nag"
            1 -> "Persistent reminder · 1 more nag after this"
            else -> "Persistent reminder · $afterThis more nags after this"
        }
    }
}
