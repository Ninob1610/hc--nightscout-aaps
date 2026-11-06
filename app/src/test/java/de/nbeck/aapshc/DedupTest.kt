package de.nbeck.aapshc

import de.nbeck.aapshc.sync.Dedup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DedupTest {
    private val t0 = Instant.parse("2026-11-05T16:00:00Z")
    private fun s(id: String, startMin: Long, endMin: Long, src: String) =
        Dedup.Session(t0.plusSeconds(startMin * 60), t0.plusSeconds(endMin * 60), src, id)

    @Test
    fun sameWorkoutFromTwoAppsIsMerged() {
        val kept = Dedup.keepDistinct(listOf(s("garmin", 0, 45, "garmin"), s("samsung", 1, 44, "samsung")))
        assertEquals(listOf("garmin"), kept)
    }

    @Test
    fun separateWorkoutsStay() {
        val kept = Dedup.keepDistinct(listOf(s("a", 0, 30, "garmin"), s("b", 40, 70, "samsung")))
        assertEquals(listOf("a", "b"), kept)
    }

    @Test
    fun sameAppOverlapIsKept() {
        // Zwei Einheiten derselben App (z. B. Aufwärmen + Lauf) sind bewusst getrennt erfasst.
        val kept = Dedup.keepDistinct(listOf(s("a", 0, 30, "garmin"), s("b", 10, 40, "garmin")))
        assertEquals(listOf("a", "b"), kept)
    }

    @Test
    fun smallOverlapIsNoDuplicate() {
        val kept = Dedup.keepDistinct(listOf(s("a", 0, 60, "garmin"), s("b", 50, 80, "samsung")))
        assertEquals(listOf("a", "b"), kept)
    }
}
