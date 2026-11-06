package de.nbeck.aapshc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.hc.HealthConnectSource
import de.nbeck.aapshc.ns.NightscoutAuthException
import de.nbeck.aapshc.ns.NightscoutClient
import de.nbeck.aapshc.sync.AnalysisReport
import de.nbeck.aapshc.sync.AnalysisRunner
import de.nbeck.aapshc.sync.BedtimeWorker
import de.nbeck.aapshc.sync.CsvExport
import de.nbeck.aapshc.sync.PdfReport
import de.nbeck.aapshc.sync.WeeklyReportWorker
import de.nbeck.aapshc.sync.PostSync
import de.nbeck.aapshc.sync.SyncEngine
import de.nbeck.aapshc.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val nsUrl: String = "",
    val nsToken: String = "",
    val syncExercise: Boolean = true,
    val syncSleep: Boolean = true,
    val syncSteps: Boolean = false,
    val minExerciseMinutes: Int = 10,
    val lookbackDays: Int = 7,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val availability: HealthConnectSource.Availability = HealthConnectSource.Availability.NOT_INSTALLED,
    val granted: Set<String> = emptySet(),
    val backgroundSupported: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncAt: Long = 0,
    val lastSyncMessage: String = "",
    val mergeDuplicates: Boolean = true,
    val knownSources: Map<String, String> = emptyMap(),
    val excludedSources: Set<String> = emptySet(),
    val activityLog: List<Settings.LogEntry> = emptyList(),
    /** Fehlende Nightscout-Rechte aus dem letzten Verbindungstest; null = nicht geprüft. */
    val missingNsPermissions: List<String>? = null,
    val syncFever: Boolean = false,
    val syncCycle: Boolean = false,
    val heartRateZones: Boolean = true,
    val syncVitals: Boolean = false,
    val maxHeartRate: Int = 190,
    val notifyHypoRisk: Boolean = true,
    val hypoRiskMinutes: Int = 45,
    val notifyErrors: Boolean = true,
    val weeklyReport: Boolean = true,
    val bedtimeHint: Boolean = true,
    val glucoseUnit: de.nbeck.aapshc.GlucoseUnit = de.nbeck.aapshc.GlucoseUnit.MGDL,
    val analysisDays: Long = 30,
    val analysis: AnalysisReport? = null,
    val analysisBusy: Boolean = false,
    val analysisError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val source = HealthConnectSource(app)
    private val engine = SyncEngine(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Demo-Modus für Screenshots: erfundene Daten, nichts wird gespeichert oder synchronisiert. */
    private var demo = false

    fun enableDemo() {
        if (demo) return
        demo = true
        _state.value = DemoData.state()
    }

    init {
        _state.value = UiState(
            nsUrl = settings.nsUrl,
            nsToken = settings.nsToken,
            syncExercise = settings.syncExercise,
            syncSleep = settings.syncSleep,
            syncSteps = settings.syncSteps,
            minExerciseMinutes = settings.minExerciseMinutes,
            lookbackDays = settings.lookbackDays,
            autoSync = settings.autoSync,
            wifiOnly = settings.wifiOnly,
            chargingOnly = settings.chargingOnly,
            mergeDuplicates = settings.mergeDuplicates,
            excludedSources = settings.excludedSources,
            syncFever = settings.syncFever,
            syncCycle = settings.syncCycle,
            heartRateZones = settings.heartRateZones,
            syncVitals = settings.syncVitals,
            maxHeartRate = settings.maxHeartRate,
            notifyHypoRisk = settings.notifyHypoRisk,
            hypoRiskMinutes = settings.hypoRiskMinutes,
            notifyErrors = settings.notifyErrors,
            weeklyReport = settings.weeklyReport,
            bedtimeHint = settings.bedtimeHint,
            glucoseUnit = settings.glucoseUnit,
        )
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        if (demo) return@launch
        val availability = source.availability()
        val available = availability == HealthConnectSource.Availability.AVAILABLE
        val granted = if (available) runCatching { source.grantedPermissions() }.getOrDefault(emptySet()) else emptySet()
        val background = available && runCatching { source.isBackgroundReadAvailable() }.getOrDefault(false)
        if (demo) return@launch // Demo wurde während des Abgleichs eingeschaltet
        _state.update {
            it.copy(
                availability = availability,
                granted = granted,
                backgroundSupported = background,
                lastSyncAt = settings.lastSyncAt,
                lastSyncMessage = settings.lastSyncMessage,
                knownSources = settings.knownSources,
                activityLog = settings.activityLog,
            )
        }
    }

    fun onUrl(v: String) = _state.update { it.copy(nsUrl = v) }
    fun onToken(v: String) = _state.update { it.copy(nsToken = v) }
    fun onExercise(v: Boolean) = save { it.copy(syncExercise = v) }
    fun onSleep(v: Boolean) = save { it.copy(syncSleep = v) }
    fun onSteps(v: Boolean) = save { it.copy(syncSteps = v) }
    fun onMinExercise(v: Int) = save { it.copy(minExerciseMinutes = v) }
    fun onLookback(v: Int) = save { it.copy(lookbackDays = v) }
    fun onAutoSync(v: Boolean) = save { it.copy(autoSync = v) }
    fun onWifiOnly(v: Boolean) = save { it.copy(wifiOnly = v) }
    fun onChargingOnly(v: Boolean) = save { it.copy(chargingOnly = v) }
    fun onFever(v: Boolean) = save { it.copy(syncFever = v) }
    fun onCycle(v: Boolean) = save { it.copy(syncCycle = v) }
    fun onHeartRateZones(v: Boolean) = save { it.copy(heartRateZones = v) }
    fun onVitals(v: Boolean) = save { it.copy(syncVitals = v) }
    fun onMaxHeartRate(v: Int) = save { it.copy(maxHeartRate = v) }
    fun onNotifyHypoRisk(v: Boolean) = save { it.copy(notifyHypoRisk = v) }
    fun onHypoRiskMinutes(v: Int) = save { it.copy(hypoRiskMinutes = v) }
    fun onNotifyErrors(v: Boolean) = save { it.copy(notifyErrors = v) }
    fun onGlucoseUnit(v: de.nbeck.aapshc.GlucoseUnit) = save { it.copy(glucoseUnit = v) }
    fun onBedtimeHint(v: Boolean) {
        save { it.copy(bedtimeHint = v) }
        BedtimeWorker.schedule(getApplication())
    }
    fun onWeeklyReport(v: Boolean) {
        save { it.copy(weeklyReport = v) }
        WeeklyReportWorker.schedule(getApplication())
    }
    fun onMergeDuplicates(v: Boolean) = save { it.copy(mergeDuplicates = v) }
    fun onSourceEnabled(pkg: String, enabled: Boolean) =
        save { it.copy(excludedSources = if (enabled) it.excludedSources - pkg else it.excludedSources + pkg) }

    fun saveNightscout() {
        save { it }
        _state.update { it.copy(message = "Gespeichert") }
    }

    private fun save(change: (UiState) -> UiState) {
        _state.update(change)
        if (demo) return
        val s = _state.value
        settings.nsUrl = s.nsUrl
        settings.nsToken = s.nsToken
        settings.syncExercise = s.syncExercise
        settings.syncSleep = s.syncSleep
        settings.syncSteps = s.syncSteps
        settings.minExerciseMinutes = s.minExerciseMinutes
        settings.lookbackDays = s.lookbackDays
        settings.autoSync = s.autoSync
        settings.wifiOnly = s.wifiOnly
        settings.chargingOnly = s.chargingOnly
        settings.mergeDuplicates = s.mergeDuplicates
        settings.excludedSources = s.excludedSources
        settings.syncFever = s.syncFever
        settings.syncCycle = s.syncCycle
        settings.heartRateZones = s.heartRateZones
        settings.syncVitals = s.syncVitals
        settings.maxHeartRate = s.maxHeartRate
        settings.notifyHypoRisk = s.notifyHypoRisk
        settings.hypoRiskMinutes = s.hypoRiskMinutes
        settings.notifyErrors = s.notifyErrors
        settings.weeklyReport = s.weeklyReport
        settings.bedtimeHint = s.bedtimeHint
        settings.glucoseUnit = s.glucoseUnit
        SyncWorker.schedule(getApplication())
    }

    fun testConnection() = if (demo) busy { "Verbindung OK – Nightscout 15.0.9, Subjekt „healthconnect“, alle Rechte für den Sync vorhanden" } else busy {
        save { it }
        _state.update { it.copy(nsUrl = settings.nsUrl) }
        val info = NightscoutClient(settings.nsUrl, settings.nsToken).testConnection()
        _state.update { it.copy(missingNsPermissions = info.missing) }
        if (info.missing.isEmpty()) {
            "Verbindung OK – Nightscout ${info.version}, Subjekt „${info.subject}“, alle Rechte für den Sync vorhanden" +
                if (info.canReadEntries) "" else ". Für die Auswertung fehlt noch api:entries:read (optional)."
        } else "Fehler: Token „${info.subject}“ fehlen Rechte: ${info.missing.joinToString()}"
    }

    fun syncNow() = if (demo) busy { "Sync: 0 hochgeladen, 0 gelöscht, 51 unverändert" } else busy {
        save { it }
        try {
            val result = engine.run(background = false)
            PostSync.onSuccess(getApplication(), result)
            "Sync: $result"
        } catch (e: NightscoutAuthException) {
            // Anleitung zum Einrichten der Rolle direkt einblenden.
            val missing = runCatching { NightscoutClient(settings.nsUrl, settings.nsToken).missingPermissions() }.getOrNull()
            _state.update { it.copy(missingNsPermissions = missing) }
            throw e
        }
    }

    /** Vergisst, was schon hochgeladen wurde; der nächste Lauf schreibt alles neu (ohne Duplikate). */
    fun resetState() {
        engine.resetState()
        _state.update { it.copy(message = "Sync-Status zurückgesetzt") }
    }

    fun runAnalysis(days: Long = _state.value.analysisDays) = viewModelScope.launch {
        if (demo) {
            _state.update { it.copy(analysis = DemoData.report(days.toInt()), analysisDays = days) }
            return@launch
        }
        _state.update { it.copy(analysisBusy = true, analysisError = null, analysisDays = days) }
        runCatching { AnalysisRunner(getApplication()).run(days) }
            .onSuccess { report ->
                _state.update { it.copy(analysis = report, analysisBusy = false) }
                NightWidget.refresh(getApplication())
            }
            .onFailure { e -> _state.update { it.copy(analysisBusy = false, analysisError = e.message ?: e.javaClass.simpleName) } }
    }

    /** Schreibt die aktuelle Auswertung als CSV in die vom Nutzer gewählte Datei. */
    fun exportCsv(uri: android.net.Uri) = viewModelScope.launch {
        val report = _state.value.analysis ?: return@launch
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(CsvExport.build(report, unit = settings.glucoseUnit).toByteArray(Charsets.UTF_8))
                } ?: error("Datei konnte nicht geöffnet werden")
            }
        }
        _state.update {
            it.copy(analysisError = result.exceptionOrNull()?.let { e -> "Export fehlgeschlagen: ${e.message}" })
        }
    }

    /** Schreibt die aktuelle Auswertung als PDF-Bericht in die vom Nutzer gewählte Datei. */
    fun exportPdf(uri: android.net.Uri) = viewModelScope.launch {
        val report = _state.value.analysis ?: return@launch
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    PdfReport(report, _state.value.glucoseUnit).write(it)
                } ?: error("Datei konnte nicht geöffnet werden")
            }
        }
        _state.update {
            it.copy(analysisError = result.exceptionOrNull()?.let { e -> "PDF fehlgeschlagen: ${e.message}" })
        }
    }

    /** Nur Debug/Demo: PDF ohne Dateiauswahl in den App-Cache schreiben (abholen per `adb exec-out run-as … cat`). */
    fun exportDemoPdf() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (!demo) return@launch
        val report = _state.value.analysis ?: return@launch
        val dir = getApplication<Application>().cacheDir
        java.io.File(dir, "demo-bericht.pdf").outputStream().use { PdfReport(report, _state.value.glucoseUnit).write(it) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun busy(block: suspend () -> String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        val msg = runCatching { block() }.getOrElse { "Fehler: ${it.message ?: it.javaClass.simpleName}" }
        _state.update { it.copy(busy = false, message = msg) }
        refresh()
    }
}
