package de.nbeck.aapshc.sync

import android.content.Context
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.hc.HealthConnectSource
import de.nbeck.aapshc.ns.NightscoutAuthException
import de.nbeck.aapshc.ns.NightscoutClient
import de.nbeck.aapshc.ns.Permissions
import de.nbeck.aapshc.ns.Treatment
import de.nbeck.aapshc.ns.UpsertResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/** Konfigurations- oder Berechtigungsproblem: ein erneuter Versuch ändert nichts. */
class SyncException(message: String) : Exception(message)

data class SyncResult(
    val uploaded: Int,
    val deleted: Int,
    val unchanged: Int,
    val skippedRemote: Int,
    val duplicates: Int,
    val warnings: List<String>,
    /** In diesem Lauf erstmals übertragene Trainings (für den Hypo-Hinweis). */
    val newExercises: List<ExerciseInfo> = emptyList(),
) {
    override fun toString() = buildList {
        add("$uploaded hochgeladen")
        add("$deleted gelöscht")
        add("$unchanged unverändert")
        if (duplicates > 0) add("$duplicates Duplikate zusammengeführt")
        if (skippedRemote > 0) add("$skippedRemote in Nightscout/AAPS gelöscht, übersprungen")
    }.joinToString(", ") + warnings.joinToString("") { " ⚠ $it" }
}

/**
 * Gleicht das Zeitfenster der letzten [Settings.lookbackDays] Tage zwischen Health Connect
 * und Nightscout ab. Nur geänderte Einträge werden geschrieben; in Health Connect gelöschte
 * Einträge werden in Nightscout gelöscht (AAPS übernimmt die Löschung).
 */
