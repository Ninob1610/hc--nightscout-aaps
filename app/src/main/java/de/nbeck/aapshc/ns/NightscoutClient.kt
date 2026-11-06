package de.nbeck.aapshc.ns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

open class NightscoutException(message: String) : IOException(message)

/** Konfigurationsfehler (Token/Rechte): Wiederholen hilft nicht. */
class NightscoutAuthException(message: String) : NightscoutException(message)

data class ConnectionInfo(
    val version: String,
    val subject: String,
    val missing: List<String>,
    /** Darf CGM-Werte lesen (für die Auswertung)? */
    val canReadEntries: Boolean,
)

/** Ein CGM-Wert aus Nightscout (immer mg/dl). */
data class GlucoseValue(val date: Long, val mgdl: Int)

/** Eine gelesene Behandlung, auf das für die Auswertung Nötige reduziert. */
sealed class TherapyItem(open val date: Long) {
    /** [smb] = automatischer Mikrobolus von AAPS, sonst ein vom Menschen ausgelöster Bolus. */
    data class Bolus(override val date: Long, val units: Double, val smb: Boolean) : TherapyItem(date)
    data class Carbs(override val date: Long, val grams: Double) : TherapyItem(date)
    data class TempTarget(override val date: Long, val durationMinutes: Long, val targetMgdl: Int, val reason: String?) : TherapyItem(date)
}

/** Ergebnis eines Schreibversuchs. */
enum class UpsertResult {
    WRITTEN,

    /** Das Dokument wurde in Nightscout/AAPS gelöscht; v3 erlaubt kein Überschreiben (HTTP 410). */
    DELETED_REMOTELY,
}

/**
 * Minimaler Nightscout-API-v3-Client.
 *
 * Authentifizierung wie bei AAPS: Zugriffstoken → JWT über /api/v2/authorization/request.
 * Die Antwort enthält die Rechte des Tokens; fehlende Rechte werden vorab gemeldet statt
 * als nichtssagender 403 beim Schreiben.
 *
 * Schreiben per PUT /api/v3/treatments/{identifier}: neu anlegen braucht `create`,
 * überschreiben `update`, löschen `delete`.
 */
