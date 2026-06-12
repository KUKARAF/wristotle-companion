// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The single time-resolution entry point for slot extractors: parse [query] to a
 * wall-clock time and roll it to its next occurrence in one step.
 *
 * Every CRUD intent that reads a clock time — create (Reminder / CreateEvent),
 * update (Reschedule), delete (CancelAlarm), query (ListReminders) — goes through
 * here, so "at 8" resolves identically everywhere ("next 8 o'clock") and an intent
 * can't be silently opted out by forgetting to roll. Returns null when the query
 * names no time.
 *
 * One documented exception: [SetAlarmSlots] must strip its verb prefix before
 * parsing ("set an alarm for an hour from now" confuses prettytime otherwise), so
 * it parses a stripped string and applies [rolledToNextFutureOccurrence] directly
 * — same roll, different parse input.
 */
fun TimeParser.resolveClockTime(query: String, clock: Clock): Instant? =
    parse(query)?.instant?.rolledToNextFutureOccurrence(clock.now(), query)

/**
 * Roll a clock time that has already passed onto its next occurrence.
 *
 * Time-of-day parsing resolves a bare clock time onto *today's* date, so a time
 * dictated after it has passed comes back in the past, and a forward-looking
 * scheduler ([ReminderSlots] issue #13, [CreateEventSlots]) would schedule in
 * the past. Already-future instants are returned untouched.
 *
 * The step size depends on whether the user disambiguated AM/PM
 * ([ambiguousMeridiem]):
 *
 *  - **Explicit meridiem** ("at 1 **a.m.**") → advance whole calendar days. At
 *    8 p.m., "1 a.m." becomes *tomorrow* 1 a.m. — NOT today's 1 p.m. The user
 *    named that specific half of the day, so only the date moves.
 *  - **Bare hour** ("at 8") → advance 12 h at a time, i.e. the next time the
 *    12-hour dial shows that hour. At 9 a.m., "8" becomes *8 p.m. today*; at
 *    9 p.m. it becomes 8 a.m. tomorrow. This matches how people read a bare
 *    hour ("remind me at 8" = the next 8 o'clock), and is robust to whichever
 *    half the parser happened to pick.
 *
 * Calendar-day steps use [DateTimeUnit.DAY] so the wall-clock time survives a
 * DST boundary. The 12-hour step is a fixed duration; on the two DST-transition
 * days a year it can land an hour off, which is acceptable for a "next 8
 * o'clock" reminder. Each step advances ≥ ~11 h, so the loop always terminates.
 */
fun Instant.rolledToNextFutureOccurrence(
    now: Instant,
    zone: TimeZone,
    ambiguousMeridiem: Boolean,
): Instant {
    if (this >= now) return this
    var result = this
    if (ambiguousMeridiem) {
        while (result < now) result = result.plus(12, DateTimeUnit.HOUR)
    } else {
        while (result < now) result = result.plus(1, DateTimeUnit.DAY, zone)
    }
    return result
}

/**
 * Convenience overload for the scheduling slot extractors: rolls [this] using
 * the system zone and infers meridiem ambiguity from [query], so each caller
 * doesn't repeat the `TimeZone.currentSystemDefault()` + `queryHasExplicitMeridiem`
 * boilerplate. Shared by ReminderSlots, CreateEventSlots, SetAlarmSlots and
 * RescheduleSlots. The 3-arg primitive stays for tests that pin the zone.
 */
fun Instant.rolledToNextFutureOccurrence(now: Instant, query: String): Instant =
    rolledToNextFutureOccurrence(
        now,
        TimeZone.currentSystemDefault(),
        ambiguousMeridiem = !queryHasExplicitMeridiem(query),
    )

/**
 * True if [query] disambiguates the half of the day a clock time falls in —
 * "a.m." / "p.m.", or a day-part word ("noon", "tonight", "in the morning").
 * A bare hour ("at 8", "8 o'clock") is ambiguous and returns false.
 */
fun queryHasExplicitMeridiem(query: String): Boolean = EXPLICIT_MERIDIEM.containsMatchIn(query)

// The meridiem alternative uses a letter-only negative lookbehind rather than a
// leading \b so it matches a digit-glued form ("8pm") yet still rejects "am"
// buried in a word ("exam", "spam"). The trailing \b rejects "ample"/"amazon".
private val EXPLICIT_MERIDIEM = Regex(
    """(?i)((?<![A-Za-z])[ap]\.?m\b\.?|\bnoon\b|\bmidnight\b|\bmorning\b|\bafternoon\b|\bevening\b|\btonight\b|\bnight\b)""",
)
