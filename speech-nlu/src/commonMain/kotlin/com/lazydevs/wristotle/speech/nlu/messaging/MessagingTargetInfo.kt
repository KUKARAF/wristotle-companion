// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.messaging

/**
 * Data-only projection of `:app/messaging/MessagingTarget` carrying the
 * fields a slot extractor needs to recognise a spoken app name.
 *
 * The Android `MessagingTarget` keeps everything: the deliver lambda,
 * the package id, the runtime install check, the enabled flag. The
 * slot extractor doesn't need any of that — it just needs the display
 * name and the spoken aliases (longest-first sort). Splitting the data
 * out lets [SendMessageSlots] live in commonMain.
 *
 * R3 batch 2 — paired with [com.lazydevs.wristotle.speech.nlu.contacts.ContactsResolver]
 * to lift SendMessageSlots into commonMain.
 */
data class MessagingTargetInfo(
    val displayName: String,
    val spokenAliases: Set<String>,
) {
    /**
     * Aliases ordered longest-first so a slot extractor can pick the most
     * specific match before a shorter prefix would accidentally win
     * (e.g. "telegram" before "tel"). Computed once at construction so
     * the SendMessage slot's three matching shapes don't each pay a
     * `sortedByDescending` per call.
     */
    val aliasesByLengthDesc: List<String> = spokenAliases.sortedByDescending { it.length }
}
