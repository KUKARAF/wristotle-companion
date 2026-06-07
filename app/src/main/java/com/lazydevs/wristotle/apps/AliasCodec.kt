// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

/**
 * Pure (de)serialization for [AliasStore] — a `normalized phrase → packageId`
 * map. Split out so it's unit-testable without an Android `Context`.
 *
 * Wire format: one entry per line, `phrase \t packageId`. Safe because phrases
 * are run through [normalizeForIndex] (lowercase, `[a-z0-9 ]` only) and package
 * ids are `[a-z0-9._]` — neither can contain a tab or newline, so no escaping
 * is needed.
 */
internal object AliasCodec {

    fun encode(aliases: Map<String, String>): String =
        aliases.entries.joinToString("\n") { (phrase, pkg) -> "$phrase\t$pkg" }

    fun decode(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val phrase = line.substring(0, tab)
            val pkg = line.substring(tab + 1)
            if (pkg.isEmpty()) null else phrase to pkg
        }.toMap()
    }
}