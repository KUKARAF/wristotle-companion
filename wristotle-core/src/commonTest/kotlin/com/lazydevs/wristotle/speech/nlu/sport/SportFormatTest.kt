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

    @Test fun standingsShowsRowsWithPoints() {
        val table = listOf(
            Standing(1, "Arsenal", points = 85),
            Standing(2, "Man City", points = 82),
            Standing(3, "Liverpool", points = 78),
            Standing(4, "Spurs", points = 70),
        )
        val out = SportFormat.standings(StandingsResult.Ok(table, "Premier League"))
        assertEquals(
            "Premier League\n1. Arsenal (85)\n2. Man City (82)\n3. Liverpool (78)\n4. Spurs (70)",
            out,
        )
    }

    @Test fun standingsUsesRecordWhenNoPoints() {
        val table = listOf(
            Standing(1, "Warriors", record = "60-22"),
            Standing(2, "Lakers", record = "55-27"),
        )
        val out = SportFormat.standings(StandingsResult.Ok(table, "NBA"))
        assertEquals("NBA\n1. Warriors (60-22)\n2. Lakers (55-27)", out)
    }

    @Test fun standingsLeadersViewDropsRedundantRank() {
        // Every row is a division leader (all rank 1) → no leading "1." noise;
        // the division-prefixed name carries the row, stat in parens.
        val table = listOf(
            Standing(1, "AL East New York Yankees", record = "50-30"),
            Standing(1, "AL West Houston Astros", record = "46-34"),
        )
        val out = SportFormat.standings(StandingsResult.Ok(table, "MLB"))
        assertEquals(
            "MLB\nAL East New York Yankees (50-30)\nAL West Houston Astros (46-34)",
            out,
        )
    }

    @Test fun lastUsesResultLineForRaces() {
        // F1: no two-sided score — the pre-formatted result line is shown.
        val race = SportEvent(
            homeName = "", awayName = "", title = "British Grand Prix",
            status = SportEventStatus.FINAL, result = "Won by Max Verstappen",
        )
        val out = SportFormat.last(SportResult.Ok(listOf(race), "Formula 1"), "Formula 1")
        assertEquals("British Grand Prix\nWon by Max Verstappen", out)
    }

    @Test fun nextRaceShowsDateAndName() {
        val race = SportEvent(
            homeName = "", awayName = "", title = "Monaco Grand Prix",
            status = SportEventStatus.SCHEDULED, kickoff = null,
        )
        val out = SportFormat.next(SportResult.Ok(listOf(race), "Formula 1"), "Formula 1")
        assertEquals("Date TBD\nMonaco Grand Prix", out)
    }

    @Test fun standingsCapLongTablesAtTen() {
        val table = (1..22).map { Standing(it, "Driver $it", points = 200 - it) }
        val out = SportFormat.standings(StandingsResult.Ok(table, "Driver Standings"))
        assertEquals(10, out.lines().size - 1) // minus the header line
    }

    @Test fun constructorStandingsShowAll() {
        val table = (1..11).map { Standing(it, "Team $it", points = 300 - it) }
        val out = SportFormat.standings(StandingsResult.Ok(table, "Constructor Standings"))
        assertEquals(11, out.lines().size - 1) // all 11 constructors, not capped at 10
    }

    @Test fun errorsRenderShortMessages() {
        assertEquals("Couldn't fetch sports. Try again.", SportFormat.next(SportResult.Network, "x"))
        assertEquals("Manchester City has no upcoming games.", SportFormat.next(SportResult.NotFound, "Manchester City"))
        assertEquals("Manchester City has no recent games.", SportFormat.last(SportResult.NotFound, "Manchester City"))
    }
}
