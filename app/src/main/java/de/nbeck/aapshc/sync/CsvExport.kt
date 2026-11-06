package de.nbeck.aapshc.sync

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

    fun build(report: AnalysisReport, zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone)
        fun t(i: Instant) = fmt.format(i)
        fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toMinutes().toString()
        val rows = mutableListOf(HEADER)
        report.items.sortedBy { it.start }.forEach { e ->
            rows += listOf(
                "Training", t(e.start), t(e.end), e.typeName, minutes(e.start, e.end),
                e.startMgdl.s(), e.endMgdl.s(), e.minDuring.s(), "", "",
                "", "", "", if (e.hypoDuring || e.hypoAfter) "ja" else "nein", "",
                "", "", e.minAfter.s(),
                "", "", "", "", "", "",
            )
        }
        report.nights.sortedBy { it.start }.forEach { n ->
            rows += listOf(
                "Nacht", t(n.start), t(n.end), "Schlaf", n.sleepMinutes.toString(),
                n.curve.firstOrNull()?.mgdl.s(), n.curve.lastOrNull()?.mgdl.s(), n.min.s(), n.max.s(), n.avg.s(),
                n.timeInRange.s(), n.minutesBelow.toString(), n.minutesAbove.toString(), if (n.hadRealHypo) "ja" else "nein",
                n.dawnRise.s(), n.exerciseMinutesBefore.toString(), n.deepPercent.s(), "",
                n.hypoEvents.toString(), n.compressionEvents.toString(),
                n.evening?.insulin?.let { String.format(java.util.Locale.GERMANY, "%.1f", it) }.orEmpty(),
                n.evening?.carbs?.let { "%.0f".format(it) }.orEmpty(),
                n.evening?.lastManualBolusBeforeSleep?.toString().orEmpty(),
                n.evening?.tempTargetMgdl.s(),
            )
        }
        return BOM + rows.joinToString("\r\n") { row -> row.joinToString(";") { escape(it) } } + "\r\n"
    }

    /** UTF-8-BOM, damit Excel die Umlaute richtig erkennt. */
    private val BOM = Char(0xFEFF).toString()

    private fun Int?.s() = this?.toString().orEmpty()

    private fun escape(v: String) =
        if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
