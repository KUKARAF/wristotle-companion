// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.alarms

/**
 * Platform-agnostic one-shot exact-trigger scheduler. Android impl
 * wraps `AlarmManager.setExactAndAllowWhileIdle` + a BroadcastReceiver
 * → Notifier dispatch; an iOS impl would wrap
 * `UNUserNotificationCenter` with a calendar/interval trigger.
 *
 * The mismatch between platforms is real and shows up here: on Android
 * the alarm fires an in-process callback (BroadcastReceiver) that
 * decides whether to post a notification; on iOS the OS shows the
 * notification directly when the trigger fires and there's no in-process
 * callback unless the user taps. The interface stays Android-centric in
 * the sense that "scheduling" means "wake my code at time X" — iOS impls
 * have to translate this into a notification-with-trigger pattern.
 *
 * R4 batch 4 — interface added; existing :app/alarms/AlarmDispatcher.kt
 * and the persistent-reminder scheduler are NOT yet refactored to use
 * it. Their AlarmManager + PendingIntent + BroadcastReceiver logic is
 * Android-deep and translates poorly. The interface is here to
 * document the seam iOS implementations will satisfy.
 */
interface AlarmScheduler {
    /**
     * Schedule a one-shot exact alarm to fire at [triggerAtEpochMs].
     * If an alarm with [id] is already scheduled, replace it.
     *
     * [kind] tells the platform layer which receiver/notification flow
     * to invoke when the alarm fires.
     */
    fun scheduleExact(id: String, triggerAtEpochMs: Long, kind: AlarmKind)

    /** Cancel a previously-scheduled alarm by id. No-op if absent. */
    fun cancel(id: String)
}

/**
 * Coarse classifier the platform layer uses to route a fired alarm to
 * the right action. Add new kinds as more alarm flows lift; the
 * platform impl pattern-matches on this to pick the right
 * BroadcastReceiver / UNNotificationCategory.
 */
enum class AlarmKind {
    /** Phone-leg of an alarm created via the Alarms feature. */
    PhoneAlarm,

    /** Re-fire nag of a persistent reminder. */
    PersistentReminderNag,
}
