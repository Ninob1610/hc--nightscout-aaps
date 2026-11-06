package de.nbeck.aapshc

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.AnalysisReport
import de.nbeck.aapshc.sync.CsvExport
import de.nbeck.aapshc.sync.NightAnalysis
import de.nbeck.aapshc.sync.WeeklyReportWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class InsightsTest {
    private val zone = ZoneOffset.UTC
    private fun at(iso: String) = Instant.parse(iso)

    private fun exercise(startIso: String, hypo: Boolean) = Analysis.evaluate(
        "Laufen", at(startIso), at(startIso).plusSeconds(3600),
        (0L..15L step 5).map { GlucoseValue(at(startIso).plusSeconds(7200 + it * 60).toEpochMilli(), if (hypo) 60 else 120) },
    )

    @Test
    fun timeOfDayBucketsInFixedOrder() {
        val items = listOf(
            exercise("2026-11-01T19:00:00Z", hypo = true),
            exercise("2026-11-02T18:00:00Z", hypo = true),
            exercise("2026-11-03T07:00:00Z", hypo = false),
        )
        val slots = Analysis.byTimeOfDay(items, zone)
        assertEquals(listOf(Analysis.TimeOfDay.MORNING, Analysis.TimeOfDay.EVENING), slots.map { it.slot })
        assertEquals(100, slots[1].hypoRate)
        assertEquals(0, slots[0].hypoRate)
    }

    private fun night(startIso: String, mgdl: (Int) -> Int, deep: Long? = null): de.nbeck.aapshc.sync.NightGlucose {
        val start = at(startIso)
        val values = (0..480 step 5).map { GlucoseValue(start.plusSeconds(it * 60L).toEpochMilli(), mgdl(it)) }
        return NightAnalysis.evaluate(start, start.plusSeconds(480 * 60), 480, values, 0, deep)
    }

    @Test
    fun profileUsesPercentilesAcrossNights() {
        val nights = listOf(
            night("2026-11-01T22:00:00Z", { 100 }),
            night("2026-11-02T22:00:00Z", { 120 }),
            night("2026-11-03T23:00:00Z", { 140 }),
        )
        val p = NightAnalysis.profile(nights)
        assertEquals(0, p.first().minute)
        assertEquals(120, p.first().median)
        assertEquals(110, p.first().p25)
        assertEquals(130, p.first().p75)
        assertEquals(3, p.first().nights)
    }

    @Test
    fun profileNeedsThreeNights() {
        assertTrue(NightAnalysis.profile(listOf(night("2026-11-01T22:00:00Z", { 100 }))).isEmpty())
    }

    @Test
    fun deepSleepSplitAtMedian() {
        val nights = listOf(
            night("2026-11-01T22:00:00Z", { 110 }, deep = 120), // 25 %
            night("2026-11-02T22:00:00Z", { 115 }, deep = 110),
            night("2026-11-03T22:00:00Z", { 190 }, deep = 30),  // ~6 %
            night("2026-11-04T22:00:00Z", { 200 }, deep = 40),
        )
        val groups = NightAnalysis.byDeepSleep(nights)
        assertEquals(2, groups.size)
        assertEquals(100, groups[0].avgTimeInRange) // viel Tiefschlaf
        assertEquals(0, groups[1].avgTimeInRange)
    }

    @Test
    fun weeklyTextComparesWithPreviousWeek() {
        val week = NightAnalysis.WeekSummary(nights = 7, avgTimeInRange = 81, hypoNights = 1, exerciseMinutes = 250, exerciseSessions = 5)
        val prev = week.copy(avgTimeInRange = 76)
        assertEquals(
            "Nächte im Ziel Ø 81 % (Vorwoche 76 %) · 1 Hypo-Nächte · Sport 4 h 10 min in 5 Einheiten",
            WeeklyReportWorker.weeklyText(week, prev),
        )
        assertTrue(WeeklyReportWorker.weeklyText(week.copy(hypoNights = 0), null).contains("keine Hypo-Nacht"))
    }

    @Test
    fun csvHasHeaderBomAndRows() {
        val report = AnalysisReport(
            items = listOf(exercise("2026-11-01T19:00:00Z", hypo = true)),
            summaries = emptyList(),
            nights = listOf(night("2026-11-01T22:00:00Z", { 110 }, deep = 90)),
        )
        val csv = CsvExport.build(report, zone)
        val lines = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertTrue(csv.startsWith("﻿"))
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Typ;Beginn;Ende"))
        assertTrue(lines[1], lines[1].startsWith("Training;01.11.2026 19:00;01.11.2026 20:00;Laufen;60;"))
        assertTrue(lines[2], lines[2].startsWith("Nacht;01.11.2026 22:00;02.11.2026 06:00;Schlaf;480;110;110;110;110;110;100;0;0;nein"))
        assertEquals(24, lines[2].split(";").size)
    }
}
