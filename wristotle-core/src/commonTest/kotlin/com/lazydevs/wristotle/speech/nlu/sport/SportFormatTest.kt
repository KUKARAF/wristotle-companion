// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs
package com.lazydevs.wristotle.speech.nlu.sport

import com.lazydevs.sportskapi.SportEvent
import com.lazydevs.sportskapi.SportEventStatus
import com.lazydevs.sportskapi.SportResult
import com.lazydevs.sportskapi.Standing
import com.lazydevs.sportskapi.StandingsResult
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportFormatTest {

    @Test fun lastDerivesWinFromScores() {
        val ev = SportEvent(
            homeName = "Warriors", awayName = "Celtics", title = "Celtics at Warriors",
            homeScore = 121, awayScore = 115, status = SportEventStatus.FINAL,
        )
        val out = SportFormat.last(SportResult.Ok(listOf(ev), "Golden State Warriors"), "Golden State Warriors")
        assertEquals("Warriors won 121-115\nvs Celtics", out)
    }

    @Test fun lastDerivesLossWhenSubjectIsAway() {
        val ev = SportEvent(
            homeName = "Lakers", awayName = "Warriors", title = "Warriors at Lakers",
            homeScore = 110, awayScore = 99, status = SportEventStatus.FINAL,
        )
        val out = SportFormat.last(SportResult.Ok(listOf(ev), "Warriors"), "Warriors")
        assertEquals("Warriors lost 99-110\nat Lakers", out)
    }

    @Test fun nextShowsOpponentAndPrep() {
        val ev = SportEvent(
            homeName = "Lakers", awayName = "Warriors", title = "Warriors at Lakers",
            status = SportEventStatus.SCHEDULED, kickoff = Instant.parse("2030-01-04T03:30:00Z"),
        )
        val out = SportFormat.next(SportResult.Ok(listOf(ev), "Warriors"), "Warriors")
        // away → "at Lakers"; date line is timezone-dependent so just assert shape.
        assertTrue(out.endsWith("at Lakers"), out)
    }

    @Test fun liveUsesProgressAndScores() {
        val ev = SportEvent(
            homeName = "Warriors", awayName = "Suns", title = "Suns at Warriors",
            homeScore = 78, awayScore = 74, status = SportEventStatus.LIVE, progress = "Q3 3:45",
        )
        val out = SportFormat.live(SportResult.Ok(listOf(ev), "Warriors"), "Warriors")
        assertEquals("Q3 3:45\nWarriors 78-74 Suns", out)
    }

    @Test fun standingsTopThreeWithPoints() {
        val table = listOf(
            Standing(1, "Arsenal", points = 85),
            Standing(2, "Man City", points = 82),
            Standing(3, "Liverpool", points = 78),
            Standing(4, "Spurs", points = 70),
        )
        val out = SportFormat.standings(StandingsResult.Ok(table, "Premier League"))
        assertEquals("Premier League\n1 Arsenal 85\n2 Man City 82\n3 Liverpool 78", out)
    }

    @Test fun standingsUsesRecordWhenNoPoints() {
        val table = listOf(Standing(1, "Warriors", record = "60-22"))
        val out = SportFormat.standings(StandingsResult.Ok(table, "NBA"))
        assertEquals("NBA\n1 Warriors 60-22", out)
    }

    @Test fun errorsRenderShortMessages() {
        assertEquals("Couldn't fetch sports. Try again.", SportFormat.next(SportResult.Network, "x"))
        assertEquals("No upcoming games.", SportFormat.next(SportResult.NotFound, "x"))
    }
}
