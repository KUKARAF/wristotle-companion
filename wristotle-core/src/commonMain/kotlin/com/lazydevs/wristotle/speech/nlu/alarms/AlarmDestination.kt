// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.alarms

/**
 * Where a created alarm fires. Per-alarm; chosen in the companion's
 * AlarmEditorDialog.
 *
 *  - [Phone]: AlarmClock intent → phone's system clock. Wakes the user
 *    even if the watch is off-wrist. **Cannot be programmatically
 *    cancelled** — Android's AlarmClock surface is set-only.
 *  - [Watch]: `wakeup_schedule` on the Pebble. Cancel works via voice
 *    and via the companion list's per-row delete.
 *  - [Both]: both legs fire simultaneously. Cancel only stops the
 *    watch leg.
 *
 * Persisted as the enum's `name` so a new value at the end doesn't
 * break old data files.
 *
 * R4 batch 4 — lifted from :app.
 */
enum class AlarmDestination { Phone, Watch, Both }
