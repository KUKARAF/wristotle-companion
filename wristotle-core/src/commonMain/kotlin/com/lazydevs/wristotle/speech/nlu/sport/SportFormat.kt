// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.sport

import com.lazydevs.sportskapi.SportEvent
import com.lazydevs.sportskapi.SportResult
import com.lazydevs.sportskapi.StandingsResult
import com.lazydevs.wristotle.speech.nlu.calendar.EventTimeFormat

/**
 * Pure, watch-friendly formatting of the (provider-neutral) sports domain into
 * short 1–3 line strings. No Android, no network, no provider knowledge — so it
 * unit-tests on the JVM + iOS Sim like the other commonMain formatters.
 *
 *   next      → "Sat May 24 7:30 PM\nvs Lakers"
 *   last      → "Warriors won 121-115\nvs Celtics"
 *   live      → "Q3 3:45\nWarriors 78-74 Suns"
 *   standings → "Premier League\n1 Arsenal 85\n2 Man City 82"
 */
object SportFormat {

    const val NO_SUBJECT = "Name a team, or add a favorite in Settings → Sports."
    const val NOT_FOUND = "Couldn't find that team."
    private const val NONE_UPCOMING = "No upcoming games."
    private const val NONE_RECENT = "No recent games."
    private const val NO_LIVE = "No live game right now."
    private const val NETWORK = "Couldn't fetch sports. Try again."
    private const val UNSUPPORTED = "Not supported yet."

    fun next(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { upcoming(it, subjectName) } ?: NONE_UPCOMING
        SportResult.NotFound -> NONE_UPCOMING
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun last(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { finalScore(it, subjectName) } ?: NONE_RECENT
        SportResult.NotFound -> NONE_RECENT
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun live(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { liveScore(it) } ?: NO_LIVE
        SportResult.NotFound -> NO_LIVE
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun standings(result: StandingsResult): String = when (result) {
        is StandingsResult.Ok -> {
            // Up to 8 rows — covers a league's full set of division leaders
            // (NFL has 8) or a single league's top 8.
            val rows = result.table.take(8).joinToString("\n") { s ->
                "${s.rank} ${s.team} ${s.points ?: s.record ?: ""}".trim()
            }
            if (rows.isEmpty()) NONE_RECENT else "${result.league}\n$rows"
        }
        StandingsResult.NotFound -> "No standings available."
        StandingsResult.Network -> NETWORK
        StandingsResult.Unsupported -> UNSUPPORTED
    }

    // --- helpers ---

    private fun upcoming(e: SportEvent, subject: String): String {
        val whenStr = e.kickoff?.let {
            val ms = it.toEpochMilliseconds()
            "${EventTimeFormat.day(ms)} ${EventTimeFormat.time(ms)}"
        } ?: "Date TBD"
        if (e.homeName.isBlank() && e.awayName.isBlank()) return "$whenStr\n${e.title}"
        val home = subjectIsHome(e, subject)
        val opponent = if (home == false) e.homeName else e.awayName
        val prep = if (home == false) "at" else "vs"
        return "$whenStr\n$prep $opponent"
    }

    private fun finalScore(e: SportEvent, subject: String): String {
        if (e.homeName.isBlank() && e.awayName.isBlank()) return e.title
        val home = subjectIsHome(e, subject)
        val name = if (home == false) e.awayName else e.homeName
        val mine = if (home == false) e.awayScore else e.homeScore
        val theirs = if (home == false) e.homeScore else e.awayScore
        val opponent = if (home == false) e.homeName else e.awayName
        val prep = if (home == false) "at" else "vs"
        val line1 = if (mine != null && theirs != null) {
            val verb = when { mine > theirs -> "won"; mine < theirs -> "lost"; else -> "drew" }
            "$name $verb $mine-$theirs"
        } else {
            "$name ${e.homeScore ?: "-"}-${e.awayScore ?: "-"}"
        }
        return "$line1\n$prep $opponent"
    }

    private fun liveScore(e: SportEvent): String {
        val prog = e.progress ?: "Live"
        val h = e.homeScore?.toString() ?: "-"
        val a = e.awayScore?.toString() ?: "-"
        return "$prog\n${e.homeName} $h-$a ${e.awayName}"
    }

    /** true = subject is home, false = away, null = couldn't tell. */
    private fun subjectIsHome(e: SportEvent, subject: String): Boolean? {
        fun matches(side: String) =
            side.isNotBlank() && (subject.contains(side, ignoreCase = true) || side.contains(subject, ignoreCase = true))
        return when {
            matches(e.homeName) -> true
            matches(e.awayName) -> false
            else -> null
        }
    }
}