class NightscoutClient(
    baseUrl: String,
    private val accessToken: String,
    private val http: OkHttpClient = defaultHttp,
) {
    private val base = normalizeUrl(baseUrl)
    private var jwt: String? = null
    private var permissions: List<String> = emptyList()
    private var subject: String = ""

    suspend fun testConnection(): ConnectionInfo = withContext(Dispatchers.IO) {
        val version = runCatching {
            val text = execute(Request.Builder().url("$base/api/v3/version").get().build(), auth = false).second
            JSONObject(text).optJSONObject("result")?.optString("version")
        }.getOrElse { e ->
            if (e is NightscoutException) throw e
            throw NightscoutException("Keine Nightscout-Antwort unter $base – URL prüfen")
        }
        authenticate()
        ConnectionInfo(
            version.orEmpty().ifBlank { "unbekannt" }, subject, Permissions.missing(permissions),
            Permissions.granted(permissions, Permissions.ENTRIES_READ),
        )
    }

    /** Fehlende Pflichtrechte; authentifiziert dafür bei Bedarf. */
    suspend fun missingPermissions(): List<String> = withContext(Dispatchers.IO) {
        authenticate()
        Permissions.missing(permissions)
    }

    suspend fun canReadEntries(): Boolean = withContext(Dispatchers.IO) {
        authenticate()
        Permissions.granted(permissions, Permissions.ENTRIES_READ)
    }

    /** CGM-Werte im Zeitraum, aufsteigend sortiert. Nur Lesen – es wird nie Glukose geschrieben. */
    suspend fun glucose(from: Long, to: Long): List<GlucoseValue> = withContext(Dispatchers.IO) {
        val url = "$base/api/v3/entries?date\$gte=$from&date\$lte=$to&sort=date&limit=1000&fields=date,sgv"
        val text = execute(Request.Builder().url(url).get().build()).second
        val result = JSONObject(text).optJSONArray("result") ?: return@withContext emptyList()
        (0 until result.length()).mapNotNull { i ->
            val o = result.optJSONObject(i) ?: return@mapNotNull null
            val sgv = o.optInt("sgv", 0)
            val date = o.optLong("date", 0L)
            if (sgv in 20..600 && date > 0) GlucoseValue(date, sgv) else null
        }
    }

    suspend fun canReadTreatments(): Boolean = withContext(Dispatchers.IO) {
        authenticate()
        Permissions.granted(permissions, Permissions.TREATMENTS_READ)
    }

    /** Boli, Kohlenhydrate und temporäre Ziele im Zeitraum. Nur Lesen. */
    suspend fun therapy(from: Long, to: Long): List<TherapyItem> = withContext(Dispatchers.IO) {
        val fields = "date,eventType,insulin,type,isSMB,carbs,targetBottom,targetTop,units,duration,durationInMilliseconds,reason,isValid"
        val url = "$base/api/v3/treatments?date\$gte=$from&date\$lte=$to&sort=date&limit=1000&fields=$fields"
        val result = JSONObject(execute(Request.Builder().url(url).get().build()).second).optJSONArray("result")
            ?: return@withContext emptyList()
        (0 until result.length()).flatMap { i -> parseTherapy(result.optJSONObject(i)) }
    }

    suspend fun upsert(treatment: Treatment): UpsertResult = withContext(Dispatchers.IO) {
        val body = treatment.toJson().toString().toRequestBody(JSON)
        val request = Request.Builder().url(documentUrl(treatment.identifier)).put(body).build()
        val code = execute(request, ignoreCodes = setOf(410)).first
        if (code == 410) UpsertResult.DELETED_REMOTELY else UpsertResult.WRITTEN
    }

    /** Löscht (in v3: markiert als ungültig, AAPS übernimmt das). Fehlende Dokumente sind kein Fehler. */
    suspend fun delete(identifier: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(documentUrl(identifier)).delete().build()
        execute(request, ignoreCodes = setOf(404, 410))
        Unit
    }

    private fun documentUrl(identifier: String) =
        "$base/api/v3/treatments/" + URLEncoder.encode(identifier, "UTF-8")

    private fun authenticate(): String {
        jwt?.let { return it }
        val url = "$base/api/v2/authorization/request/" + URLEncoder.encode(accessToken, "UTF-8")
        val (code, text) = execute(Request.Builder().url(url).get().build(), auth = false, ignoreCodes = setOf(401, 403, 404))
        val json = runCatching { JSONObject(text) }.getOrNull()
        val token = json?.optString("token").orEmpty()
        if (code != 200 || token.isBlank()) {
            throw NightscoutAuthException(
                "Zugriffstoken unbekannt. Es muss das Token eines Subjekts aus den Admin-Werkzeugen sein " +
                    "(z. B. „healthconnect-1a2b3c…“), nicht das API_SECRET."
            )
        }
        subject = json?.optString("sub").orEmpty()
        permissions = buildList {
            val groups = json?.optJSONArray("permissionGroups")
            for (i in 0 until (groups?.length() ?: 0)) {
                val group = groups!!.optJSONArray(i) ?: continue
                for (j in 0 until group.length()) add(group.optString(j))
            }
        }
        jwt = token
        return token
    }

    private fun execute(request: Request, auth: Boolean = true, ignoreCodes: Set<Int> = emptySet()): Pair<Int, String> {
        val authorized = if (auth) {
            request.newBuilder().header("Authorization", "Bearer ${authenticate()}").build()
        } else request
        http.newCall(authorized).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful || response.code in ignoreCodes) return response.code to text
            if (response.code == 401 && auth) jwt = null
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            when (response.code) {
                401, 403 -> throw NightscoutAuthException(
                    "HTTP ${response.code}: dem Token fehlen Rechte" +
                        Permissions.missing(permissions).takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" }.orEmpty() +
                        (if (message.isNotBlank()) " – $message" else "")
                )
                404 -> throw NightscoutException("HTTP 404: API v3 nicht gefunden (Nightscout ≥ 15 nötig)")
                else -> throw NightscoutException("HTTP ${response.code}: ${message.ifBlank { text.take(200) }}")
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        internal fun parseTherapy(o: JSONObject?): List<TherapyItem> {
            if (o == null || !o.optBoolean("isValid", true)) return emptyList()
            val date = o.optLong("date", 0L).takeIf { it > 0 } ?: return emptyList()
            val out = mutableListOf<TherapyItem>()
            val insulin = o.optDouble("insulin", 0.0)
            val type = o.optString("type")
            if (insulin > 0 && type != "PRIMING") {
                out += TherapyItem.Bolus(date, insulin, smb = type == "SMB" || o.optBoolean("isSMB", false))
            }
            val carbs = o.optDouble("carbs", 0.0)
            if (carbs > 0) out += TherapyItem.Carbs(date, carbs)
            if (o.optString("eventType") == "Temporary Target") {
                val minutes = if (o.has("durationInMilliseconds")) o.optLong("durationInMilliseconds") / 60_000 else o.optLong("duration")
                val bottom = o.optDouble("targetBottom", 0.0)
                val top = o.optDouble("targetTop", bottom)
                // Ziele in mmol/l (kleine Zahlen) auf mg/dl umrechnen.
                val mgdl = ((bottom + top) / 2).let { if (it in 0.1..30.0) it * 18.0 else it }.toInt()
                if (minutes > 0 && mgdl > 0) out += TherapyItem.TempTarget(date, minutes, mgdl, o.optString("reason").ifBlank { null })
            }
            return out
        }

        /** Toleriert eingefügte URLs mit Pfad, Query oder Token-Parameter (z. B. aus dem Browser). */
        fun normalizeUrl(input: String): String {
            var url = input.trim()
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            url = url.substringBefore('?').substringBefore('#').trimEnd('/')
            for (suffix in listOf("/api/v3", "/api/v1", "/api")) {
                if (url.endsWith(suffix)) url = url.removeSuffix(suffix)
            }
            return url
        }
    }
}
