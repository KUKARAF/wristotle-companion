// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a saved code. String UUID id (matches the cross-platform
 * [com.lazydevs.wristotle.speech.nlu.codes.SavedCode] shape so it can travel to
 * the watch + backup unchanged). [format] stores the CodeFormat enum name.
 */
@Entity(tableName = "codes")
data class CodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    /** Spoken alias for voice recall (empty = none). Added in schema v2. */
    val alias: String,
    val format: String,
    val data: String,
    val createdAtEpochMs: Long,
)
