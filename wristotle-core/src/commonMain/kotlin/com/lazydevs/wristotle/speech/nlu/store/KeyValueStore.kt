// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.store

/**
 * Minimal multiplatform key/value persistence surface. Android impl
 * wraps `SharedPreferences`; iOS impl will wrap `NSUserDefaults`.
 *
 * Deliberately narrow — get/put for the four primitive shapes our
 * settings classes actually use. The settings classes themselves stay
 * responsible for their own StateFlow / observation layer; this
 * interface is only the read/write seam.
 *
 * R3 batch 4 — paired with NluSettings + AskAgentSettings lifts. Future
 * settings (ReminderSettings, FileSyncSettings, etc.) follow the same
 * pattern: take a [KeyValueStore] via constructor + a starter set of
 * key constants in a companion object.
 *
 * Get methods take a default so callers can avoid the null-coalesce
 * dance — settings classes always have a baseline they default to,
 * matching the `SharedPreferences.getString(key, default)` shape.
 */
interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Read an enum-valued setting by its persisted `name`. Returns [default]
 * when the key is absent (empty-string sentinel) or stored as a value
 * that doesn't match any enum constant (manually-edited prefs file,
 * old enum value removed in a migration).
 *
 * Collapses the repeated `runCatching { enumValueOf<T>(name) }.getOrDefault(default)`
 * dance found in every lifted settings class.
 */
inline fun <reified T : Enum<T>> KeyValueStore.getEnum(key: String, default: T): T {
    val name = getString(key, "")
    if (name.isEmpty()) return default
    return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
