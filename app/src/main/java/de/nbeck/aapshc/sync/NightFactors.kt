package de.nbeck.aapshc.sync

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Vergleicht Nächte mit echter Hypo (ohne Kompressions-Verdacht) mit den übrigen Nächten:
 * Was war am Abend davor anders? Zeigt Häufungen, keine Ursachen.
 */
object NightFactors {
    const val MIN_GROUP = 3
    const val SPORT_MINUTES = 30L

    /** Ein Vergleichsmerkmal. Ja/Nein-Merkmale haben Prozentwerte, Mengen nur Text. */
    data class Factor(
        val label: String,
        val hypoText: String,
        val otherText: String,
        val hypoPercent: Int? = null,
        val otherPercent: Int? = null,
    )

    data class Comparison(val hypoNights: Int, val otherNights: Int, val factors: List<Factor>)

    fun compare(nights: List<NightGlucose>): Comparison? {
        val usable = nights.filter { it.values >= 12 }
        val (hypo, other) = usable.partition { it.hadRealHypo }
        if (hypo.size < MIN_GROUP || other.size < MIN_GROUP) return null
        val factors = mutableListOf<Factor>()
        fun share(label: String, test: (NightGlucose) -> Boolean, among: (NightGlucose) -> Boolean = { true }) {
            val h = hypo.filter(among)
            val o = other.filter(among)
            if (h.isEmpty() || o.isEmpty()) return
            val hp = h.count(test) * 100 / h.size
            val op = o.count(test) * 100 / o.size
            // Kommt in keiner Gruppe vor (z. B. nie Sport): sagt nichts aus, weglassen.
            if (hp == 0 && op == 0) return
            factors += Factor(label, "$hp %", "$op %", hp, op)
        }
        share("Sport ≥ $SPORT_MINUTES min in den 12 h davor", { it.exerciseMinutesBefore >= SPORT_MINUTES })
        val withEvening = { n: NightGlucose -> n.evening != null }
        share("Bolus < 3 h vor dem Einschlafen", { it.evening!!.lateBolus }, withEvening)
        share("Temporäres Ziel beim Einschlafen", { it.evening!!.tempTargetMgdl != null }, withEvening)
        fun avg(label: String, unit: String, value: (EveningContext) -> Double) {
            val h = hypo.mapNotNull { it.evening }.map(value)
            val o = other.mapNotNull { it.evening }.map(value)
            if (h.isEmpty() || o.isEmpty() || (h.all { it == 0.0 } && o.all { it == 0.0 })) return
            factors += Factor(label, fmt(h.average(), unit), fmt(o.average(), unit))
        }
        avg("Insulin am Abend (inkl. SMB)", "IE") { it.insulin }
        avg("Kohlenhydrate am Abend", "g") { it.carbs }
        return Comparison(hypo.size, other.size, factors)
    }

    /** Beginn echter nächtlicher Hypos je Uhrzeit (Stunde 0–23), getrennt von Kompressions-Verdacht. */
    data class HourCount(val hour: Int, val real: Int, val compression: Int)

    fun hypoStartHours(nights: List<NightGlucose>, zone: ZoneId = ZoneId.systemDefault()): List<HourCount> {
        val episodes = nights.flatMap { it.episodes }
        if (episodes.isEmpty()) return emptyList()
        val byHour = episodes.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).hour }
        // Nachtstunden ab 20 Uhr in zeitlicher Reihenfolge, nur der Bereich mit Daten.
        val order = (20..23) + (0..11)
        val used = order.filter { byHour.containsKey(it) }
        val first = order.indexOf(used.first())
        val last = order.indexOf(used.last())
        return order.subList(first, last + 1).map { h ->
            val list = byHour[h].orEmpty()
            HourCount(h, list.count { !it.suspectedCompression }, list.count { it.suspectedCompression })
        }
    }

    private fun fmt(v: Double, unit: String) = String.format(Locale.GERMANY, if (unit == "IE") "%.1f %s" else "%.0f %s", v, unit)
}

/** Heutiger Abend, wie er sich vor dem Schlafengehen darstellt. */
data class TonightContext(val exerciseMinutes: Long, val lateBolus: Boolean)

/**
 * Hinweis vor dem Schlafengehen: Nur wenn ein heute vorliegendes Merkmal in der eigenen
 * Vorgeschichte deutlich häufiger zu einer echten Hypo-Nacht geführt hat.
 */
object BedtimeRisk {
    private const val MIN_RATE = 50
    private const val MIN_DIFFERENCE = 20

    fun message(tonight: TonightContext, history: List<NightGlucose>): String? {
        val usable = history.filter { it.values >= 12 }
        val reasons = buildList {
            if (tonight.exerciseMinutes >= NightFactors.SPORT_MINUTES) {
                rate(usable, { it.exerciseMinutesBefore >= NightFactors.SPORT_MINUTES })
                    ?.let { add("Sport heute (${tonight.exerciseMinutes} min): nach ähnlichen Tagen $it") }
            }
            if (tonight.lateBolus) {
                rate(usable.filter { it.evening != null }, { it.evening!!.lateBolus })
                    ?.let { add("Bolus kurz vor dem Schlafen: danach $it") }
            }
        }
        if (reasons.isEmpty()) return null
        return reasons.joinToString(". ") + ". Vielleicht ein höheres Ziel oder ein kleiner Snack – deine Entscheidung."
    }

    /** „in 4 von 6 Nächten eine Hypo (sonst 2 von 10)“, wenn das Merkmal deutlich auffällt. */
    private fun rate(nights: List<NightGlucose>, has: (NightGlucose) -> Boolean): String? {
        val (with, without) = nights.partition(has)
        if (with.size < NightFactors.MIN_GROUP || without.size < NightFactors.MIN_GROUP) return null
        val hw = with.count { it.hadRealHypo }
        val ho = without.count { it.hadRealHypo }
        val rw = hw * 100 / with.size
        val ro = ho * 100 / without.size
        if (rw < MIN_RATE || rw - ro < MIN_DIFFERENCE) return null
        return "in $hw von ${with.size} Nächten eine Hypo (sonst $ho von ${without.size})"
    }

    /** Übliche Einschlafzeit in Minuten nach Mitternacht (Median; Zeiten vor 12 Uhr zählen zum Vortag). */
    fun typicalBedtime(nights: List<NightGlucose>, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val minutes = nights.sortedBy { it.start }.takeLast(21).map {
            val t = it.start.atZone(zone)
            val m = t.hour * 60 + t.minute
            if (m < 12 * 60) m + 24 * 60 else m
        }.sorted()
        if (minutes.size < 5) return null
        return minutes[minutes.size / 2] % (24 * 60)
    }
}
