package com.lazydevs.wristotle.handlers.persistent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms persistent reminders after a device reboot. AlarmManager wipes
 * pending alarms on shutdown, so without this any nag that hadn't fired
 * yet would silently disappear after a restart.
 *
 * Split out from [PersistentReminderReceiver] because `BOOT_COMPLETED` is a
 * system broadcast — the receiver needs `android:exported="true"` to receive
 * it. Keeping it in its own class means the fire / stop intents stay
 * `exported="false"`, so external apps can't synthesise them.
 */
class PersistentReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PersistentReminderScheduler(context.applicationContext).restoreAll()
    }
}
