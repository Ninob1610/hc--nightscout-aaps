package de.nbeck.aapshc.sync

import de.nbeck.aapshc.ns.Treatment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Ruhepuls und Herzfrequenzvariabilität eines Kalendertags (Tagesmittel). */
data class DailyVitals(val day: LocalDate, val restingHr: Double?, val hrvMs: Double?)

/** Ein Tag, an dem Ruhepuls/HRV deutlich vom eigenen Normalwert abweichen. */
data class VitalsAlert(
    val day: LocalDate,
    val restingHr: Int,
    val baselineHr: Int,
    val hrvMs: Int?,
    val baselineHrv: Int?,
    val fever: Boolean,
)

/**
 * Früher Infekt-Hinweis: Ruhepuls steigt und HRV sinkt oft schon Tage vor Symptomen –
 * und mit ihnen häufig der Insulinbedarf. Vergleich jeweils mit dem Median der 14 Tage davor.
 */
object Vitals {
    private const val BASELINE_DAYS = 14L
    private const val MIN_BASELINE = 5
    private const val HR_RISE = 5.0
    private const val HR_RISE_WITH_OTHER_SIGN = 3.0
    private const val HRV_DROP = 0.75

    fun alerts(days: List<DailyVitals>, feverDays: Set<LocalDate> = emptySet()): List<VitalsAlert> {
        val byDay = days.associateBy { it.day }
        return days.sortedBy { it.day }.mapNotNull { today ->
            val hr = today.restingHr ?: return@mapNotNull null
            val window = (1..BASELINE_DAYS).mapNotNull { byDay[today.day.minusDays(it)] }
            val hrBase = median(window.mapNotNull { it.restingHr }) ?: return@mapNotNull null
            val hrvBase = median(window.mapNotNull { it.hrvMs })
            val rise = hr - hrBase
            val hrvLow = today.hrvMs != null && hrvBase != null && today.hrvMs <= hrvBase * HRV_DROP
            val fever = today.day in feverDays
            val flagged = rise >= HR_RISE || ((hrvLow || fever) && rise >= HR_RISE_WITH_OTHER_SIGN)
            if (!flagged) return@mapNotNull null
            VitalsAlert(
                today.day, hr.toInt(), hrBase.toInt(),
                today.hrvMs?.toInt(), hrvBase?.toInt(), fever,
            )
        }
    }

    fun treatment(a: VitalsAlert, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): Treatment {
        val at = minOf(a.day.atTime(8, 0).atZone(zone).toInstant(), now)
        val parts = buildList {
            add("Ruhepuls erhöht: ${a.restingHr} bpm (sonst ${a.baselineHr})")
            if (a.hrvMs != null && a.baselineHrv != null) add("HRV ${a.hrvMs} ms (sonst ${a.baselineHrv})")
            if (a.fever) add("Fieber")
        }
        return Treatment(
            identifier = Mapper.identifierFor("vitals:${a.day}"),
            eventType = Treatment.NOTE,
            date = at.toEpochMilli(),
            utcOffsetMinutes = zone.rules.getOffset(at).totalSeconds / 60,
            durationMinutes = 0,
            notes = parts.joinToString(" · ") + " – möglicher Infekt, Insulinbedarf kann steigen",
        )
    }

    private fun median(values: List<Double>): Double? {
        if (values.size < MIN_BASELINE) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun format(v: Double) = String.format(Locale.GERMANY, "%.0f", v)
}
