package de.nbeck.aapshc.sync

import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.ns.TherapyItem
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Eine Hypo-Episode (≥ 15 min durchgehend unter 70 mg/dl). */
data class HypoEpisode(
    val start: Long,
    val end: Long,
    val minMgdl: Int,
    /** Steiler Abfall und schneller Wiederanstieg ohne Kohlenhydrate – typisch für Druck auf den Sensor. */
    val suspectedCompression: Boolean = false,
)

/** Erkennt nächtliche Kompressions-Tiefs. Markiert nur, blendet nichts aus. */
object Compression {
    // ≥ 2 mg/dl/min Abfall und ≥ 1,5 mg/dl/min Wiederanstieg; normales Absinken ist langsamer.
    private const val WINDOW_BEFORE_MS = 15 * 60_000L
    private const val WINDOW_AFTER_MS = 20 * 60_000L
    private const val MIN_DROP = 30
    private const val MIN_REBOUND = 30
    private const val MAX_EPISODE_MS = 90 * 60_000L
    private const val CARB_MARGIN_MS = 15 * 60_000L

    fun classify(episode: HypoEpisode, values: List<GlucoseValue>, carbTimes: List<Long>): HypoEpisode {
        val before = values.filter { it.date in (episode.start - WINDOW_BEFORE_MS) until episode.start }
        val after = values.filter { it.date in episode.end..(episode.end + WINDOW_AFTER_MS) }
        val dropped = (before.maxOfOrNull { it.mgdl } ?: return episode) - episode.minMgdl >= MIN_DROP
        val rebounded = (after.maxOfOrNull { it.mgdl } ?: return episode) - episode.minMgdl >= MIN_REBOUND
        val short = episode.end - episode.start <= MAX_EPISODE_MS
        // Gegessene Kohlenhydrate erklären den schnellen Anstieg – dann ist es eher eine echte Hypo.
        val ate = carbTimes.any { it in (episode.start - CARB_MARGIN_MS)..(episode.end + WINDOW_AFTER_MS) }
        return episode.copy(suspectedCompression = dropped && rebounded && short && !ate)
    }
}

/** Wie sich ein Training auf die Glukose ausgewirkt hat. */
data class ExerciseGlucose(
    val typeName: String,
    val start: Instant,
    val end: Instant,
    val startMgdl: Int?,
    val endMgdl: Int?,
    val minDuring: Int?,
    /** Tiefster Wert in den [Analysis.AFTER] nach dem Training. */
    val minAfter: Int?,
    val hypoDuring: Boolean,
    val hypoAfter: Boolean,
    /** Alle CGM-Werte von kurz vor dem Start bis 12 h danach – für die Verlaufskurve. */
    val curve: List<GlucoseValue> = emptyList(),
) {
    val changeDuring: Int? get() = if (startMgdl != null && endMgdl != null) endMgdl - startMgdl else null
}

data class TypeSummary(
    val typeName: String,
    val count: Int,
    val avgChangeDuring: Int?,
    val hypoAfterCount: Int,
    val hypoDuringCount: Int,
)

object Analysis {
    const val HYPO_MGDL = 70

    /** Eine Hypo-Episode nach internationalem Konsens: mindestens 15 min durchgehend unter 70 mg/dl. */
    private val HYPO_EVENT_MIN = Duration.ofMinutes(15)

    /** Größter Abstand zweier CGM-Werte, bei dem eine Episode noch als durchgehend gilt. */
    private val MAX_GAP = Duration.ofMinutes(10)

    /** Zählt Hypo-Episoden (siehe [episodes]). */
    fun hypoEvents(values: List<GlucoseValue>): Int = episodes(values).size

    /**
     * Hypo-Episoden. Einzelne Ausreißer zählen nicht; die Dauer reicht vom ersten Wert unter 70
     * bis 5 min nach dem letzten (ein CGM-Wert gilt 5 min).
     */
    fun episodes(values: List<GlucoseValue>): List<HypoEpisode> {
        val sorted = values.sortedBy { it.date }
        val out = mutableListOf<HypoEpisode>()
        var run = mutableListOf<GlucoseValue>()
        fun close() {
            if (run.isNotEmpty() && run.last().date + SAMPLE_MS - run.first().date >= HYPO_EVENT_MIN.toMillis()) {
                out += HypoEpisode(run.first().date, run.last().date + SAMPLE_MS, run.minOf { it.mgdl })
            }
            run = mutableListOf()
        }
        for (v in sorted) {
            if (v.mgdl < HYPO_MGDL) {
                if (run.isNotEmpty() && v.date - run.last().date > MAX_GAP.toMillis()) close()
                run += v
            } else close()
        }
        close()
        return out
    }

