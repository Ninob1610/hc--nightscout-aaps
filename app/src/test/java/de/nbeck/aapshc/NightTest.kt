package de.nbeck.aapshc

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.sync.NightAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NightTest {
    private val t0 = Instant.parse("2026-11-04T22:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    /** 8 h Nacht, alle 5 min ein Wert, erzeugt aus [mgdl] je Minute. */
    private fun night(mgdl: (Long) -> Int) = (0L..480L step 5).map { GlucoseValue(at(it).toEpochMilli(), mgdl(it)) }

    @Test
    fun hypoAndDawnRise() {
        // Fällt bis 3 Uhr auf 60, steigt dann bis zum Aufwachen auf 150.
        val values = night { m -> if (m < 300) (130 - m / 5).toInt().coerceAtLeast(60) else (60 + (m - 300) / 2).toInt() }
        val n = NightAnalysis.evaluate(t0, at(480), 450, values, exerciseMinutesBefore = 60)
        assertEquals(60, n.min)
        assertEquals(90, n.dawnRise) // 150 beim Aufwachen − 60 Tiefstwert
        assertTrue(n.hadHypo)
        assertTrue(n.minutesBelow >= 5)
    }

    @Test
    fun timeInRange() {
        val values = night { m -> if (m < 240) 120 else 200 }
        val n = NightAnalysis.evaluate(t0, at(480), 480, values, 0)
        assertEquals(49, n.timeInRange) // 48 von 97 Werten im Ziel
        assertEquals(49L * 5, n.minutesAbove)
    }

    @Test
    fun summaryComparesSportNights() {
        val hypoNight = NightAnalysis.evaluate(t0, at(480), 480, night { 65 }, exerciseMinutesBefore = 90)
        val calmNight = NightAnalysis.evaluate(t0, at(480), 480, night { 110 }, exerciseMinutesBefore = 0)
        val tooFew = NightAnalysis.evaluate(t0, at(480), 480, listOf(GlucoseValue(at(10).toEpochMilli(), 50)), 0)
        val summaries = NightAnalysis.summarize(listOf(hypoNight, calmNight, tooFew)).associateBy { it.label }
        assertEquals(2, summaries.getValue("Alle Nächte").nights)
        assertEquals(1, summaries.getValue("Nach Training (≥ 30 min)").hypoNights)
        assertEquals(0, summaries.getValue("Ohne Training").hypoNights)
        assertEquals(100, summaries.getValue("Ohne Training").avgTimeInRange)
    }
}
