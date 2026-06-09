// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.messaging

import com.lazydevs.wristotle.speech.nlu.messaging.MessagingTargetInfo

/**
 * Project the Android-side [MessagingTarget] down to the multiplatform
 * data shape the lifted `SendMessageSlots` (in :speech-nlu commonMain)
 * consumes.
 *
 * Drops the deliver lambda, packageId, enabled flag and isInstalled
 * runtime check — slot recognition only needs displayName + spoken
 * aliases. The full target stays in :app for the handler-side dispatch.
 */
fun MessagingTarget.toInfoForSlots(): MessagingTargetInfo =
    MessagingTargetInfo(displayName = displayName, spokenAliases = spokenAliases)
