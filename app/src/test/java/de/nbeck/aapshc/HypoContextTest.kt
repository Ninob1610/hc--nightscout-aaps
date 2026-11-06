package de.nbeck.aapshc

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.ns.NightscoutClient
import de.nbeck.aapshc.ns.TherapyItem
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.BedtimeRisk
import de.nbeck.aapshc.sync.Compression
import de.nbeck.aapshc.sync.EveningContext
import de.nbeck.aapshc.sync.NightAnalysis
import de.nbeck.aapshc.sync.NightFactors
import de.nbeck.aapshc.sync.NightGlucose
import de.nbeck.aapshc.sync.TonightContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HypoContextTest {
    private val t0 = Instant.parse("2026-11-04T23:00:00Z")
    private fun ms(min: Long) = t0.plusSeconds(min * 60).toEpochMilli()
    private fun series(vararg points: Pair<Long, Int>) = points.map { (m, v) -> GlucoseValue(ms(m), v) }

    /** 130 → in 10 min auf 55 → nach 20 min sofort wieder 120: typisches Kompressions-Tief. */
    private val compressionCurve = series(
        0L to 130, 5L to 128, 10L to 90, 15L to 58, 20L to 55, 25L to 56, 30L to 57, 35L to 110, 40L to 122, 45L to 125,
    )

    /** Langsamer Abfall über eine Stunde, lange Hypo, langsamer Anstieg: echte Hypo. */
    private val realCurve = (0L..180L step 5).map { m ->
        GlucoseValue(ms(m), when {
            m < 60 -> (120 - m).toInt()
            m < 120 -> 62
            else -> (62 + (m - 120) / 3).toInt()
        })
    }

    @Test
    fun compressionLowIsFlagged() {
        val ep = Analysis.episodes(compressionCurve).single()
        assertTrue(Compression.classify(ep, compressionCurve, carbTimes = emptyList()).suspectedCompression)
    }

    @Test
    fun carbsExplainTheRebound() {
        val ep = Analysis.episodes(compressionCurve).single()
        assertFalse(Compression.classify(ep, compressionCurve, carbTimes = listOf(ms(30))).suspectedCompression)
    }

    @Test
    fun slowLongHypoIsReal() {
        val ep = Analysis.episodes(realCurve).single()
        assertFalse(Compression.classify(ep, realCurve, emptyList()).suspectedCompression)
    }

    @Test
    fun eveningContextFromTherapy() {
        val therapy = listOf(
            TherapyItem.Bolus(ms(-300), 4.0, smb = false),
            TherapyItem.Bolus(ms(-90), 1.5, smb = false),
            TherapyItem.Bolus(ms(-30), 0.3, smb = true),
            TherapyItem.Carbs(ms(-300), 50.0),
            TherapyItem.TempTarget(ms(-60), 480, 140, "Activity"),
            TherapyItem.Bolus(ms(-500), 9.0, smb = false), // außerhalb der 6 h
        )
        val e = EveningContext.from(t0, therapy)
        assertEquals(5.8, e.insulin, 0.001)
        assertEquals(50.0, e.carbs, 0.001)
        assertEquals(90L, e.lastManualBolusBeforeSleep)
        assertTrue(e.lateBolus)
        assertEquals(140, e.tempTargetMgdl)
    }

    @Test
    fun parsesAapsTreatments() {
        fun p(json: String) = NightscoutClient.parseTherapy(JSONObject(json))
        assertEquals(listOf(TherapyItem.Bolus(1000, 0.4, smb = true)), p("""{"date":1000,"eventType":"Correction Bolus","insulin":0.4,"type":"SMB"}"""))
        assertTrue(p("""{"date":1000,"eventType":"Correction Bolus","insulin":2,"type":"PRIMING"}""").isEmpty())
        assertTrue(p("""{"date":1000,"eventType":"Meal Bolus","insulin":2,"isValid":false}""").isEmpty())
        assertEquals(
            listOf(TherapyItem.Bolus(1000, 3.0, smb = false), TherapyItem.Carbs(1000, 40.0)),
            p("""{"date":1000,"eventType":"Meal Bolus","insulin":3,"type":"NORMAL","carbs":40}"""),
        )
        assertEquals(
            listOf(TherapyItem.TempTarget(1000, 60, 140, "Activity")),
            p("""{"date":1000,"eventType":"Temporary Target","targetBottom":140,"targetTop":140,"durationInMilliseconds":3600000,"reason":"Activity"}"""),
        )
        // mmol/l-Ziel wird umgerechnet
        assertEquals(144, (p("""{"date":1000,"eventType":"Temporary Target","targetBottom":8,"targetTop":8,"duration":30}""").single() as TherapyItem.TempTarget).targetMgdl)
    }

    private fun night(day: Int, hypo: Boolean, lateBolus: Boolean, sport: Long = 0, compression: Boolean = false): NightGlucose {
        val start = Instant.parse("2026-10-%02dT22:30:00Z".format(day))
        // Echte Hypo: langsamer Abfall ab 60 min, 40 min unter 70 ab 120 min, langsamer Anstieg.
        val base = (0..480 step 5).map { m ->
            val v = if (!hypo) 120 else when {
                m < 60 -> 120
                m < 120 -> 120 - (m - 60)
                m < 160 -> 60
                m < 250 -> 60 + (m - 160) * 2 / 3
                else -> 120
            }
            GlucoseValue(start.plusSeconds(m * 60L).toEpochMilli(), v)
        }
        val therapy = if (lateBolus) listOf(TherapyItem.Bolus(start.minusSeconds(3600).toEpochMilli(), 2.0, smb = false)) else emptyList()
        val n = NightAnalysis.evaluate(start, start.plusSeconds(480 * 60), 480, base, sport, therapy = therapy)
        return if (compression) n.copy(episodes = n.episodes.map { it.copy(suspectedCompression = true) }) else n
    }

    @Test
    fun factorsCompareRealHypoNights() {
        val nights = listOf(
            night(1, hypo = true, lateBolus = true), night(2, hypo = true, lateBolus = true), night(3, hypo = true, lateBolus = false),
            night(4, hypo = false, lateBolus = false), night(5, hypo = false, lateBolus = false), night(6, hypo = false, lateBolus = true),
            night(7, hypo = true, lateBolus = false, compression = true), // zählt als „übrige Nacht“
        )
        val cmp = NightFactors.compare(nights)!!
        assertEquals(3, cmp.hypoNights)
        assertEquals(4, cmp.otherNights)
        val bolus = cmp.factors.single { it.label.startsWith("Bolus") }
        assertEquals(66, bolus.hypoPercent)
        assertEquals(25, bolus.otherPercent)
        // Nie Sport, nie Kohlenhydrate: diese Zeilen fehlen statt „0 % / 0 %“.
        assertTrue(cmp.factors.none { it.label.startsWith("Sport") || it.label.startsWith("Kohlenhydrate") })
    }

    @Test
    fun hypoHoursSeparateCompression() {
        val hours = NightFactors.hypoStartHours(
            listOf(night(1, hypo = true, lateBolus = false), night(2, hypo = true, lateBolus = false, compression = true)),
            ZoneOffset.UTC,
        )
        // Unter 70 ab 22:30 + 115 min = 00:25
        val h0 = hours.single { it.hour == 0 }
        assertEquals(1, h0.real)
        assertEquals(1, h0.compression)
    }

    @Test
    fun bedtimeHintOnlyWhenFactorStandsOut() {
        val history = listOf(
            night(1, true, true), night(2, true, true), night(3, true, true), night(4, false, true),
            night(5, false, false), night(6, false, false), night(7, true, false), night(8, false, false),
        )
        val msg = BedtimeRisk.message(TonightContext(exerciseMinutes = 0, lateBolus = true), history)!!
        assertTrue(msg, msg.contains("in 3 von 4 Nächten eine Hypo (sonst 1 von 4)"))
        assertNull(BedtimeRisk.message(TonightContext(exerciseMinutes = 0, lateBolus = false), history))
    }

    @Test
    fun bedtimeHintNeedsEnoughHistory() {
        val history = listOf(night(1, true, true), night(2, true, true), night(5, false, false))
        assertNull(BedtimeRisk.message(TonightContext(0, lateBolus = true), history))
    }

    @Test
    fun typicalBedtimeHandlesMidnight() {
        val starts = listOf("2026-10-01T23:30:00Z", "2026-10-02T23:50:00Z", "2026-10-04T00:10:00Z", "2026-10-04T22:40:00Z", "2026-10-06T00:30:00Z")
        val nights = starts.map { NightAnalysis.evaluate(Instant.parse(it), Instant.parse(it).plusSeconds(8 * 3600), 480, emptyList(), 0) }
        assertEquals(23 * 60 + 50, BedtimeRisk.typicalBedtime(nights, ZoneOffset.UTC))
    }
}
