// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu

import com.lazydevs.wristotle.speech.nlu.contacts.ResolvedContact
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import kotlinx.datetime.Instant

/**
 * Typed reads for the untyped [IntentResult.slots] bag.
 *
 * Slots travel as `Map<String, Any>`, so each handler historically cast values
 * itself (`slots[Time] as? Instant`). That let the producer (slot extractor)
 * and consumer (handler) drift apart silently — `SetAlarmHandler` cast the Time
 * slot to `java.util.Date` long after the extractors moved to `Instant`, so
 * `as? Date` returned null and every voice alarm reported "couldn't understand"
 * after the confirm preview already showed the right time. Routing reads through
 * these accessors keeps one type per slot in one place; a handler can't pick the
 * wrong type at its call site.
 */

/** The Time slot as a wall-clock [Instant] (the type every scheduling slot emits). */
fun IntentResult.instantSlot(key: String): Instant? = slots[key] as? Instant

/** Trimmed, never-null — the dominant handler pattern for name/body/target slots. */
fun IntentResult.stringSlot(key: String): String = (slots[key] as? String)?.trim().orEmpty()

/** Raw nullable string for callers that apply their own post-processing
 *  (takeIf-not-blank, lowercase, map to File, …). */
fun IntentResult.optStringSlot(key: String): String? = slots[key] as? String

fun IntentResult.intSlot(key: String): Int? = slots[key] as? Int

fun IntentResult.boolSlot(key: String): Boolean = slots[key] as? Boolean ?: false

/** The pre-resolved contact a slot extractor attached, if any. */
fun IntentResult.contactSlot(): ResolvedContact? =
    slots[SlotKeys.ResolvedContact] as? ResolvedContact
