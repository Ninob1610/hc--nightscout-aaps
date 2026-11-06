package de.nbeck.aapshc.sync

import org.json.JSONObject
import java.io.File

/** Was bereits nach Nightscout geschrieben wurde – Grundlage für Änderungs- und Löscherkennung. */
class SyncState(private val file: File) {

    data class Entry(
        val category: String,
        val hash: String,
        val date: Long,
        /** lastModifiedTime des Health-Connect-Records (0 bei berechneten Einträgen wie Schritten). */
        val modified: Long,
        val uploadedAt: Long,
    )

    val entries: MutableMap<String, Entry> = load()

    private fun load(): MutableMap<String, Entry> {
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWithTo(mutableMapOf()) { id ->
                val o = json.getJSONObject(id)
                Entry(
                    category = o.getString("c"),
                    hash = o.getString("h"),
                    date = o.getLong("d"),
                    modified = o.optLong("m"),
                    uploadedAt = o.optLong("u"),
                )
            }
        }.getOrElse { mutableMapOf() }
    }

    fun save() {
        val json = JSONObject()
        entries.forEach { (id, e) ->
            json.put(id, JSONObject().put("c", e.category).put("h", e.hash).put("d", e.date).put("m", e.modified).put("u", e.uploadedAt))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    fun clear() {
        entries.clear()
        file.delete()
    }

    companion object {
        const val EXERCISE = "exercise"
        const val SLEEP = "sleep"
        const val STEPS = "steps"
        const val TEMPERATURE = "temperature"
        const val PERIOD = "period"
        const val VITALS = "vitals"
    }
}
