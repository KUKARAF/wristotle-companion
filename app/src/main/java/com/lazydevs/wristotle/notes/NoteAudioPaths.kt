// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

/**
 * The `audioFilePath` column on [Note] stores one *or more* absolute
 * paths, joined by [SEP] (a control char that never appears in Android
 * file paths). A note created or merge-appended still stores a single
 * path; a separate-append produces a multi-entry list. Empty list →
 * null column (no audio).
 *
 * Pure helpers so the same encoding lives in one place and tests can
 * pin it down without touching Room.
 */
internal object NoteAudioPaths {
    /** Unit Separator (US, 0x1F) — invalid in Android filesystem paths. */
    private const val SEP = ''

    fun parse(stored: String?): List<String> =
        if (stored.isNullOrEmpty()) emptyList()
        else stored.split(SEP).filter { it.isNotEmpty() }

    fun encode(paths: List<String>): String? =
        if (paths.isEmpty()) null else paths.joinToString(separator = SEP.toString())

    fun append(stored: String?, newPath: String): String =
        encode(parse(stored) + newPath)!!
}