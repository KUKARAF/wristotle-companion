package com.lazydevs.wristotle.alarms

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.transport.PebbleTransport
import java.util.Calendar

/**
 * Single entry point for "schedule this alarm on the right destinations"
 * and "cancel its watch leg if any". Owned by [com.lazydevs.wristotle.WristotleApplication]
 * so the same instance is shared between the UI's create/toggle handlers
 * and the [com.lazydevs.wristotle.handlers.CancelAlarmHandler] voice path.
 *
 * Phone leg: `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI=true`.
 * SET-only — Android exposes no API to enumerate or cancel a previously-
 * scheduled phone alarm (see knowledge `alarm-timer.md` for the
 * `DISMISS_ALARM` dead-end). The Room row's [AlarmEntity.destination]
 * remembers the user's intent so the UI can show "this alarm goes to
 * the phone too" even though we can't verify it's still there.
 *
 * Watch leg: [PebbleTransport.sendAlarmSet] with epoch SECONDS. Pebble's
 * `wakeup_service` requires ≥ 30 s lead and global ±60 s spacing across
 * apps; we add a 5 s safety to the lead, and surface scheduling failures
 * via the watch's ALARM_SET_RESULT callback (not from this call's return).
 */
class AlarmDispatcher(
    private val context: Context,
    private val transport: PebbleTransport,
    private val repository: AlarmRepository,
) {

    /** Apply both legs of the alarm. Updates [AlarmEntity.wireEpoch] when
     *  the watch leg succeeds so the cancel/list paths know what to target. */
    suspend fun schedule(alarm: AlarmEntity): DispatchResult {
        val destination = runCatching { AlarmDestination.valueOf(alarm.destination) }
            .getOrElse { AlarmDestination.Phone }

        val errors = mutableListOf<String>()
        var newWireEpoch: Long? = alarm.wireEpoch

        if (destination == AlarmDestination.Phone || destination == AlarmDestination.Both) {
            runCatching { firePhoneAlarm(alarm) }
                .onFailure {
                    Log.w(TAG, "phone leg failed", it)
                    errors += "Phone alarm: ${it.message ?: "unknown error"}"
                }
        }

        if (destination == AlarmDestination.Watch || destination == AlarmDestination.Both) {
            val epoch = nextOccurrenceEpochSeconds(alarm.hour, alarm.minute)
            val ok = runCatching { transport.sendAlarmSet(epoch, labelOrDefault(alarm.label)) }
                .getOrElse {
                    Log.w(TAG, "watch leg threw", it)
                    errors += "Watch alarm: ${it.message ?: "send failed"}"
                    false
                }
            if (ok) newWireEpoch = epoch
            else if (errors.none { it.startsWith("Watch alarm:") }) {
                errors += "Watch alarm: not acknowledged"
            }
        }

        if (newWireEpoch != alarm.wireEpoch) {
            repository.update(alarm.copy(wireEpoch = newWireEpoch))
        }

        return if (errors.isEmpty()) DispatchResult.Success(newWireEpoch)
               else DispatchResult.PartialFailure(errors)
    }

    /** Cancel the watch leg of [alarm] (if any) and disable the row. The
     *  phone leg can't be cancelled programmatically — caller is
     *  responsible for surfacing that to the user. */
    suspend fun cancelWatchLeg(alarm: AlarmEntity): Boolean {
        val epoch = alarm.wireEpoch ?: return false
        val ok = runCatching { transport.sendAlarmCancel(epoch) }
            .getOrElse { Log.w(TAG, "watch cancel threw", it); false }
        if (ok) {
            repository.update(alarm.copy(wireEpoch = null, enabled = false))
        }
        return ok
    }

    private fun firePhoneAlarm(alarm: AlarmEntity) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, labelOrDefault(alarm.label))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d(TAG, "phone alarm fired for ${alarm.hour}:${alarm.minute}")
    }

    private fun nextOccurrenceEpochSeconds(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Roll past lead window so wakeup_schedule doesn't refuse with E_RANGE.
        if (target.timeInMillis <= now.timeInMillis + WATCH_ALARM_LEAD_MS) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis / 1000
    }

    private fun labelOrDefault(label: String): String =
        label.trim().ifBlank { "Alarm" }

    companion object {
        private const val TAG = "AlarmDispatcher"
        // Pebble's 30 s minimum + 5 s safety so we don't lose alarms to
        // sub-second clock drift between phone and watch.
        private const val WATCH_ALARM_LEAD_MS = 35_000L
    }
}

sealed class DispatchResult {
    data class Success(val wireEpoch: Long?) : DispatchResult()
    data class PartialFailure(val errors: List<String>) : DispatchResult()
}
