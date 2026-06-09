// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.FindPhone] — empty.
 * Trigger phrases ("find my phone", "ping my phone", "where's my phone")
 * carry no parameters today.
 */
class FindPhoneSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> = emptyMap()
}