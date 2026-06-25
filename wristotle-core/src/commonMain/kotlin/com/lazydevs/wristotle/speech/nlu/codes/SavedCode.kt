// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * A user-saved barcode/QR. [data] is the raw decoded payload (re-encoded for
 * display); [label] is the user's name for it ("Tesco Clubcard"). Pure model,
 * no platform deps — lifts to iOS. Persistence is an Android Room entity in
 * `:app`; this is the cross-platform shape that travels to the watch + backup.
 */
data class SavedCode(
    val id: String,
    val label: String,
    val format: CodeFormat,
    val data: String,
    val createdAtEpochMs: Long,
)
