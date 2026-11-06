package de.nbeck.aapshc

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.ns.Treatment
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.ExerciseInfo
import de.nbeck.aapshc.sync.Health
import de.nbeck.aapshc.sync.Mapper
import de.nbeck.aapshc.sync.Notifier
import de.nbeck.aapshc.sync.PeriodInfo
import de.nbeck.aapshc.sync.TemperatureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HealthTest {
    private val t0 = Instant.parse("2026-11-05T16:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    @Test
    fun zonesFromSamples() {
        // Max 200: 110 bpm = 55 % (Z1), 150 = 75 % (Z3), 185 = 92,5 % (Z5), 90 = 45 % (keine Zone)
        val samples = listOf(at(0) to 110L, at(1) to 150L, at(2) to 185L, at(3) to 90L)
        assertEquals(listOf(1L, 0L, 1L, 0L, 1L), Health.zoneMinutes(samples, at(4), 200))
    }

    @Test
    fun zoneGapsAreCapped() {
        // Ein einzelner Wert mit 30 min Lücke zählt höchstens 2 min.
        assertEquals(listOf(0L, 0L, 2L, 0L, 0L), Health.zoneMinutes(listOf(at(0) to 150L), at(30), 200))
    }

    @Test
    fun zonesInNotes() {
        val e = ExerciseInfo("x", t0, at(45), ZoneOffset.UTC, "Laufen", null, "Garmin", null, null, null, null, null,
            zoneMinutes = listOf(0, 12, 20, 8, 0))
        assertEquals("Laufen · Zonen Z2 12 / Z3 20 / Z4 8 min (Garmin)", Mapper.exercise(e).notes)
    }

    @Test
    fun periodDaysMerge() {
        val d = LocalDate.of(2026, 10, 1)
        val days = listOf(d, d.plusDays(1), d.plusDays(3), d.plusDays(4), d.plusDays(28), d.plusDays(29))
        val ranges = Health.mergePeriodDays(days)
        assertEquals(2, ranges.size)
        assertEquals(d..d.plusDays(4), ranges[0])
        assertEquals(d.plusDays(28)..d.plusDays(29), ranges[1])
    }

    @Test
    fun periodNote() {
        val d = LocalDate.of(2026, 10, 1)
        val t = Mapper.period(PeriodInfo(d, d.plusDays(4), t0, ZoneOffset.UTC))
        assertEquals(Treatment.NOTE, t.eventType)
        assertEquals(5L * 24 * 60, t.durationMinutes)
        assertEquals("Periode, Tag 1–5", t.notes)
    }

    @Test
    fun feverNote() {
        val t = Mapper.temperature(TemperatureInfo("r", t0, ZoneOffset.UTC, 38.24, "Ohr", "Withings"))
        assertEquals("Erhöhte Temperatur 38,2 °C (Ohr) – Insulinbedarf kann steigen (Withings)", t.notes)
    }

    @Test
    fun analysisFindsLateHypo() {
        val values = listOf(
            GlucoseValue(at(0).toEpochMilli(), 160),
            GlucoseValue(at(30).toEpochMilli(), 120),
            GlucoseValue(at(60).toEpochMilli(), 105),
            // nachts 20 min unter 70 = eine Hypo-Episode
            GlucoseValue(at(60 * 8).toEpochMilli(), 64),
            GlucoseValue(at(60 * 8 + 5).toEpochMilli(), 62),
            GlucoseValue(at(60 * 8 + 10).toEpochMilli(), 63),
            GlucoseValue(at(60 * 8 + 15).toEpochMilli(), 66),
        )
        val r = Analysis.evaluate("Laufen", t0, at(60), values)
        assertEquals(160, r.startMgdl)
        assertEquals(105, r.endMgdl)
        assertEquals(-55, r.changeDuring)
        assertEquals(62, r.minAfter)
        assertTrue(r.hypoAfter)
        assertFalse(r.hypoDuring)
    }

    @Test
    fun singleLowReadingIsNoHypoEvent() {
        // Ein Ausreißer (z. B. Druck auf den Sensor) und 10 min unter 70 sind keine Episode.
        val blip = listOf(GlucoseValue(at(0).toEpochMilli(), 55))
        val short = (0L..5L step 5).map { GlucoseValue(at(it).toEpochMilli(), 65) }
        assertEquals(0, Analysis.hypoEvents(blip))
        assertEquals(0, Analysis.hypoEvents(short))
    }

    @Test
    fun hypoEventsCountSeparately() {
        val first = (0L..10L step 5).map { GlucoseValue(at(it).toEpochMilli(), 60) }       // 15 min
        val recovery = listOf(GlucoseValue(at(20).toEpochMilli(), 95))
        val second = (40L..70L step 5).map { GlucoseValue(at(it).toEpochMilli(), 58) }     // 35 min
        assertEquals(2, Analysis.hypoEvents(first + recovery + second))
    }

    @Test
    fun cgmGapSplitsEpisode() {
        // Zwei Werte mit 30 min Lücke dazwischen sind keine durchgehende Episode.
        val values = listOf(GlucoseValue(at(0).toEpochMilli(), 60), GlucoseValue(at(30).toEpochMilli(), 60))
        assertEquals(0, Analysis.hypoEvents(values))
    }

    @Test
    fun analysisWithoutCgm() {
        val r = Analysis.evaluate("Yoga", t0, at(30), emptyList())
        assertEquals(null, r.changeDuring)
        assertFalse(r.hypoAfter)
    }

    @Test
    fun hypoHintUsesHistoryOnlyWithEnoughData() {
        val e = ExerciseInfo("x", t0, at(60), ZoneOffset.UTC, "Laufen", null, "Garmin", null, null, null, null, null)
        val hypo = Analysis.evaluate("Laufen", t0, at(60), (0L..15L step 5).map { GlucoseValue(at(120 + it).toEpochMilli(), 60) })
        val fine = Analysis.evaluate("Laufen", t0, at(60), listOf(GlucoseValue(at(120).toEpochMilli(), 110)))
        assertFalse(Notifier.hypoRiskText(e, listOf(hypo, fine)).contains("von"))
        assertTrue(Notifier.hypoRiskText(e, listOf(hypo, fine, hypo)).contains("Nach 2 von 3 ähnlichen Trainings"))
    }
}
