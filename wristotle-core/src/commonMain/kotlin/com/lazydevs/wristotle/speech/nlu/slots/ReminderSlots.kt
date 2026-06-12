// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Reminder]:
 *   - `time`  — parsed [Instant], from the injected [TimeParser]
 *               (Android: PrettyTime + word-form numbers). Defaults
 *               to now + [defaultOffsetMinProvider] minutes (configured
 *               in Settings → Reminders, default 30) when the user
 *               didn't say a time ("remind me to buy milk"). The confirm
 *               prompt surfaces the defaulted time so the user can
 *               cancel + re-dictate with an explicit time if needed.
 *   - `title` — the reminder body, with the reminder-prefix and any
 *               recognised time phrase stripped, then capitalised. If
 *               the title strips to nothing, the handler falls back to
 *               capitalizing the original query.
 *
 * The default-offset provider stays a lambda so the slot remains
 * Android-free for unit testing (see `ReminderSlotsTest`); the
 * production wiring in WristotleApplication reads
 * `ReminderSettings.defaultOffsetMin` per call.
 *
 * R2 batch 4 — lifted from :app; [TimeParser] injected via constructor.
 */
class ReminderSlots(
    private val timeParser: TimeParser,
    private val defaultOffsetMinProvider: () -> Int,
    private val clock: Clock = Clock.System,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        // A clock time the user named resolves onto today's date, which may
        // already be in the past — roll it to the next occurrence so the
        // reminder doesn't fire in the past (issue #13). A bare hour ("at 8")
        // rolls to the next 8 o'clock; an explicit "1 a.m." rolls to tomorrow.
        out[SlotKeys.Time] = timeParser.parse(query)?.instant
            ?.rolledToNextFutureOccurrence(
                clock.now(),
                TimeZone.currentSystemDefault(),
                ambiguousMeridiem = !queryHasExplicitMeridiem(query),
            )
            ?: defaultedInstant()
        if (DETECT_PERSISTENT.containsMatchIn(query)) out[SlotKeys.Persistent] = true
        val title = buildTitle(query)
        if (title.isNotBlank()) out[SlotKeys.Title] = title
        return out
    }

    private fun defaultedInstant(): Instant {
        val nowMs = clock.now().toEpochMilliseconds()
        return Instant.fromEpochMilliseconds(nowMs + defaultOffsetMinProvider() * 60_000L)
    }

    private fun buildTitle(transcription: String): String {
        val stripped = transcription
            // Drop the "persistent" / "persistently" modifier first so the
            // existing reminder prefixes can still match what follows ("persistent
            // reminder to call mom" → "reminder to call mom" → "Call mom").
            .replace(STRIP_PERSISTENT_MODIFIER, "")
            .replace(STRIP_PREFIXES, "")
            .replace(STRIP_LEADING_TIME_THEN_TO, "")
            .replace(STRIP_TIME_PHRASES, "")
            .trim()
        // A time-only reminder ("set a reminder for 8 p.m.") strips down to a
        // bare time token the trailing-clause regex can't reach (its preposition
        // was eaten by STRIP_PREFIXES). Drop it so the title doesn't become
        // "8pm"; the handler defaults the title when this slot is absent.
        if (isOnlyTimeExpression(stripped)) return ""
        return stripped.replaceFirstChar { it.uppercaseChar() }
    }

    private companion object {
        // `\b` wraps the optional connectors so "remind me to call" strips
        // the lead-in "to" but "remind me tomorrow" does NOT eat the "to"
        // hidden inside "tomorrow" — issue #4 surfaced this bug while
        // chasing the leading-time-clause case.
        val STRIP_PREFIXES = Regex(
            """(?i)^(remind me (\bto\b|\babout\b|\bthat\b)?|reminder (\bto\b|\babout\b)?|set a reminder (\bto\b|\bfor\b)?|set an? alarm (\bfor\b|\bto\b)?|schedule a reminder (\bfor\b|\bto\b)?|nag me (\bto\b|\babout\b)?|keep (reminding|nagging) me (\bto\b|\babout\b)?|wake me up|tell me when|ping me|buzz me)\s*""",
        )
        // Persistent-mode triggers. Phrase shapes:
        //  - "persistent reminder to X" / "persistently remind me to X"
        //  - "nag me to X" / "keep reminding me to X" / "keep nagging me to X"
        val DETECT_PERSISTENT = Regex(
            """(?i)\b(persistent(?:ly)?|nag me|keep (reminding|nagging) me)\b""",
        )
        // Strips the "persistent" / "persistently" modifier — including any
        // trailing whitespace so the residual collapses cleanly into the
        // existing prefix regex.
        val STRIP_PERSISTENT_MODIFIER = Regex("""(?i)\bpersistent(?:ly)?\b\s*""")
        // Handles the "remind me <time> to <task>" shape (issue #4):
        // after STRIP_PREFIXES drops "remind me ", the residual is
        // "in two hours to check the tables", and STRIP_TIME_PHRASES
        // (anchored at end-of-string with a leading \s+) can't reach a
        // time clause that sits at the START. This regex peels a
        // leading time lead-in up to the task-introducing "to ".
        val STRIP_LEADING_TIME_THEN_TO = Regex(
            """(?i)^\s*\b(in|at|by|on|tomorrow|next|this|every|later|tonight)\b[\w\s:.,]*?\bto\b\s+""",
        )
        // Note: "to" is intentionally NOT a lead-in — "remind me TO call" uses
        // "to" to introduce the task, not a time.
        val STRIP_TIME_PHRASES = trailingTimeClauseRegex(
            setOf("at", "in", "by", "on", "next", "this", "every"),
        )
    }
}
