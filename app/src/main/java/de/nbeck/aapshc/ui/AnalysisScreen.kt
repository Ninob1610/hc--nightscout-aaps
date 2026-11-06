package de.nbeck.aapshc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.nbeck.aapshc.sync.Analysis
import de.nbeck.aapshc.sync.AnalysisReport
import de.nbeck.aapshc.sync.ExerciseGlucose
import de.nbeck.aapshc.sync.NightGlucose
import java.text.DateFormat
import java.time.Duration
import java.time.LocalDate
import java.util.Date

@Composable
fun AnalysisScreen(s: UiState, vm: MainViewModel, modifier: Modifier) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Steuerzeile: Zeitraum, Auswerten, Export – oberhalb aller Diagramme.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(14L, 30L, 90L).forEach { d ->
                FilterChip(
                    selected = s.analysisDays == d,
                    onClick = { vm.runAnalysis(d) },
                    label = { Text("$d Tage") },
                    enabled = !s.analysisBusy,
                )
            }
            Spacer(Modifier.weight(1f))
            if (s.analysis != null) {
                IconButton(onClick = { exportLauncher.launch("auswertung-${LocalDate.now()}.csv") }) {
                    Icon(Icons.Filled.Download, contentDescription = "Als CSV exportieren")
                }
            }
        }

        s.analysisError?.let { Banner(it, isError = true, icon = Icons.Filled.Warning) }

        val report = s.analysis
        if (report == null) {
            SectionCard("Sport, Nächte und Glukose", Icons.Filled.Insights) {
                Text(
                    "Verknüpft Trainings und Schlaf aus Health Connect mit den CGM-Werten aus Nightscout. " +
                        "Die Werte werden nur gelesen und hier angezeigt – es wird nichts hochgeladen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.runAnalysis() }, enabled = !s.analysisBusy) { Text("Auswerten") }
                    if (s.analysisBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            }
            return@Column
        }
        if (s.analysisBusy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Wird ausgewertet …", style = MaterialTheme.typography.bodySmall)
            }
        }

        Overview(report)
        if (report.nights.any { it.values >= 12 }) {
            NightRangeCard(report)
            if (report.nightProfile.size >= 2) NightProfileCard(report)
            if (report.hypoHours.isNotEmpty()) HypoHoursCard(report)
            FactorsCard(report)
            NightComparisonCard(report)
            SleepQualityCard(report)
        }
        if (report.items.isNotEmpty()) {
            TimeOfDayCard(report)
            ExerciseTypesCard(report)
            ExerciseListCard(report.items)
        }
        if (report.nights.isNotEmpty()) NightListCard(report.nights)
        Banner(
            "Auswertung zur eigenen Orientierung, kein Medizinprodukt. Muster gern mit dem Diabetes-Team besprechen – " +
                "dafür gibt es oben den CSV-Export.",
            isError = false, icon = Icons.Filled.Info,
        )
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun Overview(r: AnalysisReport) {
    val usable = r.nights.filter { it.values >= 12 }
    val tir = usable.mapNotNull { it.timeInRange }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    val sportMinutes = r.items.sumOf { Duration.between(it.start, it.end).toMinutes() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(tir?.let { "$it %" } ?: "–", "Nächte im Ziel", Modifier.weight(1f), "${usable.size} Nächte")
        val compressionOnly = usable.count { it.hadHypo && !it.hadRealHypo }
        StatTile(
            "${usable.count { it.hadHypo }}", "Hypo-Nächte", Modifier.weight(1f),
            if (compressionOnly > 0) "davon $compressionOnly nur Kompr." else "≥ 15 min unter 70",
        )
        StatTile(hm(sportMinutes), "Sport", Modifier.weight(1f), "${r.items.size} Einheiten")
    }
}

@Composable
private fun NightRangeCard(r: AnalysisReport) {
    val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
    val shares = r.nights.filter { it.values >= 12 }.sortedBy { it.start }.takeLast(31).map { n ->
        val below = (n.minutesBelow / 5 * 100 / n.values).toInt()
        val above = (n.minutesAbove / 5 * 100 / n.values).toInt()
        RangeShare(fmt.format(Date(n.start.toEpochMilli())), below, (100 - below - above).coerceAtLeast(0), above)
    }
    SectionCard("Zeit im Zielbereich", Icons.Filled.Bedtime, subtitle = "je Nacht, 70–180 mg/dl") {
        RangeBarChart(shares)
    }
}

@Composable
private fun NightProfileCard(r: AnalysisReport) {
    SectionCard("Nachtprofil", Icons.Filled.Hotel, subtitle = "alle Nächte übereinander, ab dem Einschlafen") {
        NightProfileChart(r.nightProfile)
    }
}

@Composable
private fun HypoHoursCard(r: AnalysisReport) {
    val total = r.nights.sumOf { it.hypoEvents }
    val compression = r.nights.sumOf { it.compressionEvents }
    SectionCard(
        "Wann nachts Hypos beginnen", Icons.Filled.Schedule,
        subtitle = "$total Episoden, davon $compression mit Kompressions-Verdacht",
    ) {
        HourHistogram(r.hypoHours)
        Text(
            "Kompressions-Verdacht: steiler Abfall, schneller Wiederanstieg ohne Kohlenhydrate, höchstens 90 min – " +
                "typisch für Liegen auf dem Sensor. Solche Tiefs bleiben sichtbar, zählen aber nicht für Vergleiche und Hinweise.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FactorsCard(r: AnalysisReport) {
    val cmp = r.factors
    SectionCard("Was vor Hypo-Nächten anders war", Icons.Filled.Insights, subtitle = "Abend bis 6 h vor dem Einschlafen") {
        if (cmp == null) {
            Text(
                "Für den Vergleich braucht es mindestens ${de.nbeck.aapshc.sync.NightFactors.MIN_GROUP} Nächte mit und " +
                    "ohne echte Hypo im Zeitraum.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        Text(
            "${cmp.hypoNights} Nächte mit echter Hypo gegen ${cmp.otherNights} übrige. Zeigt Häufungen, keine Ursachen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val shares = cmp.factors.filter { it.hypoPercent != null }
        if (shares.isNotEmpty()) PairedPercentBars(shares.map { Triple(it.label, it.hypoPercent!!, it.otherPercent!!) })
        cmp.factors.filter { it.hypoPercent == null }.forEach { f ->
            Row(Modifier.fillMaxWidth()) {
                Text(f.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${f.hypoText} / ${f.otherText}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (cmp.factors.any { it.hypoPercent == null }) {
            Text("Mittelwerte: Hypo-Nächte / übrige Nächte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!r.therapyKnown) {
            Text(
                "Bolus, Kohlenhydrate und Ziele fehlen: Dem Token fehlt ${de.nbeck.aapshc.ns.Permissions.TREATMENTS_READ}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NightComparisonCard(r: AnalysisReport) {
    SectionCard("Nächte mit und ohne Sport", Icons.Filled.DirectionsRun, subtitle = "Training in den 12 h vor dem Einschlafen") {
        val groups = r.nightSummaries.filter { it.label != "Alle Nächte" }
        SummaryRows(groups)
        if (groups.size < 2) {
            Text(
                "Für den Vergleich fehlen noch Nächte " +
                    (if (groups.none { it.label.startsWith("Nach") }) "nach mindestens 30 min Training." else "ohne Training."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SleepQualityCard(r: AnalysisReport) {
    val points = r.nights.filter { it.values >= 12 && it.deepPercent != null && it.avg != null }
    if (points.size < 4) return
    val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
    SectionCard("Schlafqualität und Glukose", Icons.Filled.Bedtime, subtitle = "Tiefschlaf-Anteil gegen Ø Glukose der Nacht") {
        ScatterChart(
            points.map { it.deepPercent!! to it.avg!! },
            xLabel = "Tiefschlaf-Anteil · Ø Glukose in mg/dl",
            describe = { (x, y) ->
                val n = points.first { it.deepPercent == x && it.avg == y }
                "${fmt.format(Date(n.start.toEpochMilli()))}: $x % Tiefschlaf, Ø $y mg/dl"
            },
        )
        if (r.deepSleep.isNotEmpty()) {
            HorizontalDivider()
            SummaryRows(r.deepSleep)
        }
    }
}

@Composable
private fun SummaryRows(rows: List<de.nbeck.aapshc.sync.NightSummary>) {
    rows.forEach { n ->
        Column {
            Text("${n.label} · ${n.nights} Nächte", style = MaterialTheme.typography.titleSmall)
            Text(
                buildList {
                    n.avgTimeInRange?.let { add("im Ziel Ø $it %") }
                    n.avgMin?.let { add("Tiefstwert Ø $it") }
                    add("Hypo-Nächte ${n.hypoNights}")
                    n.avgDawnRise?.let { add("morgens Ø ${signed(it)}") }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeOfDayCard(r: AnalysisReport) {
    if (r.timeOfDay.isEmpty()) return
    SectionCard("Uhrzeit des Trainings", Icons.Filled.Schedule, subtitle = "Anteil mit Hypo in den 12 h danach") {
        PercentBars(
            r.timeOfDay.map { t ->
                Triple(t.slot.label, t.hypoRate, "${t.hypoRate} % (${t.hypoAfterCount}/${t.count})")
            },
            color = LocalChartColors.current.below,
        )
    }
}

@Composable
private fun ExerciseTypesCard(r: AnalysisReport) {
    SectionCard("Nach Trainingsart", Icons.Filled.DirectionsRun) {
        r.summaries.forEach { t ->
            Column {
                Text("${t.typeName} · ${t.count}×", style = MaterialTheme.typography.titleSmall)
                Text(
                    buildList {
                        t.avgChangeDuring?.let { add("Ø ${signed(it)} mg/dl während") }
                        add("Hypo danach ${t.hypoAfterCount}×")
                        if (t.hypoDuringCount > 0) add("währenddessen ${t.hypoDuringCount}×")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExerciseListCard(items: List<ExerciseGlucose>) {
    var all by remember { mutableStateOf(false) }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    SectionCard("Einzelne Trainings", Icons.Filled.DirectionsRun, subtitle = "Kurve bis 12 h danach, grau = Training") {
        (if (all) items else items.take(5)).forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${fmt.format(Date(e.start.toEpochMilli()))} · ${e.typeName}", style = MaterialTheme.typography.labelLarge)
                val during = if (e.startMgdl != null && e.endMgdl != null) "${e.startMgdl} → ${e.endMgdl} mg/dl" else "keine CGM-Werte"
                Text(
                    listOfNotNull(during, e.minAfter?.let { "Tiefstwert danach $it" }, if (e.hypoAfter || e.hypoDuring) "Hypo" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.hypoAfter || e.hypoDuring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.curve.size >= 2) {
                    GlucoseSparkline(
                        e.curve, e.curve.first().date, e.end.plus(Analysis.AFTER).toEpochMilli(),
                        e.start.toEpochMilli(), e.end.toEpochMilli(),
                    )
                }
            }
        }
        if (items.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) "Weniger anzeigen" else "Alle ${items.size} anzeigen") }
    }
}

@Composable
private fun NightListCard(nights: List<NightGlucose>) {
    var all by remember { mutableStateOf(false) }
    val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
    SectionCard("Einzelne Nächte", Icons.Filled.Bedtime) {
        (if (all) nights else nights.take(5)).forEach { n ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val sport = if (n.exerciseMinutesBefore > 0) " · vorher ${n.exerciseMinutesBefore} min Sport" else ""
                Text("${fmt.format(Date(n.start.toEpochMilli()))} · ${hm(n.sleepMinutes)} Schlaf$sport", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (n.values < 12) "zu wenige CGM-Werte"
                    else buildList {
                        n.timeInRange?.let { add("im Ziel $it %") }
                        add("${n.min}–${n.max} mg/dl")
                        if (n.hypoEvents > 0) {
                            add("${n.hypoEvents} Hypo" + if (n.compressionEvents > 0) " (${n.compressionEvents} Kompr.-Verdacht)" else "")
                        }
                        n.dawnRise?.let { add("morgens ${signed(it)}") }
                        n.deepPercent?.let { add("Tiefschlaf $it %") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.hadRealHypo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                n.evening?.let { e ->
                    Text(
                        "Abend: " + buildList {
                            add(String.format(java.util.Locale.GERMANY, "%.1f IE", e.insulin))
                            if (e.carbs > 0) add("${e.carbs.toInt()} g KH")
                            e.lastManualBolusBeforeSleep?.let { add("letzter Bolus ${hm(it)} vor dem Schlafen") }
                            e.tempTargetMgdl?.let { add("Ziel $it" + (e.tempTargetReason?.let { r -> " ($r)" } ?: "")) }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (n.curve.size >= 2) GlucoseSparkline(n.curve, n.start.toEpochMilli(), n.end.toEpochMilli())
            }
        }
        if (nights.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) "Weniger anzeigen" else "Alle ${nights.size} anzeigen") }
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

private fun hm(minutes: Long) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
