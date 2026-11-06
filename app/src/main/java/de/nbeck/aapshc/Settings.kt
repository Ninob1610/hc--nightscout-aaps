package de.nbeck.aapshc

import android.content.Context
import androidx.core.content.edit
import de.nbeck.aapshc.ns.NightscoutClient
import org.json.JSONArray
import org.json.JSONObject

/** Einstellungen und letzter Sync-Status, app-privat in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var nsUrl: String
        get() = prefs.getString("ns_url", "") ?: ""
        set(value) = prefs.edit { putString("ns_url", NightscoutClient.normalizeUrl(value)) }

    /** Nightscout-Zugriffstoken eines Subjekts (Admin-Werkzeuge), nicht das API_SECRET. */
    var nsToken: String
        get() {
            val stored = prefs.getString("ns_token", "") ?: ""
            if (stored.isEmpty()) return ""
            if (!SecretBox.isEncrypted(stored)) {
                // Altbestand aus Version 0.1: beim ersten Lesen verschlüsselt ablegen.
                nsToken = stored
                return stored
            }
            return SecretBox.decrypt(stored).orEmpty()
        }
        set(value) = prefs.edit {
            val token = value.trim()
            putString("ns_token", if (token.isEmpty()) "" else SecretBox.encrypt(token))
        }

    var syncExercise: Boolean
        get() = prefs.getBoolean("sync_exercise", true)
        set(value) = prefs.edit { putBoolean("sync_exercise", value) }

    var syncSleep: Boolean
        get() = prefs.getBoolean("sync_sleep", true)
        set(value) = prefs.edit { putBoolean("sync_sleep", value) }

    var syncSteps: Boolean
        get() = prefs.getBoolean("sync_steps", false)
        set(value) = prefs.edit { putBoolean("sync_steps", value) }

    /** Notiz bei Körpertemperatur ab 37,5 °C. */
    var syncFever: Boolean
        get() = prefs.getBoolean("sync_fever", false)
        set(value) = prefs.edit { putBoolean("sync_fever", value) }

    /** Periode als Notiz mit Dauer. */
    var syncCycle: Boolean
        get() = prefs.getBoolean("sync_cycle", false)
        set(value) = prefs.edit { putBoolean("sync_cycle", value) }

    /** Notiz bei deutlich erhöhtem Ruhepuls / gesunkener HRV (möglicher Infekt). */
    var syncVitals: Boolean
        get() = prefs.getBoolean("sync_vitals", false)
        set(value) = prefs.edit { putBoolean("sync_vitals", value) }

    var heartRateZones: Boolean
        get() = prefs.getBoolean("hr_zones", true)
        set(value) = prefs.edit { putBoolean("hr_zones", value) }

    /** Maximale Herzfrequenz als Basis der Pulszonen. */
    var maxHeartRate: Int
        get() = prefs.getInt("max_hr", 190)
        set(value) = prefs.edit { putInt("max_hr", value.coerceIn(120, 230)) }

    /** Hinweis nach langem/intensivem Training, auf (nächtliche) Hypos zu achten. */
    var notifyHypoRisk: Boolean
        get() = prefs.getBoolean("notify_hypo_risk", true)
        set(value) = prefs.edit { putBoolean("notify_hypo_risk", value) }

    /** Ab dieser Trainingsdauer (Minuten) gibt es den Hypo-Hinweis. */
    var hypoRiskMinutes: Int
        get() = prefs.getInt("hypo_risk_min", 45)
        set(value) = prefs.edit { putInt("hypo_risk_min", value.coerceIn(15, 180)) }

    /** Hinweis etwa 30 min vor der üblichen Einschlafzeit, wenn der Abend riskant aussieht. */
    var bedtimeHint: Boolean
        get() = prefs.getBoolean("bedtime_hint", true)
        set(value) = prefs.edit { putBoolean("bedtime_hint", value) }

    /** Übliche Einschlafzeit in Minuten nach Mitternacht, aus den letzten Nächten gelernt. */
    var typicalBedtimeMinutes: Int
        get() = prefs.getInt("typical_bedtime", 22 * 60 + 30)
        set(value) = prefs.edit { putInt("typical_bedtime", value) }

    var glucoseUnit: GlucoseUnit
        get() = runCatching { GlucoseUnit.valueOf(prefs.getString("glucose_unit", "MGDL")!!) }.getOrDefault(GlucoseUnit.MGDL)
        set(value) = prefs.edit { putString("glucose_unit", value.name) }

    var weeklyReport: Boolean
        get() = prefs.getBoolean("weekly_report", true)
        set(value) = prefs.edit { putBoolean("weekly_report", value) }

    var notifyErrors: Boolean
        get() = prefs.getBoolean("notify_errors", true)
        set(value) = prefs.edit { putBoolean("notify_errors", value) }

    /** Fehlgeschlagene Hintergrundläufe in Folge; steuert die Fehlerbenachrichtigung. */
    var consecutiveFailures: Int
        get() = prefs.getInt("consecutive_failures", 0)
        set(value) = prefs.edit { putInt("consecutive_failures", value) }

    var minExerciseMinutes: Int
        get() = prefs.getInt("min_exercise_min", 10)
        set(value) = prefs.edit { putInt("min_exercise_min", value.coerceIn(0, 240)) }

    /** Wie viele Tage zurück Health Connect bei jedem Lauf abgeglichen wird. */
    var lookbackDays: Int
        get() = prefs.getInt("lookback_days", 7)
        set(value) = prefs.edit { putInt("lookback_days", value.coerceIn(1, 30)) }

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = prefs.edit { putBoolean("auto_sync", value) }

    /** Hintergrund-Sync nur in nicht getakteten Netzen (WLAN). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(value) = prefs.edit { putBoolean("wifi_only", value) }

    /** Hintergrund-Sync nur, während das Gerät lädt. */
    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging_only", false)
        set(value) = prefs.edit { putBoolean("charging_only", value) }

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(value) = prefs.edit { putLong("last_sync_at", value) }

    var lastSyncMessage: String
        get() = prefs.getString("last_sync_message", "") ?: ""
        set(value) = prefs.edit { putString("last_sync_message", value) }

    /** Paketnamen der Quell-Apps, deren Trainings und Schlaf ignoriert werden. */
    var excludedSources: Set<String>
        get() = prefs.getStringSet("excluded_sources", emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet("excluded_sources", value) }

    /** Zuletzt in Health Connect gesehene Quell-Apps: Paketname → Anzeigename. */
    var knownSources: Map<String, String>
        get() = runCatching {
            val o = JSONObject(prefs.getString("known_sources", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(value) = prefs.edit { putString("known_sources", JSONObject(value).toString()) }

    var mergeDuplicates: Boolean
        get() = prefs.getBoolean("merge_duplicates", true)
        set(value) = prefs.edit { putBoolean("merge_duplicates", value) }

    /** Die letzten übertragenen Einträge, neueste zuerst. */
    var activityLog: List<LogEntry>
        get() = runCatching {
            val a = JSONArray(prefs.getString("activity_log", "[]") ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LogEntry(o.getLong("t"), o.getString("a"), o.getString("n"))
            }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit {
            val a = JSONArray()
            value.take(LOG_SIZE).forEach { a.put(JSONObject().put("t", it.at).put("a", it.action).put("n", it.text)) }
            putString("activity_log", a.toString())
        }

    val isNightscoutConfigured: Boolean
        get() = nsUrl.startsWith("http") && nsToken.isNotBlank()

    /** Kurzfassung der letzten Nacht für das Widget. */
    var lastNight: NightSnapshot?
        get() = runCatching {
            val o = JSONObject(prefs.getString("last_night", null) ?: return null)
            NightSnapshot(
                start = o.getLong("start"), end = o.getLong("end"),
                timeInRange = o.optInt("tir", -1).takeIf { it >= 0 },
                hypoEvents = o.getInt("hypo"), compressionEvents = o.getInt("compr"),
                sleepMinutes = o.getLong("sleep"),
            )
        }.getOrNull()
        set(value) = prefs.edit {
            if (value == null) remove("last_night") else putString(
                "last_night",
                JSONObject().put("start", value.start).put("end", value.end).put("tir", value.timeInRange ?: -1)
                    .put("hypo", value.hypoEvents).put("compr", value.compressionEvents).put("sleep", value.sleepMinutes).toString(),
            )
        }

    data class NightSnapshot(
        val start: Long,
        val end: Long,
        val timeInRange: Int?,
        val hypoEvents: Int,
        val compressionEvents: Int,
        val sleepMinutes: Long,
    )

    data class LogEntry(val at: Long, val action: String, val text: String)

    companion object {
        private const val LOG_SIZE = 30
    }
}