    private const val SAMPLE_MS = 5 * 60_000L
    val AFTER: Duration = Duration.ofHours(12)

    /** Werte rund um den Start/das Ende dürfen so weit entfernt liegen (CGM-Lücken). */
    private val NEAR = Duration.ofMinutes(15)

    fun evaluate(typeName: String, start: Instant, end: Instant, values: List<GlucoseValue>): ExerciseGlucose {
        fun nearest(at: Instant): Int? = values
            .filter { Math.abs(it.date - at.toEpochMilli()) <= NEAR.toMillis() }
            .minByOrNull { Math.abs(it.date - at.toEpochMilli()) }?.mgdl
        val s = start.toEpochMilli()
        val e = end.toEpochMilli()
        val duringValues = values.filter { it.date in s..e }
        val afterValues = values.filter { it.date in (e + 1)..(e + AFTER.toMillis()) }
        val during = duringValues.map { it.mgdl }
        val after = afterValues.map { it.mgdl }
        return ExerciseGlucose(
            typeName = typeName,
            start = start,
            end = end,
            startMgdl = nearest(start),
            endMgdl = nearest(end),
            minDuring = during.minOrNull(),
            minAfter = after.minOrNull(),
            hypoDuring = hypoEvents(duringValues) > 0,
            hypoAfter = hypoEvents(afterValues) > 0,
            curve = values.sortedBy { it.date },
        )
    }

    enum class TimeOfDay(val label: String, val fromHour: Int) {
        MORNING("Morgens (5–11 Uhr)", 5),
        MIDDAY("Mittags (11–17 Uhr)", 11),
        EVENING("Abends (17–22 Uhr)", 17),
        NIGHT("Spät (22–5 Uhr)", 22);

        companion object {
            fun of(hour: Int): TimeOfDay = when (hour) {
                in 5..10 -> MORNING
                in 11..16 -> MIDDAY
                in 17..21 -> EVENING
                else -> NIGHT
            }
        }
    }

    data class TimeOfDaySummary(val slot: TimeOfDay, val count: Int, val hypoAfterCount: Int, val avgMinAfter: Int?) {
        val hypoRate: Int get() = if (count == 0) 0 else hypoAfterCount * 100 / count
    }

    /** Hypo-Risiko nach dem Training je Tageszeit des Trainingsbeginns, in fester Reihenfolge. */
    fun byTimeOfDay(items: List<ExerciseGlucose>, zone: ZoneId = ZoneId.systemDefault()): List<TimeOfDaySummary> {
        val groups = items.filter { it.curve.isNotEmpty() }.groupBy { TimeOfDay.of(it.start.atZone(zone).hour) }
        return TimeOfDay.entries.mapNotNull { slot ->
            val list = groups[slot] ?: return@mapNotNull null
            TimeOfDaySummary(
                slot, list.size, list.count { it.hypoAfter },
                list.mapNotNull { it.minAfter }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            )
        }
    }

    fun summarize(items: List<ExerciseGlucose>): List<TypeSummary> =
        items.groupBy { it.typeName }.map { (type, list) ->
            val changes = list.mapNotNull { it.changeDuring }
            TypeSummary(
                typeName = type,
                count = list.size,
                avgChangeDuring = if (changes.isEmpty()) null else changes.average().toInt(),
                hypoAfterCount = list.count { it.hypoAfter },
                hypoDuringCount = list.count { it.hypoDuring },
            )
        }.sortedByDescending { it.count }
}

/** Glukose während einer Schlafsitzung. */
data class NightGlucose(
    val start: Instant,
    val end: Instant,
    val sleepMinutes: Long,
    val values: Int,
    val avg: Int?,
    val min: Int?,
    val max: Int?,
    /** Anteil der Werte in 70–180 mg/dl, in Prozent. */
    val timeInRange: Int?,
    val minutesBelow: Long,
    val minutesAbove: Long,
    /** Anstieg vom Tiefstwert der zweiten Nachthälfte bis zum Aufwachen (Dawn-Phänomen). */
    val dawnRise: Int?,
    /** Trainingsminuten in den 12 Stunden vor dem Einschlafen. */
    val exerciseMinutesBefore: Long,
    /** Anteil Tiefschlaf an der Schlafzeit in Prozent; null, wenn die Quelle keine Phasen liefert. */
    val deepPercent: Int? = null,
    val curve: List<GlucoseValue> = emptyList(),
    /** Hypo-Episoden (≥ 15 min unter 70) in der Nacht, mit Kompressions-Verdacht markiert. */
    val episodes: List<HypoEpisode> = emptyList(),
    /** Was am Abend davor war; null, wenn das Token keine Behandlungen lesen darf. */
    val evening: EveningContext? = null,
) {
    val hypoEvents: Int get() = episodes.size
    val compressionEvents: Int get() = episodes.count { it.suspectedCompression }
    val hadHypo: Boolean get() = episodes.isNotEmpty()

    /** Mindestens eine Hypo ohne Kompressions-Verdacht – Grundlage für Vergleiche und Hinweise. */
    val hadRealHypo: Boolean get() = episodes.any { !it.suspectedCompression }
}

