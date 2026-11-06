package de.nbeck.aapshc.sync

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Reine Berechnungen auf Gesundheitsdaten, ohne Android-Abhängigkeiten. */
object Health {

    /** Untergrenzen der Pulszonen 1–5 in Prozent der maximalen Herzfrequenz. */
    private val ZONE_FLOORS = listOf(50, 60, 70, 80, 90)

    /** Längste Zeit, die ein Pulswert gilt, wenn der nächste fehlt (Lücken nicht hochrechnen). */
    private val MAX_SAMPLE_GAP = Duration.ofMinutes(2)

    /**
     * Minuten je Pulszone. Jeder Messwert gilt bis zum nächsten, höchstens [MAX_SAMPLE_GAP];
     * Werte unter 50 % der Maximalfrequenz zählen zu keiner Zone.
     */
    fun zoneMinutes(samples: List<Pair<Instant, Long>>, end: Instant, maxHeartRate: Int): List<Long> {
        val seconds = LongArray(ZONE_FLOORS.size)
        val sorted = samples.sortedBy { it.first }
        sorted.forEachIndexed { i, (time, bpm) ->
            val next = sorted.getOrNull(i + 1)?.first ?: end
            val held = minOf(Duration.between(time, next), MAX_SAMPLE_GAP).seconds.coerceAtLeast(0)
            val percent = bpm * 100.0 / maxHeartRate
            val zone = ZONE_FLOORS.indexOfLast { percent >= it }
            if (zone >= 0) seconds[zone] += held
        }
        return seconds.map { (it + 30) / 60 }
    }

    /** Fasst einzelne Periodentage zu zusammenhängenden Zeiträumen zusammen (ein Tag Lücke ist erlaubt). */
    fun mergePeriodDays(days: Collection<LocalDate>): List<ClosedRange<LocalDate>> {
        val sorted = days.toSortedSet().toList()
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf<ClosedRange<LocalDate>>()
        var first = sorted.first()
        var last = first
        for (day in sorted.drop(1)) {
            if (day.isAfter(last.plusDays(2))) {
                out += first..last
                first = day
            }
            last = day
        }
        out += first..last
        return out
    }

    const val FEVER_CELSIUS = 37.5
}
