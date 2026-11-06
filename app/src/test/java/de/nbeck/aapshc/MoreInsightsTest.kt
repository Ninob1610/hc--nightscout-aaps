package de.nbeck.aapshc

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.ns.TherapyItem
import de.nbeck.aapshc.sync.AnalysisReport
import de.nbeck.aapshc.sync.CsvExport
import de.nbeck.aapshc.sync.DailyVitals
import de.nbeck.aapshc.sync.NightAnalysis
import de.nbeck.aapshc.sync.NightFactors
import de.nbeck.aapshc.sync.NightGlucose
import de.nbeck.aapshc.sync.Vitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MoreInsightsTest {
    private val d0 = LocalDate.of(2026, 10, 1)

    private fun baseline(days: Int = 14) = (0 until days).map { DailyVitals(d0.plusDays(it.toLong()), 54.0 + (it % 3) - 1, 44.0) }

    @Test
    fun restingHeartRateRiseIsFlagged() {
        val days = baseline() + DailyVitals(d0.plusDays(14), 61.0, 40.0)
        val alert = Vitals.alerts(days).single()
        assertEquals(d0.plusDays(14), alert.day)
        assertEquals(61, alert.restingHr)
        assertEquals(54, alert.baselineHr)
    }

    @Test
    fun smallRiseNeedsSecondSign() {
        val normalHrv = baseline() + DailyVitals(d0.plusDays(14), 58.0, 43.0)
        val lowHrv = baseline() + DailyVitals(d0.plusDays(14), 58.0, 30.0)
        assertTrue(Vitals.alerts(normalHrv).isEmpty())
        assertEquals(1, Vitals.alerts(lowHrv).size)
        assertEquals(1, Vitals.alerts(normalHrv, feverDays = setOf(d0.plusDays(14))).size)
    }

    @Test
    fun noAlertWithoutEnoughBaseline() {
        val days = baseline(3) + DailyVitals(d0.plusDays(3), 70.0, 20.0)
        assertTrue(Vitals.alerts(days).isEmpty())
    }

    @Test
    fun vitalsNote() {
        val a = Vitals.alerts(baseline() + DailyVitals(d0.plusDays(14), 62.0, 29.0)).single()
        val t = Vitals.treatment(a, ZoneOffset.UTC, now = Instant.parse("2026-11-01T00:00:00Z"))
        assertEquals("Ruhepuls erhöht: 62 bpm (sonst 54) · HRV 29 ms (sonst 44) – möglicher Infekt, Insulinbedarf kann steigen", t.notes)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z").toEpochMilli(), t.date)
    }

    private fun night(day: Int, hypo: Boolean, sport: Long, target: Pair<Int, String>?): NightGlucose {
        val start = Instant.parse("2026-10-%02dT22:30:00Z".format(day))
        val values = (0..480 step 5).map { m ->
            val v = if (!hypo) 120 else when { m < 60 -> 120; m < 120 -> 120 - (m - 60); m < 160 -> 60; m < 250 -> 60 + (m - 160) * 2 / 3; else -> 120 }
            GlucoseValue(start.plusSeconds(m * 60L).toEpochMilli(), v)
        }
        val therapy = target?.let { (mgdl, reason) -> listOf(TherapyItem.TempTarget(start.minusSeconds(3600).toEpochMilli(), 300, mgdl, reason)) }.orEmpty()
        return NightAnalysis.evaluate(start, start.plusSeconds(480 * 60), 480, values, sport, therapy = therapy)
    }

    @Test
    fun plannedTargetComparedAfterSport() {
        val nights = listOf(
            night(1, false, 60, 140 to "Activity"), night(2, false, 60, 140 to "Activity"), night(3, true, 60, 140 to "Activity"),
            night(4, true, 60, null), night(5, true, 60, null), night(6, false, 60, null),
            night(7, true, 0, 135 to "Automation"), // Reaktion, nicht gewertet
        )
        val effect = NightFactors.tempTargetEffect(nights)!!
        assertTrue(effect.afterSport)
        assertEquals(1, effect.reactiveNights)
        assertEquals(1, effect.groups[0].hypoNights)
        assertEquals(2, effect.groups[1].hypoNights)
    }

    @Test
    fun targetEffectNeedsGroups() {
        assertNull(NightFactors.tempTargetEffect(listOf(night(1, false, 60, 140 to "Activity"), night(2, true, 0, null))))
    }

    @Test
    fun mmolFormatting() {
        assertEquals("3,9", GlucoseUnit.MMOL.value(70))
        assertEquals("10,0", GlucoseUnit.MMOL.value(180))
        assertEquals("3,9–10,0", GlucoseUnit.MMOL.range)
        assertEquals("+1,6", GlucoseUnit.MMOL.delta(28))
        assertEquals("−2,1", GlucoseUnit.MMOL.delta(-38))
        assertEquals("118 mg/dl", GlucoseUnit.MGDL.withUnit(118))
    }

    @Test
    fun csvInMmol() {
        val n = night(1, false, 0, null)
        val csv = CsvExport.build(AnalysisReport(emptyList(), emptyList(), listOf(n)), ZoneOffset.UTC, GlucoseUnit.MMOL)
        val lines = csv.trimEnd().split("\r\n")
        assertTrue(lines[0], lines[0].contains("Glukose Beginn (mmol/l)"))
        assertTrue(lines[1], lines[1].contains(";6,7;6,7;6,7;6,7;6,7;"))
    }
}