class SyncEngine(context: Context) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val source = HealthConnectSource(app)

    suspend fun run(background: Boolean): SyncResult = lock.withLock {
        try {
            sync(background).also {
                settings.lastSyncMessage = "OK: $it"
            }
        } catch (e: Exception) {
            settings.lastSyncMessage = "Fehler: ${e.message ?: e.javaClass.simpleName}"
            throw e
        } finally {
            settings.lastSyncAt = System.currentTimeMillis()
        }
    }

    fun resetState() = SyncState(stateFile()).clear()

    private suspend fun sync(background: Boolean): SyncResult {
        if (!settings.isNightscoutConfigured) throw SyncException("Nightscout-URL und Token fehlen")
        if (source.availability() != HealthConnectSource.Availability.AVAILABLE) {
            throw SyncException("Health Connect ist nicht verfügbar")
        }
        val granted = source.grantedPermissions()
        if (background && HealthConnectSource.BACKGROUND !in granted) {
            throw SyncException("Hintergrundzugriff auf Health Connect nicht erlaubt")
        }
        fun require(type: KClass<out Record>, label: String) {
            if (HealthConnectSource.read(type) !in granted) throw SyncException("Health-Connect-Berechtigung für $label fehlt")
        }

        // Rechte vorab prüfen, damit ein falsch eingerichtetes Token nicht mitten im Lauf scheitert.
        val ns = NightscoutClient(settings.nsUrl, settings.nsToken)
        val missing = ns.missingPermissions()
        if (Permissions.CREATE in missing || Permissions.UPDATE in missing) {
            throw NightscoutAuthException("Dem Nightscout-Token fehlen Rechte: ${missing.joinToString()}")
        }
        val warnings = mutableListOf<String>()
        val canDelete = Permissions.DELETE !in missing
        if (!canDelete) warnings += "ohne Recht ${Permissions.DELETE} wird nichts gelöscht"

        val state = SyncState(stateFile())
        val now = Instant.now()
        val from = now.minus(Duration.ofDays(settings.lookbackDays.toLong()))
        val excluded = settings.excludedSources
        val seenSources = settings.knownSources.toMutableMap()
        var duplicates = 0

        // id → neuer Inhalt; null heißt „unverändert, nicht neu berechnet“.
        val desired = linkedMapOf<String, Pair<String, Pending?>>()

        if (settings.syncExercise) {
            require(ExerciseSessionRecord::class, "Training")
            val minMinutes = settings.minExerciseMinutes.toLong()
            val maxHr = if (settings.heartRateZones) settings.maxHeartRate else 0
            val candidates = source.readExercises(from, now)
                .onEach { seenSources[it.metadata.dataOrigin.packageName] = source.sourceName(it.metadata.dataOrigin.packageName) }
                .filter { it.metadata.dataOrigin.packageName !in excluded }
                .filter { Duration.between(it.startTime, it.endTime).toMinutes() >= minMinutes }
            val sessions = dedup(candidates) { Dedup.Session(it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it) }
            duplicates += candidates.size - sessions.size
            for (session in sessions) {
                val id = Mapper.identifierFor("exercise:${session.metadata.id}")
                val modified = session.metadata.lastModifiedTime.toEpochMilli()
                val prev = state.entries[id]
                // Puls/Distanz können verspätet in Health Connect landen: 48 h lang neu berechnen.
                val settled = session.endTime.isBefore(now.minus(SETTLE_TIME))
                desired[id] = SyncState.EXERCISE to
                    if (prev != null && prev.modified == modified && settled) null
                    else source.enrich(session, granted, maxHr).let { Pending(Mapper.exercise(it), modified, it) }
            }
        }

        if (settings.syncSleep) {
            require(SleepSessionRecord::class, "Schlaf")
            val candidates = source.readSleep(from, now)
                .onEach { seenSources[it.sourcePackage] = it.source }
                .filter { it.sourcePackage !in excluded }
            val sleeps = dedup(candidates) { Dedup.Session(it.start, it.end, it.sourcePackage, it) }
            duplicates += candidates.size - sleeps.size
            for (sleep in sleeps) {
                desired[Mapper.identifierFor("sleep:${sleep.recordId}")] =
                    SyncState.SLEEP to Pending(Mapper.sleep(sleep), 0)
            }
        }
        settings.knownSources = seenSources

        if (settings.syncFever) {
            require(BodyTemperatureRecord::class, "Körpertemperatur")
            for (t in source.readFever(from, now)) {
                desired[Mapper.identifierFor("temperature:${t.recordId}")] =
                    SyncState.TEMPERATURE to Pending(Mapper.temperature(t), 0)
            }
        }

        if (settings.syncCycle) {
            require(MenstruationFlowRecord::class, "Zyklus")
            for (p in source.readPeriods(from, now)) {
                desired[Mapper.identifierFor("period:${p.firstDay}")] = SyncState.PERIOD to Pending(Mapper.period(p), 0)
            }
        }

        if (settings.syncVitals) {
            require(RestingHeartRateRecord::class, "Ruhepuls")
            // 14 Tage Vorlauf für den persönlichen Normalwert.
            val vitals = source.dailyVitals(from.minus(Duration.ofDays(15)), now, granted)
            val feverDays = if (HealthConnectSource.read(BodyTemperatureRecord::class) in granted) {
                source.readFever(from.minus(Duration.ofDays(1)), now).map { it.time.atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
            } else emptySet()
            val firstDay = from.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            for (alert in Vitals.alerts(vitals, feverDays).filter { !it.day.isBefore(firstDay) }) {
                desired[Mapper.identifierFor("vitals:${alert.day}")] = SyncState.VITALS to Pending(Vitals.treatment(alert, now = now), 0)
            }
        }

        if (settings.syncSteps) {
            require(StepsRecord::class, "Schritte")
            val zone = ZoneId.systemDefault()
            val firstDay = from.atZone(zone).toLocalDate().plusDays(1)
            val today = now.atZone(zone).toLocalDate()
            for (day in source.dailySteps(firstDay, now)) {
                if (day.steps <= 0) continue
                val id = Mapper.identifierFor("steps:${day.day}")
                val prev = state.entries[id]
                // Den laufenden Tag höchstens stündlich aktualisieren, um AAPS nicht zu fluten.
                val throttled = day.day == today && prev != null &&
                    now.toEpochMilli() - prev.uploadedAt < STEPS_INTERVAL.toMillis()
                desired[id] = SyncState.STEPS to if (throttled) null else Pending(Mapper.steps(day), 0)
            }
        }

        var uploaded = 0
        var deleted = 0
        var unchanged = 0
        var skippedRemote = 0
        val log = mutableListOf<Settings.LogEntry>()
        val newExercises = mutableListOf<ExerciseInfo>()
        try {
            for ((id, value) in desired) {
                val (category, pending) = value
                if (pending == null) { unchanged++; continue }
                val hash = pending.treatment.contentHash()
                val prev = state.entries[id]
                // Einmal in AAPS/Nightscout gelöschte Einträge nicht immer wieder versuchen.
                if (prev?.hash == hash || prev?.hash == REMOTE_DELETED) { unchanged++; continue }
                val result = ns.upsert(pending.treatment)
                val storedHash = if (result == UpsertResult.DELETED_REMOTELY) REMOTE_DELETED else hash
                state.entries[id] = SyncState.Entry(category, storedHash, pending.treatment.date, pending.modified, now.toEpochMilli())
                if (result == UpsertResult.DELETED_REMOTELY) {
                    skippedRemote++
                } else {
                    uploaded++
                    if (prev == null) pending.exercise?.let { newExercises += it }
                    log += Settings.LogEntry(now.toEpochMilli(), if (prev == null) "neu" else "aktualisiert", pending.treatment.notes)
                }
            }

            // Gelöscht wird nur, was sicher im gelesenen Fenster lag und aus einer aktiven Kategorie stammt.
            if (canDelete) {
                val activeCategories = buildSet {
                    if (settings.syncExercise) add(SyncState.EXERCISE)
                    if (settings.syncSleep) add(SyncState.SLEEP)
                    if (settings.syncFever) add(SyncState.TEMPERATURE)
                    if (settings.syncCycle) add(SyncState.PERIOD)
                    if (settings.syncVitals) add(SyncState.VITALS)
                }
                val safeFrom = from.plus(Duration.ofDays(1)).toEpochMilli()
                val gone = state.entries.filter { (id, e) ->
                    e.category in activeCategories && e.date >= safeFrom && id !in desired
                }.keys
                for (id in gone) {
                    val entry = state.entries.getValue(id)
                    if (entry.hash != REMOTE_DELETED) {
                        ns.delete(id)
                        deleted++
                        log += Settings.LogEntry(now.toEpochMilli(), "gelöscht", "${entry.category} vom ${java.text.DateFormat.getDateInstance().format(entry.date)}")
                    }
                    state.entries.remove(id)
                }
            }

            val pruneBefore = from.minus(Duration.ofDays(2)).toEpochMilli()
            state.entries.entries.removeAll { it.value.date < pruneBefore }
        } finally {
            state.save()
            if (log.isNotEmpty()) settings.activityLog = log.reversed() + settings.activityLog
        }
        return SyncResult(uploaded, deleted, unchanged, skippedRemote, duplicates, warnings, newExercises)
    }

    private fun <T> dedup(items: List<T>, session: (T) -> Dedup.Session<T>): List<T> =
        if (settings.mergeDuplicates) Dedup.keepDistinct(items.map(session)) else items

    private fun stateFile() = File(app.filesDir, "sync_state.json")

    private class Pending(val treatment: Treatment, val modified: Long, val exercise: ExerciseInfo? = null)

    companion object {
        private val lock = Mutex()
        private val SETTLE_TIME = Duration.ofHours(48)
        private val STEPS_INTERVAL = Duration.ofMinutes(60)
        private const val REMOTE_DELETED = "remote-deleted"
    }
}
