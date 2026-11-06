package de.nbeck.aapshc.sync

import de.nbeck.aapshc.GlucoseUnit
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Auswertung als CSV (Semikolon, UTF-8 mit BOM), damit Excel/LibreOffice sie mit deutscher
 * Spracheinstellung direkt richtig öffnen – z. B. für die Diabetes-Sprechstunde.
 */
object CsvExport {
    private val HEADER = listOf(
        "Typ", "Beginn", "Ende", "Bezeichnung", "Dauer (min)",
        "Glukose Beginn", "Glukose Ende", "Min", "Max", "Mittel",
        "Im Ziel (%)", "Min. unter 70", "Min. über 180", "Hypo", "Anstieg morgens",
        "Sport vorher (min)", "Tiefschlaf (%)", "Tiefster Wert 12 h danach",
        "Hypo-Episoden", "davon Kompressions-Verdacht", "Insulin Abend (IE)", "KH Abend (g)",
        "Letzter Bolus vor Schlaf (min)", "Temp. Ziel beim Einschlafen",
    )

    fun build(report: AnalysisReport, zone: ZoneId = ZoneId.systemDefault(), unit: GlucoseUnit = GlucoseUnit.MGDL): String {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone)
        fun t(i: Instant) = fmt.format(i)
        fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toMinutes().toString()
        fun Int?.g() = this?.let { unit.value(it) }.orEmpty()
        fun Int?.gd() = this?.let { unit.delta(it).replace("−", "-") }.orEmpty()
        val rows = mutableListOf(HEADER.map { if (it in GLUCOSE_COLUMNS) "$it (${unit.label})" else it })
        report.items.sortedBy { it.start }.forEach { e ->
            rows += listOf(
                "Training", t(e.start), t(e.end), e.typeName, minutes(e.start, e.end),
                e.startMgdl.g(), e.endMgdl.g(), e.minDuring.g(), "", "",
                "", "", "", if (e.hypoDuring || e.hypoAfter) "ja" else "nein", "",
                "", "", e.minAfter.g(),
                "", "", "", "", "", "",
            )
        }
        report.nights.sortedBy { it.start }.forEach { n ->
            rows += listOf(
                "Nacht", t(n.start), t(n.end), "Schlaf", n.sleepMinutes.toString(),
                n.curve.firstOrNull()?.mgdl.g(), n.curve.lastOrNull()?.mgdl.g(), n.min.g(), n.max.g(), n.avg.g(),
                n.timeInRange.s(), n.minutesBelow.toString(), n.minutesAbove.toString(), if (n.hadRealHypo) "ja" else "nein",
                n.dawnRise.gd(), n.exerciseMinutesBefore.toString(), n.deepPercent.s(), "",
                n.hypoEvents.toString(), n.compressionEvents.toString(),
                n.evening?.insulin?.let { String.format(java.util.Locale.GERMANY, "%.1f", it) }.orEmpty(),
                n.evening?.carbs?.let { "%.0f".format(it) }.orEmpty(),
                n.evening?.lastManualBolusBeforeSleep?.toString().orEmpty(),
                n.evening?.tempTargetMgdl.g(),
            )
        }
        return BOM + rows.joinToString("\r\n") { row -> row.joinToString(";") { escape(it) } } + "\r\n"
    }

    private val GLUCOSE_COLUMNS = setOf(
        "Glukose Beginn", "Glukose Ende", "Min", "Max", "Mittel", "Anstieg morgens", "Tiefster Wert 12 h danach",
        "Temp. Ziel beim Einschlafen",
    )

    /** UTF-8-BOM, damit Excel die Umlaute richtig erkennt. */
    private val BOM = Char(0xFEFF).toString()

    private fun Int?.s() = this?.toString().orEmpty()

    private fun escape(v: String) =
        if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
