package de.nbeck.aapshc.ui

import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.hc.HealthConnectSource
import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.ns.TherapyItem
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.AnalysisReport
import de.nbeck.aapshc.sync.NightAnalysis
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Erfundene, aber plausible Daten für Screenshots und Vorführungen (nur Debug-Builds,
 * Start mit `--ez demo true`). Keine echten Gesundheitsdaten.
 */
object DemoData {
    private val zone = ZoneId.systemDefault()
    private const val MINUTE = 60_000L

    fun state(): UiState {
        val now = System.currentTimeMillis()
        return UiState(
            nsUrl = "https://meinns.example.com",
            nsToken = "demo",
            minExerciseMinutes = 10,
            availability = HealthConnectSource.Availability.AVAILABLE,
            granted = HealthConnectSource.ALL_PERMISSIONS,
            backgroundSupported = true,
            lastSyncAt = now - 3 * MINUTE,
            lastSyncMessage = "OK: 3 hochgeladen, 0 gelöscht, 48 unverändert, 2 Duplikate zusammengeführt",
            knownSources = mapOf("com.garmin.android.apps.connectmobile" to "Garmin Connect", "com.sec.android.app.shealth" to "Samsung Health"),
            activityLog = listOf(
                Settings.LogEntry(now - 3 * MINUTE, "neu", "Laufen · 8,4 km · 612 kcal · Ø 151 / max 174 bpm · Zonen Z2 9 / Z3 24 / Z4 12 min (Garmin Connect)"),
                Settings.LogEntry(now - 3 * MINUTE, "neu", "Schlaf 7 h 12 min (Tief 1 h 24 min, REM 1 h 41 min, wach 18 min) (Samsung Health)"),
                Settings.LogEntry(now - 50 * MINUTE, "aktualisiert", "Schritte 05.11.: 9.842"),
            ),
            analysis = report(),
            analysisDays = 30,
        )
    }

    fun report(days: Int = 30): AnalysisReport {
        val rnd = Random(42)
        val today = LocalDate.now(zone)
        val items = mutableListOf<de.nbeck.aapshc.sync.ExerciseGlucose>()
        val nights = mutableListOf<de.nbeck.aapshc.sync.NightGlucose>()
        val types = listOf("Laufen", "Radfahren", "Krafttraining", "Gehen", "Schwimmen (Becken)")
        for (d in days downTo 1) {
            val day = today.minusDays(d.toLong())
            // Training an etwa jedem zweiten Tag, meist abends.
            var sportMinutesBefore = 0L
            val sleepStart = day.atTime(22, 30).atZone(zone).toInstant().plusSeconds(rnd.nextLong(-30, 50) * 60)
            if (rnd.nextFloat() < 0.5f) {
                val evening = rnd.nextFloat() < 0.6f
                val start = day.atTime(if (evening) 18 else 7, rnd.nextInt(0, 50)).atZone(zone).toInstant()
                val minutes = rnd.nextLong(30, 75)
                val end = start.plusSeconds(minutes * 60)
                val type = types[rnd.nextInt(types.size)]
                val lateLow = evening && rnd.nextFloat() < 0.55f
                val curve = curve(start.toEpochMilli() - 20 * MINUTE, end.toEpochMilli() + 12 * 60 * MINUTE, rnd) { t ->
                    val m = (t - start.toEpochMilli()) / MINUTE
                    when {
                        m < 0 -> 150.0
                        m <= minutes -> 150.0 - m * 0.9
                        lateLow && m in (minutes + 300)..(minutes + 345) -> 64.0
                        else -> 120.0 + 15 * sin(m / 90.0)
                    }
                }
                items += Analysis.evaluate(type, start, end, curve)
                if (evening) sportMinutesBefore = minutes
            }
            val lateBolus = rnd.nextFloat() < 0.3f
            val hypo = (sportMinutesBefore > 0 && rnd.nextFloat() < 0.6f) || (lateBolus && rnd.nextFloat() < 0.5f) || rnd.nextFloat() < 0.12f
            val compression = rnd.nextFloat() < 0.18f
            val hypoAt = rnd.nextLong(70, 160)
            val compressionAt = rnd.nextLong(200, 330)
            // Manche Nächte starten hoch (spätes Essen), einige haben einen deutlichen Morgenanstieg.
            val highStart = rnd.nextFloat() < 0.3f
            val highPeak = rnd.nextDouble(60.0, 110.0)
            val dawn = rnd.nextDouble(10.0, 70.0)
            val sleepEnd = sleepStart.plus(Duration.ofMinutes(rnd.nextLong(400, 500)))
            val s = sleepStart.toEpochMilli()
            val values = curve(s - 30 * MINUTE, sleepEnd.toEpochMilli() + 30 * MINUTE, rnd) { t ->
                val m = (t - s) / MINUTE
                val high = if (highStart && m in 0..180) highPeak * sin(PI * m / 180.0) else 0.0
                val base = 130.0 - 25 * sin(PI * m.coerceIn(0, 480) / 480.0) + dawn * (m - 360).coerceAtLeast(0) / 120.0 + high +
                    12 * sin(m / 37.0)
                when {
                    hypo && m in hypoAt until hypoAt + 40 -> 63.0
                    hypo && m in (hypoAt - 50) until hypoAt -> base - (base - 63) * (m - hypoAt + 50) / 50.0
                    hypo && m in (hypoAt + 40) until (hypoAt + 120) -> 63 + (base - 63) * (m - hypoAt - 40) / 80.0
                    compression && m in compressionAt until compressionAt + 25 -> 58.0
                    else -> base
                }
            }
            val therapy = buildList {
                add(TherapyItem.Bolus(s - 4 * 60 * MINUTE, rnd.nextDouble(3.0, 7.0), smb = false))
                add(TherapyItem.Carbs(s - 4 * 60 * MINUTE, rnd.nextInt(35, 70).toDouble()))
                repeat(rnd.nextInt(8, 20)) { add(TherapyItem.Bolus(s - rnd.nextLong(10, 350) * MINUTE, 0.3, smb = true)) }
                if (lateBolus) add(TherapyItem.Bolus(s - rnd.nextLong(40, 150) * MINUTE, rnd.nextDouble(1.0, 3.0), smb = false))
                if (sportMinutesBefore > 0 && rnd.nextFloat() < 0.5f) add(TherapyItem.TempTarget(s - 90 * MINUTE, 240, 140, "Activity"))
            }.sortedBy { it.date }
            val sleepMinutes = Duration.between(sleepStart, sleepEnd).toMinutes() - rnd.nextLong(5, 30)
            nights += NightAnalysis.evaluate(
                sleepStart, sleepEnd, sleepMinutes, values, sportMinutesBefore,
                deepMinutes = sleepMinutes * rnd.nextLong(10, 24) / 100, therapy = therapy,
            )
        }
        return AnalysisReport(
            items.sortedByDescending { it.start }, Analysis.summarize(items),
            nights.sortedByDescending { it.start }, NightAnalysis.summarize(nights), days.toLong(), therapyKnown = true,
        )
    }

    /** CGM-Werte alle 5 min mit etwas Messrauschen. */
    private fun curve(from: Long, to: Long, rnd: Random, mgdl: (Long) -> Double): List<GlucoseValue> =
        (from..to step 5 * MINUTE).map { t -> GlucoseValue(t, (mgdl(t) + rnd.nextDouble(-4.0, 4.0)).toInt().coerceIn(40, 400)) }
}
