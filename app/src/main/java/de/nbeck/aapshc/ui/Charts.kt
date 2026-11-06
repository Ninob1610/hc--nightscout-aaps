package de.nbeck.aapshc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nbeck.aapshc.ns.GlucoseValue
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.NightAnalysis
import kotlin.math.abs

/*
 * Eigene Canvas-Diagramme statt einer Chart-Bibliothek. Regeln: dünne Marken, 2 dp Linien,
 * 2 dp Abstand zwischen gestapelten Flächen, zurückhaltendes Raster, Beschriftung in Textfarben.
 * Antippen zeigt die genauen Werte in der Zeile über dem Diagramm.
 */

private const val Y_MIN = 40f
private const val Y_MAX = 300f
private val AXIS_WIDTH = 32.dp
private const val SPARK_Y_MAX = 400
private const val SPARK_GAP_MS = 15 * 60_000L

/** Anteile einer Nacht unter/im/über dem Zielbereich, in Prozent. */
data class RangeShare(val label: String, val below: Int, val inRange: Int, val above: Int)

@Composable
fun RangeBarChart(nights: List<RangeShare>, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    var selected by remember(nights) { mutableStateOf(nights.lastIndex) }
    Column(modifier) {
        val n = nights.getOrNull(selected)
        Text(
            n?.let { "${it.label}: ${it.inRange} % im Ziel · ${it.below} % unter 70 · ${it.above} % über 180" } ?: "",
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = "Zeit im Zielbereich je Nacht" }
                .pointerInput(nights) {
                    detectTapGestures { pos ->
                        val axis = AXIS_WIDTH.toPx()
                        val slot = (size.width - axis) / nights.size.coerceAtLeast(1)
                        selected = ((pos.x - axis) / slot).toInt().coerceIn(0, nights.lastIndex.coerceAtLeast(0))
                    }
                },
        ) {
            val axis = AXIS_WIDTH.toPx()
            val inset = 10.dp.toPx() // Platz für die obere Achsenbeschriftung
            val h = size.height - 4.dp.toPx() - inset
            val gap = 2.dp.toPx()
            translate(top = inset) {
            for (p in listOf(0, 50, 100)) {
                val y = h - h * p / 100f
                drawLine(c.grid, Offset(axis, y), Offset(size.width, y), 1.dp.toPx())
                label(measurer, "$p %", Offset(0f, y - 7.sp.toPx()), textColor)
            }
            if (nights.isEmpty()) return@Canvas
            val slot = (size.width - axis) / nights.size
            val barW = (slot * 0.7f).coerceAtMost(28.dp.toPx())
            nights.forEachIndexed { i, night ->
                val x = axis + i * slot + (slot - barW) / 2
                var bottom = h
                // Von unten nach oben: unter Ziel, im Ziel, über Ziel.
                val segments = listOf(night.below to c.below, night.inRange to c.inRange, night.above to c.above).filter { it.first > 0 }
                segments.forEachIndexed { k, (pct, color) ->
                    val segH = (h * pct / 100f - if (k < segments.lastIndex) gap else 0f).coerceAtLeast(1f)
                    val top = bottom - segH
                    // Deckend abgedunkelt statt transparent, damit sich nichts überlagert.
                    val fill = if (i == selected) color else lerp(color, surface, 0.45f)
                    if (k == segments.lastIndex) {
                        drawPath(topRoundedRect(x, top, barW, segH, 4.dp.toPx()), fill)
                    } else {
                        drawRect(fill, Offset(x, top), Size(barW, segH))
                    }
                    bottom = top - gap
                }
            }
            }
        }
        Legend(listOf(c.below to "unter 70", c.inRange to "70–180", c.above to "über 180"))
    }
}

