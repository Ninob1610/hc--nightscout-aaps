package de.nbeck.aapshc.sync

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.nbeck.aapshc.GlucoseUnit
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bericht für die Sprechstunde als PDF (A4). Gleiche Inhalte wie die Auswertung in der App,
 * in den geprüften hellen Diagrammfarben (Rot/Blau/Gelb), jede Grafik mit Legende.
 */
class PdfReport(private val report: AnalysisReport, private val unit: GlucoseUnit, private val zone: ZoneId = ZoneId.systemDefault()) {

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0

    private val below = Color.parseColor("#D03B3B")
    private val inRange = Color.parseColor("#2A78D6")
    private val above = Color.parseColor("#EDA100")
    private val neutral = Color.parseColor("#9A9A94")
    private val band = Color.parseColor("#F0EFEC")
    private val grid = Color.parseColor("#E2E1DC")
    private val ink = Color.parseColor("#1B1C18")
    private val ink2 = Color.parseColor("#52514E")

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private val h1 = paint(18f, bold = true)
    private val h2 = paint(12.5f, bold = true)
    private val body = paint(9.5f)
    private val small = paint(8f, ink2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND }
    private val dayFmt = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMANY).withZone(zone)
    private val shortFmt = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY).withZone(zone)

    fun write(out: OutputStream) {
        newPage()
        header()
        kpis()
        rangeChart()
        profileChart()
        hoursChart()
        comparisons()
        nightTable()
        definitions()
        finishPage()
        doc.writeTo(out)
        doc.close()
    }

    // --- Seitenverwaltung ---

    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        canvas = page!!.canvas
        y = M
    }

    private fun finishPage() {
        val p = page ?: return
        canvas.drawText("HC → AAPS · Seite $pageNo · Kein Medizinprodukt – zur Besprechung mit dem Diabetes-Team", M, H - 22f, small)
        doc.finishPage(p)
        page = null
    }

    private fun need(height: Float) {
        if (y + height > H - 40f) newPage()
    }

    // --- Inhalte ---

    private fun header() {
        canvas.drawText("Sport, Nächte und Glukose", M, y + 18f, h1)
        y += 30f
        val nights = report.nights.sortedBy { it.start }
        val from = nights.firstOrNull()?.start ?: Instant.now().minus(Duration.ofDays(report.days))
        canvas.drawText(
            "Zeitraum ${dayFmt.format(from)} – ${dayFmt.format(Instant.now())} (${report.days} Tage) · Glukose in ${unit.label} · " +
                "erstellt ${dayFmt.format(Instant.now())}",
            M, y, small,
        )
        y += 10f
        wrap(
            "Daten: Trainings und Schlaf aus Health Connect, CGM-Werte und Behandlungen aus Nightscout. Hypo = mindestens " +
                "15 min durchgehend unter ${unit.withUnit(70)}. Kompressions-Verdacht = steiler Abfall und schneller Wiederanstieg " +
                "ohne Kohlenhydrate. Häufungen, keine Ursachen.",
            small, CW,
        )
        y += 8f
    }

    private fun kpis() {
        val usable = report.nights.filter { it.values >= 12 }
        val tir = usable.mapNotNull { it.timeInRange }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val episodes = usable.sumOf { it.hypoEvents }
        val compression = usable.sumOf { it.compressionEvents }
        val sport = report.items.sumOf { Duration.between(it.start, it.end).toMinutes() }
        val tiles = listOf(
            (tir?.let { "$it %" } ?: "–") to "Nächte im Ziel (${usable.size} Nächte)",
            "${usable.count { it.hadRealHypo }}" to "Nächte mit echter Hypo",
            "$episodes" to "Hypo-Episoden, davon $compression Kompr.-Verdacht",
            hm(sport) to "Sport in ${report.items.size} Einheiten",
        )
        val w = (CW - 3 * 8f) / 4
        tiles.forEachIndexed { i, (value, label) ->
            val x = M + i * (w + 8f)
            fill.color = band
            canvas.drawRoundRect(RectF(x, y, x + w, y + 48f), 8f, 8f, fill)
            canvas.drawText(value, x + 8f, y + 22f, paint(16f, bold = true))
            wrapAt(label, small, x + 8f, y + 34f, w - 14f)
        }
        y += 62f
    }

    private fun rangeChart() {
        val nights = report.nights.filter { it.values >= 12 }.sortedBy { it.start }
        if (nights.isEmpty()) return
        need(150f)
        title("Zeit im Zielbereich je Nacht")
        val top = y
        val h = 100f
        val axis = 26f
        axisLabels(listOf(0 to "0 %", 50 to "50 %", 100 to "100 %"), top, h, axis) { v -> top + h - h * v / 100f }
        val slot = (CW - axis) / nights.size
        val barW = (slot * 0.7f).coerceAtMost(14f)
        nights.forEachIndexed { i, n ->
            val b = (n.minutesBelow / 5 * 100 / n.values).toFloat()
            val a = (n.minutesAbove / 5 * 100 / n.values).toFloat()
            val x = M + axis + i * slot + (slot - barW) / 2
            var bottom = top + h
            for ((pct, color) in listOf(b to below, (100 - b - a).coerceAtLeast(0f) to inRange, a to above)) {
                if (pct <= 0f) continue
                val segH = h * pct / 100f
                fill.color = color
                canvas.drawRect(x, bottom - segH + 0.8f, x + barW, bottom, fill)
                bottom -= segH
            }
            if (nights.size <= 16 || i % 3 == 0) canvas.drawText(shortFmt.format(n.start), x - 4f, top + h + 10f, small)
        }
        y = top + h + 26f
        legend(listOf(below to "unter ${unit.low}", inRange to unit.range, above to "über ${unit.high}"))
    }

    private fun profileChart() {
        val pts = report.nightProfile
        if (pts.size < 2) return
        need(170f)
        title("Nachtprofil – alle Nächte ab dem Einschlafen (Median und mittlere 50 %)")
        val top = y
        val h = 110f
        val axis = 26f
        val maxMin = pts.maxOf { it.minute }.coerceAtLeast(60)
        fun x(m: Int) = M + axis + (CW - axis) * m / maxMin
        fun yv(v: Int) = top + h - h * (v.coerceIn(40, 300) - 40) / 260f
        fill.color = band
        canvas.drawRect(M + axis, yv(180), M + CW, yv(70), fill)
        axisLabels(listOf(70, 180, 250).map { it to unit.value(it) }, top, h, axis) { v -> yv(v) }
        val area = Path().apply {
            moveTo(x(pts.first().minute), yv(pts.first().p75))
            pts.forEach { lineTo(x(it.minute), yv(it.p75)) }
            pts.asReversed().forEach { lineTo(x(it.minute), yv(it.p25)) }
            close()
        }
        fill.color = Color.argb(70, 42, 120, 214)
        canvas.drawPath(area, fill)
        stroke.color = inRange
        canvas.drawPath(Path().apply {
            moveTo(x(pts.first().minute), yv(pts.first().median))
            pts.drop(1).forEach { lineTo(x(it.minute), yv(it.median)) }
        }, stroke)
        for (hour in 0..maxMin / 60 step 2) canvas.drawText("$hour h", x(hour * 60) - 5f, top + h + 10f, small)
        y = top + h + 26f
        legend(listOf(inRange to "Median", Color.argb(70, 42, 120, 214) to "mittlere 50 %", band to "Ziel ${unit.range}"))
    }

    private fun hoursChart() {
        val hours = report.hypoHours
        if (hours.isEmpty()) return
        need(140f)
        title("Wann nachts Hypos beginnen")
        val top = y
        val h = 80f
        val axis = 26f
        val max = hours.maxOf { it.real + it.compression }.coerceAtLeast(1)
        axisLabels(listOf(0 to "0", max to "$max"), top, h, axis) { v -> top + h - h * v / max }
        val slot = (CW - axis) / hours.size
        val barW = (slot * 0.6f).coerceAtMost(22f)
        hours.forEachIndexed { i, hc ->
            val x = M + axis + i * slot + (slot - barW) / 2
            var bottom = top + h
            for ((count, color) in listOf(hc.real to below, hc.compression to neutral)) {
                if (count == 0) continue
                val segH = h * count / max
                fill.color = color
                canvas.drawRect(x, bottom - segH + 0.8f, x + barW, bottom, fill)
                bottom -= segH
            }
            canvas.drawText("${hc.hour}", x + barW / 2 - 4f, top + h + 10f, small)
        }
        y = top + h + 26f
        legend(listOf(below to "Hypo", neutral to "Kompressions-Verdacht"))
    }

    private fun comparisons() {
        report.factors?.let { cmp ->
            table(
                "Was vor Hypo-Nächten anders war (${cmp.hypoNights} Nächte mit echter Hypo / ${cmp.otherNights} übrige)",
                listOf("Merkmal", "Hypo-Nächte", "übrige Nächte"),
                cmp.factors.map { listOf(it.label, it.hypoText, it.otherText) },
                listOf(0.6f, 0.2f, 0.2f),
            )
        }
        val groups = buildList {
            addAll(report.nightSummaries.filter { it.label != "Alle Nächte" })
            report.targetEffect?.let { addAll(it.groups) }
            addAll(report.deepSleep)
            addAll(report.nightsAfterAlerts())
        }
        if (groups.isNotEmpty()) {
            table(
                "Nächte im Vergleich",
                listOf("Gruppe", "Nächte", "im Ziel", "Tiefstwert Ø", "Hypo-Nächte", "morgens Ø"),
                groups.map {
                    listOf(
                        it.label, "${it.nights}", it.avgTimeInRange?.let { v -> "$v %" } ?: "–",
                        it.avgMin?.let(unit::value) ?: "–", "${it.hypoNights}", it.avgDawnRise?.let(unit::delta) ?: "–",
                    )
                },
                listOf(0.36f, 0.1f, 0.12f, 0.15f, 0.13f, 0.14f),
            )
        }
        if (report.timeOfDay.isNotEmpty() || report.summaries.isNotEmpty()) {
            table(
                "Training",
                listOf("Art / Tageszeit", "Anzahl", "Ø Änderung während", "Hypo in 12 h danach"),
                report.summaries.map {
                    listOf(it.typeName, "${it.count}", it.avgChangeDuring?.let { v -> "${unit.delta(v)} ${unit.label}" } ?: "–", "${it.hypoAfterCount}")
                } + report.timeOfDay.map { listOf(it.slot.label, "${it.count}", "", "${it.hypoAfterCount} (${it.hypoRate} %)") },
                listOf(0.4f, 0.15f, 0.25f, 0.2f),
            )
        }
    }

    private fun nightTable() {
        val nights = report.nights.sortedByDescending { it.start }
        if (nights.isEmpty()) return
        table(
            "Einzelne Nächte",
            listOf("Nacht", "Schlaf", "im Ziel", "Min–Max", "Hypo", "Abend: Insulin / KH", "Ziel", "Sport"),
            nights.map { n ->
                listOf(
                    dayFmt.format(n.start), hm(n.sleepMinutes),
                    if (n.values < 12) "–" else "${n.timeInRange} %",
                    if (n.min != null && n.max != null) "${unit.value(n.min)}–${unit.value(n.max)}" else "–",
                    when {
                        n.hypoEvents == 0 -> ""
                        n.compressionEvents > 0 -> "${n.hypoEvents} (${n.compressionEvents} K.)"
                        else -> "${n.hypoEvents}"
                    },
                    n.evening?.let { String.format(Locale.GERMANY, "%.1f IE / %.0f g", it.insulin, it.carbs) } ?: "–",
                    n.evening?.tempTargetMgdl?.let(unit::value) ?: "",
                    if (n.exerciseMinutesBefore > 0) "${n.exerciseMinutesBefore} min" else "",
                )
            },
            listOf(0.12f, 0.12f, 0.09f, 0.14f, 0.1f, 0.2f, 0.09f, 0.14f),
        )
    }

    private fun definitions() {
        need(50f)
        y += 6f
        wrap(
            "Abend = 6 h vor dem Einschlafen. Später Bolus = selbst ausgelöst (kein SMB), < 3 h vor dem Einschlafen. " +
                "Geplantes Ziel = temporäres Ziel ohne Grund „Automation“ oder „Hypo“ (diese sind eine Reaktion auf fallende Werte). " +
                "„K.“ = Kompressions-Verdacht. Auffälliger Tag = Ruhepuls deutlich über dem eigenen 14-Tage-Median.",
            small, CW,
        )
    }

    // --- Bausteine ---

    private fun title(text: String) {
        canvas.drawText(text, M, y + 12f, h2)
        y += 22f
    }

    private fun axisLabels(values: List<Pair<Int, String>>, top: Float, h: Float, axis: Float, pos: (Int) -> Float) {
        fill.color = grid
        values.forEach { (v, label) ->
            val yy = pos(v)
            canvas.drawRect(M + axis, yy - 0.4f, M + CW, yy + 0.4f, fill)
            canvas.drawText(label, M, yy + 3f, small)
        }
    }

    private fun legend(items: List<Pair<Int, String>>) {
        var x = M
        items.forEach { (color, label) ->
            fill.color = color
            canvas.drawRoundRect(RectF(x, y - 7f, x + 8f, y + 1f), 2f, 2f, fill)
            canvas.drawText(label, x + 12f, y, small)
            x += 12f + small.measureText(label) + 16f
        }
        y += 16f
    }

    private fun table(title: String, head: List<String>, rows: List<List<String>>, widths: List<Float>) {
        need(40f + minOf(rows.size, 3) * 13f)
        title(title)
        fun row(cells: List<String>, p: Paint, shade: Boolean) {
            need(13f)
            if (shade) {
                fill.color = band
                canvas.drawRect(M, y - 9f, M + CW, y + 3.5f, fill)
            }
            var x = M + 3f
            cells.forEachIndexed { i, c ->
                val w = CW * widths[i]
                canvas.drawText(ellipsize(c, p, w - 6f), x, y, p)
                x += w
            }
            y += 13f
        }
        row(head, paint(8.5f, ink2, bold = true), false)
        rows.forEachIndexed { i, r -> row(r, paint(8.5f), i % 2 == 0) }
        y += 8f
    }

    private fun wrap(text: String, p: Paint, width: Float) {
        y = wrapAt(text, p, M, y + p.textSize, width) + 2f
    }

    /** Bricht Text in Zeilen um und liefert die y-Position der letzten Zeile. */
    private fun wrapAt(text: String, p: Paint, x: Float, startY: Float, width: Float): Float {
        var line = ""
        var yy = startY
        for (word in text.split(" ")) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) > width && line.isNotEmpty()) {
                canvas.drawText(line, x, yy, p)
                yy += p.textSize * 1.3f
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) canvas.drawText(line, x, yy, p)
        return yy
    }

    private fun ellipsize(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    private fun hm(minutes: Long) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

    companion object {
        private const val W = 595 // A4 in Punkt
        private const val H = 842
        private const val M = 40f
        private const val CW = W - 2 * M
    }
}
