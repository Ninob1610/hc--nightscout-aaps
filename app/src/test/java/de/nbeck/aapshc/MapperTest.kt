package de.nbeck.aapshc

import de.nbeck.aapshc.ns.Treatment
import de.nbeck.aapshc.sync.ExerciseInfo
import de.nbeck.aapshc.sync.Mapper
import de.nbeck.aapshc.sync.SleepInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class MapperTest {
    private val start = Instant.parse("2026-11-05T16:00:00Z")

    private fun run(id: String = "abc") = ExerciseInfo(
        recordId = id, start = start, end = start.plus(Duration.ofMinutes(45)),
        offset = ZoneOffset.ofHours(1), typeName = "Laufen", title = null, source = "Garmin Connect",
        distanceKm = 7.34, kcal = 512.0, avgHeartRate = 148, maxHeartRate = 171, steps = 6789,
    )

    @Test
    fun exerciseMatchesAapsFormat() {
        val t = Mapper.exercise(run())
        assertEquals(Treatment.EXERCISE, t.eventType)
        assertEquals(start.toEpochMilli(), t.date)
        assertEquals(45L, t.durationMinutes)
        assertEquals(60, t.utcOffsetMinutes)
        assertEquals("Laufen · 7,3 km · 512 kcal · Ø 148 / max 171 bpm · 6.789 Schritte (Garmin Connect)", t.notes)

        val json = t.toJson()
        assertEquals("Exercise", json.getString("eventType"))
        assertEquals(45L * 60_000, json.getLong("durationInMilliseconds"))
        assertEquals("aaps-health-connect", json.getString("app"))
        assertEquals("2026-11-05T16:00:00Z", json.getString("created_at"))
    }

    @Test
    fun identifierIsStablePerRecord() {
        assertEquals(Mapper.exercise(run("x")).identifier, Mapper.exercise(run("x")).identifier)
        assertNotEquals(Mapper.exercise(run("x")).identifier, Mapper.exercise(run("y")).identifier)
        assertNotEquals(Mapper.identifierFor("exercise:x"), Mapper.identifierFor("sleep:x"))
    }

    @Test
    fun sleepSubtractsAwakeTime() {
        val t = Mapper.sleep(
            SleepInfo(
                recordId = "s", start = start, end = start.plus(Duration.ofMinutes(480)),
                offset = ZoneOffset.UTC, source = "Samsung Health", sourcePackage = "com.sec.android.app.shealth",
                deep = Duration.ofMinutes(90), rem = Duration.ofMinutes(100), awake = Duration.ofMinutes(20),
            )
        )
        assertEquals(Treatment.NOTE, t.eventType)
        assertEquals(480L, t.durationMinutes)
        assertEquals("Schlaf 7 h 40 min (Tief 1 h 30 min, REM 1 h 40 min, wach 20 min) (Samsung Health)", t.notes)
    }

    @Test
    fun hashChangesWithContent() {
        val a = Mapper.exercise(run())
        val b = Mapper.exercise(run().copy(avgHeartRate = 150))
        assertEquals(a.contentHash(), Mapper.exercise(run()).contentHash())
        assertNotEquals(a.contentHash(), b.contentHash())
    }
}