/** Abend vor einer Nacht (6 h vor dem Einschlafen). */
data class EveningContext(
    /** Insulin inkl. SMB in Einheiten. */
    val insulin: Double,
    val carbs: Double,
    /** Minuten zwischen dem letzten selbst ausgelösten Bolus und dem Einschlafen. */
    val lastManualBolusBeforeSleep: Long?,
    /** Temporäres Ziel beim Einschlafen in mg/dl. */
    val tempTargetMgdl: Int?,
    val tempTargetReason: String?,
) {
    val lateBolus: Boolean get() = lastManualBolusBeforeSleep != null && lastManualBolusBeforeSleep <= LATE_BOLUS_MINUTES

    companion object {
        const val WINDOW_HOURS = 6L
        const val LATE_BOLUS_MINUTES = 180L

        fun from(sleepStart: Instant, therapy: List<TherapyItem>): EveningContext {
            val start = sleepStart.toEpochMilli()
            val from = start - Duration.ofHours(WINDOW_HOURS).toMillis()
            val evening = therapy.filter { it.date in from..start }
            val lastManual = evening.filterIsInstance<TherapyItem.Bolus>().filter { !it.smb }.maxOfOrNull { it.date }
            val target = therapy.filterIsInstance<TherapyItem.TempTarget>()
                .filter { it.date <= start && it.date + it.durationMinutes * 60_000 > start }
                .maxByOrNull { it.date }
            return EveningContext(
                insulin = evening.filterIsInstance<TherapyItem.Bolus>().sumOf { it.units },
                carbs = evening.filterIsInstance<TherapyItem.Carbs>().sumOf { it.grams },
                lastManualBolusBeforeSleep = lastManual?.let { (start - it) / 60_000 },
                tempTargetMgdl = target?.targetMgdl,
                tempTargetReason = target?.reason,
            )
        }
    }
}

data class NightSummary(
    val label: String,
    val nights: Int,
    val avgTimeInRange: Int?,
    val avgMin: Int?,
    val hypoNights: Int,
    val avgDawnRise: Int?,
)

object NightAnalysis {
    const val HIGH_MGDL = 180

    /** Ein CGM-Wert steht für höchstens diese Zeit (übliche 5-min-Taktung). */
    private const val SAMPLE_MINUTES = 5L

    fun evaluate(
        start: Instant,
        end: Instant,
        sleepMinutes: Long,
        values: List<GlucoseValue>,
        exerciseMinutesBefore: Long,
        deepMinutes: Long? = null,
        /** Behandlungen von 6 h vor dem Einschlafen bis zum Aufwachen; null = nicht lesbar. */
        therapy: List<TherapyItem>? = null,
    ): NightGlucose {
        val inNight = values.filter { it.date in start.toEpochMilli()..end.toEpochMilli() }.sortedBy { it.date }
        val mgdl = inNight.map { it.mgdl }
        val half = start.toEpochMilli() + (end.toEpochMilli() - start.toEpochMilli()) / 2
        val secondHalfMin = inNight.filter { it.date >= half }.minOfOrNull { it.mgdl }
        val last = inNight.lastOrNull()?.takeIf { end.toEpochMilli() - it.date <= Duration.ofMinutes(20).toMillis() }
        return NightGlucose(
            start = start,
            end = end,
            sleepMinutes = sleepMinutes,
            values = mgdl.size,
            avg = mgdl.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            min = mgdl.minOrNull(),
            max = mgdl.maxOrNull(),
            timeInRange = mgdl.takeIf { it.isNotEmpty() }
                ?.let { list -> list.count { it in Analysis.HYPO_MGDL..HIGH_MGDL } * 100 / list.size },
            minutesBelow = mgdl.count { it < Analysis.HYPO_MGDL } * SAMPLE_MINUTES,
            minutesAbove = mgdl.count { it > HIGH_MGDL } * SAMPLE_MINUTES,
            dawnRise = if (last != null && secondHalfMin != null) last.mgdl - secondHalfMin else null,
            exerciseMinutesBefore = exerciseMinutesBefore,
            deepPercent = deepMinutes?.takeIf { it > 0 && sleepMinutes > 0 }?.let { (it * 100 / sleepMinutes).toInt() },
            curve = inNight,
            episodes = run {
                val carbTimes = therapy.orEmpty().filterIsInstance<TherapyItem.Carbs>().map { it.date }
                Analysis.episodes(inNight).map { Compression.classify(it, values, carbTimes) }
            },
            evening = therapy?.let { EveningContext.from(start, it) },
        )
    }