/** Nachtprofil: Median (Linie) und mittlere 50 % (Band) aller Nächte, ab dem Einschlafen. */
@Composable
fun NightProfileChart(points: List<NightAnalysis.ProfilePoint>, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    // Deckend über dem Zielband gemischt, damit Band und Legendenfeld gleich aussehen.
    val bandColor = c.series.copy(alpha = 0.4f).compositeOver(c.targetBand)
    val maxMinute = (points.maxOfOrNull { it.minute } ?: 0).coerceAtLeast(60)
    Column(modifier) {
        val p = selected?.let { points.getOrNull(it) }
        Text(
            p?.let { "Nach ${hm(it.minute)}: Median ${it.median} mg/dl (50 % zwischen ${it.p25} und ${it.p75}, ${it.nights} Nächte)" }
                ?: "Tippe auf das Diagramm für Werte",
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = "Glukose-Nachtprofil" }
                .pointerInput(points) {
                    detectTapGestures { pos ->
                        val axis = AXIS_WIDTH.toPx()
                        val minute = (pos.x - axis) / (size.width - axis) * maxMinute
                        selected = points.indices.minByOrNull { abs(points[it].minute - minute) }
                    }
                },
        ) {
            val axis = AXIS_WIDTH.toPx()
            val bottomPad = 16.dp.toPx()
            val h = size.height - bottomPad
            fun x(min: Int) = axis + (size.width - axis) * min / maxMinute
            fun y(mgdl: Int) = h - h * (mgdl.coerceIn(Y_MIN.toInt(), Y_MAX.toInt()) - Y_MIN) / (Y_MAX - Y_MIN)
            drawRect(c.targetBand, Offset(axis, y(180)), Size(size.width - axis, y(70) - y(180)))
            for (v in listOf(70, 180, 250)) {
                drawLine(c.grid, Offset(axis, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                label(measurer, "$v", Offset(0f, y(v) - 7.sp.toPx()), textColor)
            }
            for (hour in 0..maxMinute / 60 step 2) {
                label(measurer, "$hour h", Offset(x(hour * 60) - 6.dp.toPx(), h + 2.dp.toPx()), textColor)
            }
            if (points.size < 2) return@Canvas
            val band = Path().apply {
                moveTo(x(points.first().minute), y(points.first().p75))
                points.forEach { lineTo(x(it.minute), y(it.p75)) }
                points.asReversed().forEach { lineTo(x(it.minute), y(it.p25)) }
                close()
            }
            drawPath(band, bandColor)
            val line = Path().apply {
                moveTo(x(points.first().minute), y(points.first().median))
                points.drop(1).forEach { lineTo(x(it.minute), y(it.median)) }
            }
            drawPath(line, c.series, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            p?.let {
                drawLine(textColor.copy(alpha = 0.5f), Offset(x(it.minute), 0f), Offset(x(it.minute), h), 1.dp.toPx())
                dot(Offset(x(it.minute), y(it.median)), c.series)
            }
        }
        Legend(listOf(c.series to "Median", bandColor to "mittlere 50 %", c.targetBand to "Ziel 70–180"))
    }
}

/** Waagerechte Balken mit direkter Beschriftung, Wert 0–100. */
@Composable
fun PercentBars(rows: List<Triple<String, Int, String>>, color: Color, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height((rows.size * 44).dp).semantics { contentDescription = "Balkendiagramm" }) {
        val rowH = size.height / rows.size.coerceAtLeast(1)
        rows.forEachIndexed { i, (label, value, caption) ->
            val top = i * rowH
            label(measurer, label, Offset(0f, top), textColor, 13f)
            val barTop = top + 20.dp.toPx()
            val barH = 10.dp.toPx()
            val maxW = size.width * 0.72f
            drawRoundRect(c.grid, Offset(0f, barTop), Size(maxW, barH), CornerRadius(4.dp.toPx()))
            if (value > 0) drawRoundRect(color, Offset(0f, barTop), Size(maxW * value / 100f, barH), CornerRadius(4.dp.toPx()))
            label(measurer, caption, Offset(maxW + 8.dp.toPx(), barTop - 3.dp.toPx()), sub, 12f)
        }
    }
}

/** Streudiagramm, z. B. Tiefschlaf-Anteil gegen mittlere Nachtglukose. */
@Composable
fun ScatterChart(
    points: List<Pair<Int, Int>>,
    xLabel: String,
    describe: (Pair<Int, Int>) -> String,
    modifier: Modifier = Modifier,
) {
    val c = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val xMax = ((points.maxOfOrNull { it.first } ?: 30) + 5).coerceAtLeast(10)
    val yMin = ((points.minOfOrNull { it.second } ?: 80) - 20).coerceAtLeast(40)
    val yMax = ((points.maxOfOrNull { it.second } ?: 200) + 20).coerceAtMost(350)
    Column(modifier) {
        Text(selected?.let { describe(points[it]) } ?: "Tippe auf einen Punkt", style = MaterialTheme.typography.labelLarge)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Streudiagramm $xLabel" }
                .pointerInput(points) {
                    detectTapGestures { pos ->
                        val axis = AXIS_WIDTH.toPx()
                        val h = size.height - 16.dp.toPx()
                        selected = points.indices.minByOrNull { i ->
                            val px = axis + (size.width - axis) * points[i].first / xMax
                            val py = h - h * (points[i].second - yMin) / (yMax - yMin).toFloat()
                            (px - pos.x) * (px - pos.x) + (py - pos.y) * (py - pos.y)
                        }
                    }
                },
        ) {
            val axis = AXIS_WIDTH.toPx()
            val h = size.height - 16.dp.toPx()
            fun x(v: Int) = axis + (size.width - axis) * v / xMax
            fun y(v: Int) = h - h * (v - yMin) / (yMax - yMin).toFloat()
            for (v in listOf(yMin, (yMin + yMax) / 2, yMax)) {
                drawLine(c.grid, Offset(axis, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                label(measurer, "$v", Offset(0f, y(v) - 7.sp.toPx()), textColor)
            }
            for (v in 0..xMax step 10) label(measurer, "$v %", Offset(x(v) - 8.dp.toPx(), h + 2.dp.toPx()), textColor)
            points.forEachIndexed { i, (px, py) ->
                // 2 dp Ring in Flächenfarbe, damit sich überlappende Punkte trennen.
                drawCircle(surface, 6.dp.toPx(), Offset(x(px), y(py)))
                drawCircle(c.series.copy(alpha = if (selected == null || selected == i) 1f else 0.5f), 4.dp.toPx(), Offset(x(px), y(py)))
            }
        }
        Text(xLabel, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

/** Kleine Verlaufskurve eines Trainings oder einer Nacht; markierter Zeitraum = das Training. */
@Composable
fun GlucoseSparkline(
    curve: List<GlucoseValue>,
    from: Long,
    to: Long,
    highlightFrom: Long? = null,
    highlightTo: Long? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalChartColors.current
    val mark = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Canvas(modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Glukoseverlauf" }) {
        if (to <= from) return@Canvas
        fun x(t: Long) = size.width * (t - from) / (to - from).toFloat()
        fun y(v: Int) = size.height - size.height * (v.coerceIn(Y_MIN.toInt(), SPARK_Y_MAX) - Y_MIN) / (SPARK_Y_MAX - Y_MIN)
        drawRect(c.targetBand, Offset(0f, y(180)), Size(size.width, y(70) - y(180)))
        if (highlightFrom != null && highlightTo != null) {
            drawRect(mark, Offset(x(highlightFrom), 0f), Size(x(highlightTo) - x(highlightFrom), size.height))
        }
        if (curve.size < 2) return@Canvas
        // Bei CGM-Lücken die Linie unterbrechen statt einen Verlauf zu erfinden.
        val path = Path().apply {
            moveTo(x(curve.first().date), y(curve.first().mgdl))
            curve.zipWithNext().forEach { (a, b) ->
                if (b.date - a.date > SPARK_GAP_MS) moveTo(x(b.date), y(b.mgdl)) else lineTo(x(b.date), y(b.mgdl))
            }
        }
        drawPath(path, c.series, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        curve.filter { it.mgdl < Analysis.HYPO_MGDL }.forEach { drawCircle(c.below, 3.dp.toPx(), Offset(x(it.date), y(it.mgdl))) }
    }
}

private fun topRoundedRect(x: Float, top: Float, w: Float, h: Float, r: Float): Path {
    val radius = minOf(r, h, w / 2)
    return Path().apply {
        moveTo(x, top + h)
        lineTo(x, top + radius)
        quadraticTo(x, top, x + radius, top)
        lineTo(x + w - radius, top)
        quadraticTo(x + w, top, x + w, top + radius)
        lineTo(x + w, top + h)
        close()
    }
}

private fun DrawScope.dot(at: Offset, color: Color) {
    drawCircle(Color.White, 6.dp.toPx(), at)
    drawCircle(color, 4.dp.toPx(), at)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Float = 11f) {
    drawText(measurer, text, at, TextStyle(color = color, fontSize = sizeSp.sp))
}

private fun hm(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Säulen je Uhrzeit: echte Hypos unten, Kompressions-Verdacht darüber (neutral grau). */
@Composable
fun HourHistogram(hours: List<de.nbeck.aapshc.sync.NightFactors.HourCount>, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val neutral = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    var selected by remember(hours) { mutableStateOf(hours.indices.maxByOrNull { hours[it].real + hours[it].compression }) }
    val maxCount = (hours.maxOfOrNull { it.real + it.compression } ?: 1).coerceAtLeast(1)
    Column(modifier) {
        val h = selected?.let { hours.getOrNull(it) }
        Text(
            h?.let { "${it.hour}–${(it.hour + 1) % 24} Uhr: ${it.real} Hypos, ${it.compression} mit Kompressions-Verdacht" } ?: "",
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = "Beginn nächtlicher Hypos nach Uhrzeit" }
                .pointerInput(hours) {
                    detectTapGestures { pos ->
                        val axis = AXIS_WIDTH.toPx()
                        val slot = (size.width - axis) / hours.size.coerceAtLeast(1)
                        selected = ((pos.x - axis) / slot).toInt().coerceIn(0, (hours.size - 1).coerceAtLeast(0))
                    }
                },
        ) {
            val axis = AXIS_WIDTH.toPx()
            val inset = 10.dp.toPx()
            val bottomPad = 16.dp.toPx()
            val plotH = size.height - bottomPad - inset
            val gap = 2.dp.toPx()
            fun y(count: Int) = inset + plotH - plotH * count / maxCount
            for (v in listOf(0, maxCount)) {
                drawLine(c.grid, Offset(axis, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                label(measurer, "$v", Offset(0f, y(v) - 7.sp.toPx()), textColor)
            }
            if (hours.isEmpty()) return@Canvas
            val slot = (size.width - axis) / hours.size
            val barW = (slot * 0.7f).coerceAtMost(24.dp.toPx())
            hours.forEachIndexed { i, hc ->
                val x = axis + i * slot + (slot - barW) / 2
                val dim = i != selected
                fun tone(col: Color) = if (dim) lerp(col, surface, 0.45f) else col
                val segments = listOf(hc.real to c.below, hc.compression to neutral).filter { it.first > 0 }
                var bottom = y(0)
                segments.forEachIndexed { k, (count, col) ->
                    val segH = (plotH * count / maxCount - if (k < segments.lastIndex) gap else 0f).coerceAtLeast(1f)
                    val top = bottom - segH
                    if (k == segments.lastIndex) drawPath(topRoundedRect(x, top, barW, segH, 4.dp.toPx()), tone(col))
                    else drawRect(tone(col), Offset(x, top), Size(barW, segH))
                    bottom = top - gap
                }
                if (i % 2 == 0 || hours.size <= 8) {
                    label(measurer, "${hc.hour}", Offset(x + barW / 2 - 5.dp.toPx(), size.height - bottomPad + 2.dp.toPx()), textColor)
                }
            }
        }
        Legend(listOf(c.below to "Hypo", neutral to "Kompressions-Verdacht"))
    }
}

/** Zwei Balken je Merkmal: Anteil in Hypo-Nächten gegen Anteil in den übrigen Nächten. */
@Composable
fun PairedPercentBars(rows: List<Triple<String, Int, Int>>, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height((rows.size * 62).dp).semantics { contentDescription = "Vergleich Hypo-Nächte und übrige Nächte" }) {
            val rowH = size.height / rows.size.coerceAtLeast(1)
            val maxW = size.width * 0.75f
            val barH = 9.dp.toPx()
            rows.forEachIndexed { i, (label, hypo, other) ->
                val top = i * rowH
                label(measurer, label, Offset(0f, top), textColor, 13f)
                listOf(hypo to c.below, other to c.inRange).forEachIndexed { k, (pct, col) ->
                    val barTop = top + 20.dp.toPx() + k * (barH + 4.dp.toPx())
                    drawRoundRect(c.grid, Offset(0f, barTop), Size(maxW, barH), CornerRadius(4.dp.toPx()))
                    if (pct > 0) drawRoundRect(col, Offset(0f, barTop), Size(maxW * pct / 100f, barH), CornerRadius(4.dp.toPx()))
                    label(measurer, "$pct %", Offset(maxW + 8.dp.toPx(), barTop - 4.dp.toPx()), sub, 12f)
                }
            }
        }
        Legend(listOf(c.below to "Nächte mit Hypo", c.inRange to "übrige Nächte"))
    }
}
