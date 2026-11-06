package de.nbeck.aapshc.sync

import java.time.Duration
import java.time.Instant

/**
 * Viele Nutzer haben mehrere Apps, die dasselbe Training nach Health Connect schreiben
 * (z. B. Garmin Connect und Samsung Health). Ohne Abgleich landet es doppelt in AAPS.
 */
object Dedup {
    data class Session<T>(val start: Instant, val end: Instant, val source: String, val item: T)

    /**
     * Behält pro Überschneidungsgruppe eine Sitzung: bevorzugt die längste, bei Gleichstand die erste.
     * Zwei Sitzungen gelten als dieselbe, wenn sie sich zu mindestens [minOverlap] der kürzeren überlappen
     * und aus verschiedenen Apps stammen.
     */
    fun <T> keepDistinct(sessions: List<Session<T>>, minOverlap: Double = 0.5): List<T> {
        val kept = mutableListOf<Session<T>>()
        for (s in sessions.sortedByDescending { Duration.between(it.start, it.end) }) {
            val duplicate = kept.any { k -> k.source != s.source && overlapRatio(k, s) >= minOverlap }
            if (!duplicate) kept += s
        }
        return kept.sortedBy { it.start }.map { it.item }
    }

    private fun overlapRatio(a: Session<*>, b: Session<*>): Double {
        val start = maxOf(a.start, b.start)
        val end = minOf(a.end, b.end)
        if (!end.isAfter(start)) return 0.0
        val shorter = minOf(Duration.between(a.start, a.end), Duration.between(b.start, b.end)).toMillis()
        return if (shorter <= 0) 0.0 else Duration.between(start, end).toMillis().toDouble() / shorter
    }
}
