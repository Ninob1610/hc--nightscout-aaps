package de.nbeck.aapshc.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import de.nbeck.aapshc.hc.HealthConnectSource
import de.nbeck.aapshc.hc.HealthConnectSource.Availability
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SyncScreen(s: UiState, vm: MainViewModel, modifier: Modifier, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHero(s, vm)
        HealthConnectCard(s, context, requestPermissions)
        NightscoutCard(s, vm)
        DataCard(s, vm)
        AutomationCard(s, vm)
        NotificationCard(s, vm)
        if (s.knownSources.isNotEmpty()) SourcesCard(s, vm)
        if (s.activityLog.isNotEmpty()) LogCard(s)
        Banner(
            "In AAPS 4: Konfiguration → Synchronisierung → NSClient (v3) → Einstellungen → Synchronisierung → " +
                "„Therapie-Ereignisse übernehmen“ aktivieren. Blutzucker aus Health Connect wird nie übertragen.",
            isError = false, icon = Icons.Filled.Info,
        )
        Spacer(Modifier.size(8.dp))
    }
}

/** Großer Statusbereich oben: letzter Lauf, Ergebnis, Sync-Knopf. */
@Composable
private fun StatusHero(s: UiState, vm: MainViewModel) {
    val failed = s.lastSyncMessage.startsWith("Fehler")
    val never = s.lastSyncAt == 0L
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        never -> Icons.Filled.Sync
                        failed -> Icons.Filled.Error
                        else -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null, modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            never -> "Noch nicht synchronisiert"
                            failed -> "Letzter Sync fehlgeschlagen"
                            else -> "Synchronisiert " + relative(s.lastSyncAt)
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    )
                    if (!never) {
                        Text(
                            s.lastSyncMessage.removePrefix("OK: ").removePrefix("Fehler: "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::syncNow, enabled = !s.busy && s.availability == Availability.AVAILABLE) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Jetzt synchronisieren")
                }
                if (s.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
            s.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Fehler")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun HealthConnectCard(s: UiState, context: Context, requestPermissions: () -> Unit) {
    SectionCard("Health Connect", Icons.Filled.Favorite, subtitle = "Freigaben für das Lesen") {
        when (s.availability) {
            Availability.NOT_INSTALLED, Availability.UPDATE_REQUIRED -> {
                Text(if (s.availability == Availability.NOT_INSTALLED) "Health Connect ist nicht installiert." else "Health Connect muss aktualisiert werden.")
                Button(onClick = {
                    val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                }) { Text("Im Play Store öffnen") }
            }
            Availability.AVAILABLE -> {
                ChipFlow {
                    PermissionChip("Training", HealthConnectSource.read(ExerciseSessionRecord::class) in s.granted)
                    PermissionChip("Schlaf", HealthConnectSource.read(SleepSessionRecord::class) in s.granted)
                    PermissionChip("Schritte", HealthConnectSource.read(StepsRecord::class) in s.granted)
                    PermissionChip("Puls", HealthConnectSource.read(HeartRateRecord::class) in s.granted)
                    if (s.syncFever) PermissionChip("Temperatur", HealthConnectSource.read(BodyTemperatureRecord::class) in s.granted)
                    if (s.syncCycle) PermissionChip("Zyklus", HealthConnectSource.read(MenstruationFlowRecord::class) in s.granted)
                    if (s.backgroundSupported) PermissionChip("Hintergrund", HealthConnectSource.BACKGROUND in s.granted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = requestPermissions) { Text("Freigaben") }
                    OutlinedButton(onClick = { openHealthConnectSettings(context) }) { Text("Health Connect öffnen") }
                }
            }
        }
    }
}

@Composable
private fun NightscoutCard(s: UiState, vm: MainViewModel) {
    // Eingerichtet und fehlerfrei: kompakt anzeigen, Felder erst auf Wunsch.
    var editing by rememberSaveable { mutableStateOf(s.nsUrl.isBlank() || s.nsToken.isBlank()) }
    SectionCard(
        "Nightscout", Icons.Filled.Dns,
        subtitle = s.nsUrl.removePrefix("https://").ifBlank { "nicht eingerichtet" },
        trailing = { if (!editing) TextButton(onClick = { editing = true }) { Text("Ändern") } },
    ) {
        if (editing) {
            OutlinedTextField(
                value = s.nsUrl, onValueChange = vm::onUrl, singleLine = true,
                label = { Text("URL, z. B. https://meinns.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.nsToken, onValueChange = vm::onToken, singleLine = true,
                label = { Text("Zugriffstoken (Subjekt aus Admin-Werkzeuge)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Das Token wird verschlüsselt auf dem Gerät gespeichert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) Button(onClick = { vm.saveNightscout(); editing = false }, enabled = !s.busy) { Text("Speichern") }
            OutlinedButton(onClick = vm::testConnection, enabled = !s.busy) { Text("Verbindung testen") }
        }
        if (!s.missingNsPermissions.isNullOrEmpty()) TokenHelp(s.missingNsPermissions)
    }
}

@Composable
private fun DataCard(s: UiState, vm: MainViewModel) {
    SectionCard("Was übertragen wird", Icons.Filled.Tune, subtitle = "als Behandlung nach Nightscout/AAPS") {
        SwitchRow("Training", s.syncExercise, vm::onExercise, "„Exercise“ mit Distanz, Kalorien, Puls")
        if (s.syncExercise) {
            LabeledSlider("Training ab ${s.minExerciseMinutes} min", s.minExerciseMinutes.toFloat(), 0f..60f, 11) {
                vm.onMinExercise((it / 5).roundToInt() * 5)
            }
            SwitchRow("Pulszonen in der Notiz", s.heartRateZones, vm::onHeartRateZones)
            if (s.heartRateZones) {
                LabeledSlider("Maximale Herzfrequenz: ${s.maxHeartRate} bpm", s.maxHeartRate.toFloat(), 140f..220f, 0) {
                    vm.onMaxHeartRate(it.roundToInt())
                }
            }
        }
        HorizontalDivider()
        SwitchRow("Schlaf", s.syncSleep, vm::onSleep, "Notiz mit Dauer, Tief-/REM-Schlaf")
        SwitchRow("Tages-Schritte", s.syncSteps, vm::onSteps, "Notiz am Tagesende")
        SwitchRow("Fieber ab 37,5 °C", s.syncFever, vm::onFever, "Notiz, weil der Insulinbedarf steigen kann")
        SwitchRow("Periode", s.syncCycle, vm::onCycle, "Notiz mit Dauer")
        HorizontalDivider()
        SwitchRow("Duplikate zusammenführen", s.mergeDuplicates, vm::onMergeDuplicates, "gleiches Training aus mehreren Apps nur einmal")
        LabeledSlider("Abgleich: letzte ${s.lookbackDays} Tage", s.lookbackDays.toFloat(), 1f..30f, 28) { vm.onLookback(it.roundToInt()) }
    }
}

@Composable
private fun AutomationCard(s: UiState, vm: MainViewModel) {
    SectionCard("Automatik", Icons.Filled.Schedule) {
        SwitchRow("Automatisch alle 15 min", s.autoSync, vm::onAutoSync)
        if (s.autoSync) {
            SwitchRow("Nur über WLAN", s.wifiOnly, vm::onWifiOnly)
            SwitchRow("Nur beim Laden", s.chargingOnly, vm::onChargingOnly)
            if (s.wifiOnly || s.chargingOnly) {
                Text("„Jetzt synchronisieren“ und die Schnelleinstellungs-Kachel laufen immer sofort.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NotificationCard(s: UiState, vm: MainViewModel) {
    SectionCard("Benachrichtigungen", Icons.Filled.Notifications) {
        SwitchRow("Hypo-Hinweis nach Training", s.notifyHypoRisk, vm::onNotifyHypoRisk, "nur ein Hinweis, die App ändert nichts in AAPS")
        if (s.notifyHypoRisk) {
            LabeledSlider("ab ${s.hypoRiskMinutes} min Training", s.hypoRiskMinutes.toFloat(), 15f..120f, 20) {
                vm.onHypoRiskMinutes((it / 5).roundToInt() * 5)
            }
        }
        SwitchRow(
            "Hinweis vor dem Schlafengehen", s.bedtimeHint, vm::onBedtimeHint,
            "30 min vor deiner üblichen Schlafzeit, nur wenn der Abend früheren Hypo-Nächten ähnelt",
        )
        SwitchRow("Wochenbericht", s.weeklyReport, vm::onWeeklyReport, "sonntags 19 Uhr: Nächte, Hypos, Sport")
        SwitchRow("Sync-Fehler melden", s.notifyErrors, vm::onNotifyErrors)
    }
}

@Composable
private fun SourcesCard(s: UiState, vm: MainViewModel) {
    SectionCard("Quell-Apps", Icons.Filled.Apps, subtitle = "Trainings und Schlaf nur aus diesen Apps") {
        s.knownSources.entries.sortedBy { it.value }.forEach { (pkg, name) ->
            SwitchRow(name, pkg !in s.excludedSources, { vm.onSourceEnabled(pkg, it) })
        }
    }
}

@Composable
private fun LogCard(s: UiState) {
    SectionCard("Zuletzt übertragen", Icons.Filled.History) {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        s.activityLog.take(8).forEach { e ->
            Column {
                Text("${fmt.format(Date(e.at))} · ${e.action}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, valueRange = range, steps = steps, onValueChange = onChange)
    }
}

/** Anleitung, wenn dem Token Rechte fehlen – die Standardrolle „careportal“ darf nur anlegen. */
@Composable
private fun TokenHelp(missing: List<String>) {
    Banner(
        "Dem Token fehlen: ${missing.joinToString()}\n\n" +
            "So richtest du es ein:\n" +
            "1. Nightscout → Admin-Werkzeuge → Rolle anlegen oder bearbeiten\n" +
            "2. Berechtigungen: api:treatments:* (für die Auswertung zusätzlich api:entries:read, durch Leerzeichen getrennt)\n" +
            "3. Die Rolle beim Subjekt eintragen und speichern\n" +
            "Das Token bleibt gleich; danach erneut „Verbindung testen“.",
        isError = true, icon = Icons.Filled.Warning,
    )
}

private fun relative(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "gerade eben"
        minutes < 60 -> "vor $minutes min"
        minutes < 24 * 60 -> "vor ${minutes / 60} h"
        else -> "am " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(at))
    }
}

private fun openHealthConnectSettings(context: Context) {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        android.health.connect.HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS
    } else HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
    val intent = Intent(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}
