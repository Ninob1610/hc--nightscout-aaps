package de.nbeck.aapshc.sync

import de.nbeck.aapshc.ns.Treatment
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Plattformunabhängige Abbilder der Health-Connect-Daten (testbar ohne Android). */
data class ExerciseInfo(
    val recordId: String,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val typeName: String,
    val title: String?,
    val source: String,
    val distanceKm: Double?,
    val kcal: Double?,
    val avgHeartRate: Long?,
    val maxHeartRate: Long?,
    val steps: Long?,
    /** Minuten in den Pulszonen 1–5; null, wenn keine Pulsdaten oder Zonen abgeschaltet. */
    val zoneMinutes: List<Long>? = null,
)

data class SleepInfo(
    val recordId: String,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val source: String,
    val sourcePackage: String,
    val deep: Duration,
    val rem: Duration,
    val awake: Duration,
)

data class TemperatureInfo(
    val recordId: String,
    val time: Instant,
    val offset: ZoneOffset,
    val celsius: Double,
    val location: String?,
    val source: String,
)

/** Eine Periode, zusammengefasst aus Tagesmeldungen und/oder Zeiträumen in Health Connect. */
data class PeriodInfo(
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val start: Instant,
    val offset: ZoneOffset,
)

data class DailySteps(
    val day: LocalDate,
    /** Zeitpunkt der Notiz: Tagesende (23:59). */
    val at: Instant,
    val offset: ZoneOffset,
    val steps: Long,
)

object Mapper {
    private val de = Locale.GERMANY

    fun identifierFor(key: String): String = UUID.nameUUIDFromBytes("hc:$key".toByteArray()).toString()

    fun exercise(e: ExerciseInfo): Treatment {
        val minutes = Duration.between(e.start, e.end).toMinutes()
        val parts = buildList {
            add(e.title?.takeIf { it.isNotBlank() && it != e.typeName }?.let { "${e.typeName} „$it“" } ?: e.typeName)
            e.distanceKm?.takeIf { it >= 0.05 }?.let { add(String.format(de, "%.1f km", it)) }
            e.kcal?.takeIf { it >= 1 }?.let { add(String.format(de, "%.0f kcal", it)) }
            e.avgHeartRate?.let { avg ->
                add(e.maxHeartRate?.let { "Ø $avg / max $it bpm" } ?: "Ø $avg bpm")
            }
            e.steps?.takeIf { it > 0 }?.let { add(String.format(de, "%,d Schritte", it)) }
            e.zoneMinutes?.let { zones ->
                val text = zones.mapIndexedNotNull { i, m -> if (m >= 1) "Z${i + 1} $m" else null }
                if (text.isNotEmpty()) add("Zonen ${text.joinToString(" / ")} min")
            }
        }
        return Treatment(
            identifier = identifierFor("exercise:${e.recordId}"),
            eventType = Treatment.EXERCISE,
            date = e.start.toEpochMilli(),
            utcOffsetMinutes = e.offset.totalSeconds / 60,
            durationMinutes = minutes,
            notes = parts.joinToString(" · ") + " (${e.source})",
        )
    }

    fun sleep(s: SleepInfo): Treatment {
        val total = Duration.between(s.start, s.end)
        val asleep = total.minus(s.awake)
        val details = buildList {
            if (!s.deep.isZero) add("Tief ${hm(s.deep)}")
            if (!s.rem.isZero) add("REM ${hm(s.rem)}")
            if (!s.awake.isZero) add("wach ${hm(s.awake)}")
        }
        val text = "Schlaf ${hm(asleep)}" + if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
        return Treatment(
            identifier = identifierFor("sleep:${s.recordId}"),
            eventType = Treatment.NOTE,
            date = s.start.toEpochMilli(),
            utcOffsetMinutes = s.offset.totalSeconds / 60,
            durationMinutes = total.toMinutes(),
            notes = "$text (${s.source})",
        )
    }

    fun steps(d: DailySteps): Treatment = Treatment(
        identifier = identifierFor("steps:${d.day}"),
        eventType = Treatment.NOTE,
        date = d.at.toEpochMilli(),
        utcOffsetMinutes = d.offset.totalSeconds / 60,
        durationMinutes = 0,
        notes = String.format(de, "Schritte %s: %,d", d.day.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.")), d.steps),
    )

    fun temperature(t: TemperatureInfo): Treatment = Treatment(
        identifier = identifierFor("temperature:${t.recordId}"),
        eventType = Treatment.NOTE,
        date = t.time.toEpochMilli(),
        utcOffsetMinutes = t.offset.totalSeconds / 60,
        durationMinutes = 0,
        notes = String.format(de, "Erhöhte Temperatur %.1f °C", t.celsius) +
            (t.location?.let { " ($it)" } ?: "") + " – Insulinbedarf kann steigen (${t.source})",
    )

    fun period(p: PeriodInfo): Treatment {
        val days = java.time.temporal.ChronoUnit.DAYS.between(p.firstDay, p.lastDay) + 1
        return Treatment(
            identifier = identifierFor("period:${p.firstDay}"),
            eventType = Treatment.NOTE,
            date = p.start.toEpochMilli(),
            utcOffsetMinutes = p.offset.totalSeconds / 60,
            durationMinutes = days * 24 * 60,
            notes = "Periode, Tag 1–$days",
        )
    }

    private fun hm(d: Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return if (h > 0) "$h h ${m} min" else "$m min"
    }
}
