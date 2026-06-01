package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "WatchHintRefiner"

/**
 * Picks which [Intent] to actually dispatch for a query the watch sent.
 * Pure NLU policy — no Android types, no service plumbing — so it tests
 * with unit cases without spinning the service up.
 *
 * Three regimes (in order):
 *   1. The watch tagged the query with REMINDER_QUERY or CANCEL_QUERY →
 *      trust the hint, but allow refinement WITHIN the reminder family
 *      (Reminder / ListReminders / Reschedule) because the watch's
 *      keyword routing can't distinguish them.
 *   2. The classifier is below the confidence floor OR within margin of
 *      the runner-up → check the prefix hints. An unambiguous opening
 *      verb beats the embedder's uncertain pick.
 *   3. The classifier is confidently above margin → trust it, with the
 *      deterministic SetAlarm↔SetTimer and Time↔WorldTime corrections
 *      applied to fix the embedding's known confusions.
 */
object WatchHintRefiner {

    /** Intents the watch's "remind(er)" keyword routing lumps into
     *  REMINDER_QUERY. A Reminder watch-hint may be refined into any of
     *  these by a confident classifier, but no further. */
    val REMINDER_FAMILY: Set<Intent> = setOf(
        Intent.Reminder, Intent.ListReminders, Intent.Reschedule,
    )

    /**
     * Pick the intent to dispatch on. Returns null only when the
     * classifier is below threshold AND no prefix hint fires AND no
     * watch hint exists — meaning the caller should treat the query as
     * [Intent.Unknown].
     */
    fun refine(
        classified: IntentResult?,
        watchHint: Intent?,
        query: String,
        routeThreshold: Float,
        routeMargin: Float,
    ): Intent? {
        if (watchHint != null) return refineWatchHinted(classified, watchHint, query, routeThreshold)
        if (classified == null) return null
        return refineUnhinted(classified, query, routeThreshold, routeMargin)
    }

    private fun refineWatchHinted(
        classified: IntentResult?,
        watchHint: Intent,
        query: String,
        routeThreshold: Float,
    ): Intent {
        // The watch routes anything containing "remind(er)" to REMINDER_QUERY,
        // so a Reminder hint can actually be a list ("is there a reminder at
        // 2pm") or reschedule ("push my reminder to 6") query. Refine within
        // the reminder family — it can't escape to Call/Sms/Media/etc, so the
        // hint's guard holds. Two refiners, in order:
        //   1. A deterministic prefix hint (e.g. interrogative + reminder →
        //      ListReminders). The embedding can't separate "is there a
        //      reminder at X" from "remind me at X" because the time dominates
        //      the cosine, so the opening words are the reliable signal.
        //   2. Otherwise a confident classifier pick (catches Reschedule).
        val prefixHint = if (watchHint == Intent.Reminder) PrefixHints.hintFor(query) else null
        val refined = when {
            prefixHint != null && prefixHint in REMINDER_FAMILY -> prefixHint
            watchHint == Intent.Reminder &&
                classified != null &&
                classified.intent in REMINDER_FAMILY &&
                classified.confidence >= routeThreshold -> classified.intent
            else -> watchHint
        }
        if (refined != watchHint) {
            Log.d(TAG, "watch hinted $watchHint; refined to $refined " +
                "(prefix=$prefixHint classifier=${classified?.intent}@${classified?.confidence})")
        }
        return refined
    }

    private fun refineUnhinted(
        classified: IntentResult,
        query: String,
        routeThreshold: Float,
        routeMargin: Float,
    ): Intent {
        // Confidence + margin gate. Three paths:
        //   1. Above threshold AND margin clear of the runner-up: trust the
        //      classifier's pick directly.
        //   2. Above threshold but tight margin OR below threshold: check for
        //      an unambiguous opening verb (`text`/`call`/`remind`/`cancel`/
        //      `find phone`). If the prefix hint resolves cleanly, route to
        //      that — a verb at the front of the query is deterministic
        //      evidence the embedder may have missed (e.g. when a long body
        //      dilutes the cosine to the canonical intent centroid).
        //   3. No hint AND no usable classifier pick → Unknown.
        val runnerUp = classified.alternates.firstOrNull()?.score ?: 0f
        val below = classified.confidence < routeThreshold
        val ambiguous = !below && (classified.confidence - runnerUp) < routeMargin
        if (below || ambiguous) {
            val why = if (below) "below-threshold" else "ambiguous"
            val hint = PrefixHints.hintFor(query)
            if (hint != null) {
                val refined = PrefixHints.refineWorldTime(query, PrefixHints.refineAlarmTimer(query, hint))
                Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) → prefix hint $refined wins")
                return refined
            }
            Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) and no prefix hint → Unknown")
            return Intent.Unknown
        }
        // Confident classifier pick — trusted directly, EXCEPT for the
        // deterministic SetAlarm↔SetTimer correction. The embedding
        // confidently confuses the pair (and Whisper drops "timer"→"time"),
        // so a relative duration vs. a clock time overrides the pick. No-op
        // for every other intent.
        val finalIntent = PrefixHints.refineWorldTime(query, PrefixHints.refineAlarmTimer(query, classified.intent))
        if (finalIntent != classified.intent) {
            Log.d(TAG, "intent refine: ${classified.intent} → $finalIntent for \"$query\"")
        }
        return finalIntent
    }
}
