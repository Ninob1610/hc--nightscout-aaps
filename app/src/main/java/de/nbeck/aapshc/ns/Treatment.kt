package de.nbeck.aapshc.ns

import org.json.JSONObject
import java.time.Instant

/**
 * Ein Nightscout-Treatment, so wie AAPS 4 (NSClient v3) es als Therapie-Ereignis einliest.
 *
 * AAPS erkennt „Exercise“ und „Note“ und übernimmt sie, wenn in AAPS unter
 * NSClient → Synchronisierung „Therapie-Ereignisse übernehmen“ aktiv ist.
 */
data class Treatment(
    /** Stabile ID, aus der Health-Connect-Record-ID abgeleitet; macht Uploads idempotent. */
    val identifier: String,
    val eventType: String,
    val date: Long,
    val utcOffsetMinutes: Int,
    val durationMinutes: Long,
    val notes: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("identifier", identifier)
        put("eventType", eventType)
        put("date", date)
        put("created_at", Instant.ofEpochMilli(date).toString())
        put("utcOffset", utcOffsetMinutes)
        put("duration", durationMinutes)
        // AAPS liest zuerst durationInMilliseconds, Nightscout-Careportal nutzt duration (Minuten).
        put("durationInMilliseconds", durationMinutes * 60_000L)
        put("notes", notes)
        put("enteredBy", ENTERED_BY)
        put("app", APP)
        put("device", APP)
        put("isValid", true)
    }

    /** Fingerabdruck des Inhalts, um unveränderte Einträge nicht erneut hochzuladen. */
    fun contentHash(): String = toJson().toString().hashCode().toString(16)

    companion object {
        const val EXERCISE = "Exercise"
        const val NOTE = "Note"
        const val ENTERED_BY = "Health Connect"
        const val APP = "aaps-health-connect"
    }
}
