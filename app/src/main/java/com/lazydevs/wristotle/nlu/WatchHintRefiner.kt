// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.PrefixHints
import com.lazydevs.wristotle.speech.nlu.slots.*
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
 *      deterministic Time↔WorldTime correction applied to fix the
 *      embedding's known confusion when a location keyword is present.
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
        customAskAgentSubjects: List<String> = emptyList(),
    ): Intent? {
        // User-supplied "ask jarvis …" triggers run BEFORE PrefixHints so a
        // custom subject takes effect on every code path (watch-hinted and
        // unhinted alike). Default subjects are already covered by the
        // static PrefixHints rule; this pre-pass only kicks in when the
        // user has actually added an extra word.
        if (customAskAgentSubjects.isNotEmpty()) {
            customAskAgentRegex(customAskAgentSubjects).find(query)?.let {
                return Intent.AskAgent
            }
        }
        if (watchHint != null) return refineWatchHinted(classified, watchHint, query, routeThreshold)
        if (classified == null) return null
        return refineUnhinted(classified, query, routeThreshold, routeMargin)
    }

    /** Cache the route regex by extras-list identity so we don't recompile
     *  on every query. The list is short (typically 1-5 entries) and
     *  changes only when the user edits Settings. */
    @Volatile private var cachedExtras: List<String> = emptyList()
    @Volatile private var cachedRouteRegex: Regex =
        com.lazydevs.wristotle.speech.nlu.slots.AskAgentTriggers.routeRegex(emptyList())

    private fun customAskAgentRegex(extras: List<String>): Regex {
        if (extras == cachedExtras) return cachedRouteRegex
        val rebuilt = com.lazydevs.wristotle.speech.nlu.slots.AskAgentTriggers.routeRegex(extras)
        cachedExtras = extras
        cachedRouteRegex = rebuilt
        return rebuilt
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
        // The watch's keyword router also lumps every cancel-verb query into
        // CANCEL_QUERY (watchHint == Cancel). A query like "cancel the alarm"
        // therefore arrives with watchHint=Cancel even though the user wants
        // CancelAlarm. PrefixHints.refineCancelAlarm upgrades Cancel →
        // CancelAlarm whenever an `\balarm\b` token is present; no-op otherwise.
        val prefixHint = if (watchHint == Intent.Reminder) PrefixHints.hintFor(query) else null
        val refined = when {
            prefixHint != null && prefixHint in REMINDER_FAMILY -> prefixHint
            watchHint == Intent.Reminder &&
                classified != null &&
                classified.intent in REMINDER_FAMILY &&
                classified.confidence >= routeThreshold -> classified.intent
            watchHint == Intent.Cancel -> PrefixHints.refineCancelAlarm(query, watchHint)
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
                val refined = PrefixHints.refineWorldTime(query, hint)
                Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) → prefix hint $refined wins")
                return refined
            }
            Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) and no prefix hint → Unknown")
            return Intent.Unknown
        }
        // Confident classifier pick — trusted directly, with two post-pick
        // corrections:
        //   - Time → WorldTime when a location keyword is present (the bare
        //     time form is answered locally on the watch and never reaches
        //     us; a location-qualified query is unambiguously a world-clock
        //     lookup).
        //   - Cancel → CancelAlarm when the query has a whole-word "alarm"
        //     (the classifier confidently picks Cancel for the cancel-verb
        //     opener but Cancel's handler is reminder-only).
        val finalIntent = PrefixHints.refineWorldTime(query, PrefixHints.refineCancelAlarm(query, classified.intent))
        if (finalIntent != classified.intent) {
            Log.d(TAG, "intent refine: ${classified.intent} → $finalIntent for \"$query\"")
        }
        return finalIntent
    }
}