    /** Ein Punkt des Nachtprofils: Perzentile aller Nächte zur gleichen Zeit nach dem Einschlafen. */
    data class ProfilePoint(val minute: Int, val p25: Int, val median: Int, val p75: Int, val nights: Int)

    /**
     * Nachtprofil im Stil eines AGP: alle Nächte übereinandergelegt, ausgerichtet am Einschlafen,
     * in [binMinutes]-Schritten. Ein Zeitpunkt zählt erst ab 3 Nächten, damit Ausreißer nicht täuschen.
     */
    fun profile(nights: List<NightGlucose>, binMinutes: Int = 30, hours: Int = 9): List<ProfilePoint> {
        val bins = (0 until hours * 60 / binMinutes).map { mutableListOf<Int>() }
        for (n in nights) {
            // Pro Nacht und Zeitfenster nur den Mittelwert, damit dichte CGM-Daten nicht stärker zählen.
            n.curve.groupBy { ((it.date - n.start.toEpochMilli()) / 60_000 / binMinutes).toInt() }
                .forEach { (bin, list) -> if (bin in bins.indices) bins[bin] += list.map { it.mgdl }.average().toInt() }
        }
        return bins.mapIndexedNotNull { i, list ->
            if (list.size < 3) return@mapIndexedNotNull null
            val sorted = list.sorted()
            ProfilePoint(i * binMinutes, percentile(sorted, 25), percentile(sorted, 50), percentile(sorted, 75), list.size)
        }
    }

    private fun percentile(sorted: List<Int>, p: Int): Int {
        val pos = (sorted.size - 1) * p / 100.0
        val lo = sorted[pos.toInt()]
        val hi = sorted[minOf(pos.toInt() + 1, sorted.size - 1)]
        return (lo + (hi - lo) * (pos - pos.toInt())).toInt()
    }

    /** Vergleicht Nächte mit viel und wenig Tiefschlaf (Teilung am Median). */
    fun byDeepSleep(nights: List<NightGlucose>): List<NightSummary> {
        val usable = nights.filter { it.values >= 12 && it.deepPercent != null }
        if (usable.size < 4) return emptyList()
        val median = usable.map { it.deepPercent!! }.sorted()[usable.size / 2]
        val (more, less) = usable.partition { it.deepPercent!! >= median }
        return listOf(summary("Tiefschlaf ≥ $median %", more), summary("Tiefschlaf < $median %", less)).filter { it.nights > 0 }
    }

    data class WeekSummary(
        val nights: Int,
        val avgTimeInRange: Int?,
        val hypoNights: Int,
        val exerciseMinutes: Long,
        val exerciseSessions: Int,
    )

    fun week(nights: List<NightGlucose>, exercises: List<ExerciseGlucose>): WeekSummary {
        val usable = nights.filter { it.values >= 12 }
        return WeekSummary(
            nights = usable.size,
            avgTimeInRange = usable.mapNotNull { it.timeInRange }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            hypoNights = usable.count { it.hadRealHypo },
            exerciseMinutes = exercises.sumOf { Duration.between(it.start, it.end).toMinutes() },
            exerciseSessions = exercises.size,
        )
    }

    /** Zusammenfassung einer Gruppe von Nächten; Hypo-Nächte ohne reine Kompressions-Tiefs. */
    internal fun summary(label: String, list: List<NightGlucose>) = NightSummary(
        label = label,
        nights = list.size,
        avgTimeInRange = list.mapNotNull { it.timeInRange }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        avgMin = list.mapNotNull { it.min }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        hypoNights = list.count { it.hadRealHypo },
        avgDawnRise = list.mapNotNull { it.dawnRise }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
    )

    /** Vergleicht Nächte mit und ohne Training am Vortag; Nächte ohne genug CGM-Werte zählen nicht. */
    fun summarize(nights: List<NightGlucose>, minExerciseMinutes: Long = 30): List<NightSummary> {
        val usable = nights.filter { it.values >= 12 }
        val (sport, rest) = usable.partition { it.exerciseMinutesBefore >= minExerciseMinutes }
        return listOf(
            summary("Alle Nächte", usable),
            summary("Nach Training (≥ $minExerciseMinutes min)", sport),
            summary("Ohne Training", rest),
        ).filter { it.nights > 0 }
    }
}
