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
    private const val NETWORK = "Couldn't fetch sports. Try again."
    private const val UNSUPPORTED = "Not supported yet."

    fun next(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { upcoming(it, subjectName) }
            ?: "$subjectName has no upcoming games."
        SportResult.NotFound -> "$subjectName has no upcoming games."
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun last(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { finalScore(it, subjectName) }
            ?: "$subjectName has no recent games."
        SportResult.NotFound -> "$subjectName has no recent games."
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun live(result: SportResult, subjectName: String): String = when (result) {
        is SportResult.Ok -> result.events.firstOrNull()?.let { liveScore(it) }
            ?: "$subjectName isn't playing right now."
        SportResult.NotFound -> "$subjectName isn't playing right now."
        SportResult.Network -> NETWORK
        SportResult.Unsupported -> UNSUPPORTED
    }

    fun standings(result: StandingsResult): String = when (result) {
        is StandingsResult.Ok -> {
            // Up to 8 rows — covers a league's full set of division leaders
            // (NFL has 8) or a single league's top 8.
            val rows = result.table.take(8)
            // All rows sharing one rank ⇒ a "division leaders" view (every row
            // IS a #1), so the leading rank on each line is noise — drop it and
            // let the division-prefixed team name carry the row. Otherwise show
            // "1." as an ordinal. The stat (points or W-L record) goes in parens
            // so the three values (rank / team / stat) are visually distinct on
            // the watch instead of three bare numbers running together.
            val leaders = rows.size > 1 && rows.map { it.rank }.distinct().size == 1
            val body = rows.joinToString("\n") { s ->
                val stat = s.points?.toString() ?: s.record
                val name = if (leaders) s.team else "${s.rank}. ${s.team}"
                if (stat == null) name else "$name ($stat)"
            }
            if (body.isBlank()) "No standings available." else "${result.league}\n$body"
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
        // Sports without two numeric scores (F1 winner, cricket innings) carry a
        // pre-formatted result line.
        e.result?.let { return "${e.title}\n$it" }
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
        e.result?.let { return "${e.progress ?: e.title}\n$it" }
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
