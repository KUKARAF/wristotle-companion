package com.lazydevs.wristotle.alarms

/**
 * Where a created alarm fires. Per-alarm, chosen in the companion's
 * AlarmEditorDialog.
 *
 *  - [Phone]: AlarmClock intent → phone's system clock. Wakes the user
 *    even if the watch is off-wrist. **Cannot be programmatically
 *    cancelled** — Android's AlarmClock surface is set-only, the only
 *    way to dismiss is from the phone's clock app.
 *  - [Watch]: `wakeup_schedule` on the Pebble. Cancel works via voice
 *    and via the companion list's per-row delete.
 *  - [Both]: both legs fire simultaneously. Cancel only stops the
 *    watch leg; the phone alarm still has to be dismissed manually.
 *
 * Persisted as String in Room (the entity column is the enum name) so
 * a new value at the end doesn't break old data files. */
enum class AlarmDestination { Phone, Watch, Both }
