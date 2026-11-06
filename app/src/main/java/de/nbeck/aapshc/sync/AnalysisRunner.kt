package de.nbeck.aapshc.sync

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.hc.ExerciseTypes
import de.nbeck.aapshc.hc.HealthConnectSource
import de.nbeck.aapshc.ns.NightscoutAuthException
import de.nbeck.aapshc.ns.NightscoutClient
import de.nbeck.aapshc.ns.Permissions
import java.time.Duration
import java.time.Instant

data class AnalysisReport(
    val items: List<ExerciseGlucose>,
    val summaries: List<TypeSummary>,
    val nights: List<NightGlucose> = emptyList(),
    val nightSummaries: List<NightSummary> = emptyList(),
    val days: Long = 30,
    val nightProfile: List<NightAnalysis.ProfilePoint> = NightAnalysis.profile(nights),
    val timeOfDay: List<Analysis.TimeOfDaySummary> = Analysis.byTimeOfDay(items),
    val deepSleep: List<NightSummary> = NightAnalysis.byDeepSleep(nights),
    val factors: NightFactors.Comparison? = NightFactors.compare(nights),
    val hypoHours: List<NightFactors.HourCount> = NightFactors.hypoStartHours(nights),
    /** Ob Bolus/KH/Ziele gelesen werden durften (sonst fehlt der Abend-Kontext). */
    val therapyKnown: Boolean = false,
)

/**
 * Verknüpft Trainings aus Health Connect mit den CGM-Werten aus Nightscout.
 * Läuft nur lokal zur Anzeige; es wird dabei nichts hochgeladen.
 */
class AnalysisRunner(context: Context) {
    private val settings = Settings(context.applicationContext)
    private val source = HealthConnectSource(context.applicationContext)

    suspend fun run(days: Long = 30): AnalysisReport {
        val now = Instant.now()
        val ns = NightscoutClient(settings.nsUrl, settings.nsToken)
        if (!ns.canReadEntries()) {
            throw NightscoutAuthException(
                "Für die Auswertung braucht das Token zusätzlich ${Permissions.ENTRIES_READ} " +
                    "(in der Rolle als zweite Berechtigung, durch Leerzeichen getrennt)."
            )
        }
        val sessions = relevantExercises(now.minus(Duration.ofDays(days)), now)
        val items = sessions.map { s ->
            val values = ns.glucose(
                s.startTime.minus(Duration.ofMinutes(20)).toEpochMilli(),
                s.endTime.plus(Analysis.AFTER).toEpochMilli(),
            )
            Analysis.evaluate(ExerciseTypes.name(s.exerciseType), s.startTime, s.endTime, values)
        }.sortedByDescending { it.start }
        val therapyKnown = ns.canReadTreatments()
        val nights = nights(ns, sessions, now.minus(Duration.ofDays(days)), now, therapyKnown)
        BedtimeRisk.typicalBedtime(nights)?.let { settings.typicalBedtimeMinutes = it }
        return AnalysisReport(
            items, Analysis.summarize(items), nights, NightAnalysis.summarize(nights), days, therapyKnown = therapyKnown,
        )
    }

    /** Jede Schlafsitzung mit den CGM-Werten der Nacht und dem Training der 12 h davor. */
    private suspend fun nights(
        ns: NightscoutClient,
        exercises: List<ExerciseSessionRecord>,
        from: Instant,
        to: Instant,
        therapyKnown: Boolean,
    ): List<NightGlucose> {
        if (HealthConnectSource.read(SleepSessionRecord::class) !in source.grantedPermissions()) return emptyList()
        val sleeps = source.readSleep(from, to)
            .filter { it.sourcePackage !in settings.excludedSources }
            .filter { Duration.between(it.start, it.end).toHours() >= 3 } // Nickerchen auslassen
        val distinct = if (!settings.mergeDuplicates) sleeps
        else Dedup.keepDistinct(sleeps.map { Dedup.Session(it.start, it.end, it.sourcePackage, it) })
        return distinct.map { s ->
            // Etwas Rand um die Nacht, damit Abfall und Wiederanstieg einer Hypo am Rand erkennbar sind.
            val values = ns.glucose(
                s.start.minus(Duration.ofMinutes(30)).toEpochMilli(), s.end.plus(Duration.ofMinutes(30)).toEpochMilli(),
            )
            val therapy = if (!therapyKnown) null else ns.therapy(
                s.start.minus(Duration.ofHours(EveningContext.WINDOW_HOURS)).toEpochMilli(), s.end.toEpochMilli(),
            )
            val windowStart = s.start.minus(Duration.ofHours(12))
            val sport = exercises
                .filter { it.endTime.isAfter(windowStart) && !it.startTime.isAfter(s.start) }
                .sumOf { Duration.between(maxOf(it.startTime, windowStart), minOf(it.endTime, s.start)).toMinutes() }
            NightAnalysis.evaluate(
                s.start, s.end, Duration.between(s.start, s.end).minus(s.awake).toMinutes(), values, sport,
                deepMinutes = s.deep.toMinutes(),
                therapy = therapy,
            )
        }.sortedByDescending { it.start }
    }

    /** Kontext des heutigen Abends für den Hinweis vor dem Schlafengehen. */
    suspend fun tonight(now: Instant): TonightContext {
        val (_, minutes) = exerciseMinutes(now.minus(Duration.ofHours(12)), now)
        val ns = NightscoutClient(settings.nsUrl, settings.nsToken)
        val lateBolus = ns.canReadTreatments() && ns.therapy(
            now.minus(Duration.ofMinutes(EveningContext.LATE_BOLUS_MINUTES)).toEpochMilli(), now.toEpochMilli(),
        ).any { it is de.nbeck.aapshc.ns.TherapyItem.Bolus && !it.smb }
        return TonightContext(minutes, lateBolus)
    }

    /** Nur Trainingsminuten aus Health Connect – für den Wochenbericht ohne Leserecht auf CGM-Werte. */
    suspend fun exerciseMinutes(from: Instant, to: Instant): Pair<Int, Long> {
        val sessions = relevantExercises(from, to)
        return sessions.size to sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
    }

    /** Bisherige Trainings derselben Art, für den Hypo-Hinweis („nach 3 von 5 …“). */
    suspend fun history(typeName: String, before: Instant, days: Long = 60): List<ExerciseGlucose> {
        val ns = NightscoutClient(settings.nsUrl, settings.nsToken)
        if (!ns.canReadEntries()) return emptyList()
        return relevantExercises(before.minus(Duration.ofDays(days)), before)
            .filter { ExerciseTypes.name(it.exerciseType) == typeName && it.endTime.plus(Analysis.AFTER).isBefore(before) }
            .takeLast(10)
            .map { s ->
                val values = ns.glucose(s.startTime.toEpochMilli(), s.endTime.plus(Analysis.AFTER).toEpochMilli())
                Analysis.evaluate(typeName, s.startTime, s.endTime, values)
            }
    }

    /** Dieselben Filter wie beim Sync: Mindestdauer, ausgeschlossene Apps, Duplikate. */
    private suspend fun relevantExercises(from: Instant, to: Instant): List<ExerciseSessionRecord> {
        val candidates = source.readExercises(from, to)
            .filter { it.metadata.dataOrigin.packageName !in settings.excludedSources }
            .filter { Duration.between(it.startTime, it.endTime).toMinutes() >= settings.minExerciseMinutes }
        return if (!settings.mergeDuplicates) candidates.sortedBy { it.startTime }
        else Dedup.keepDistinct(candidates.map { Dedup.Session(it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it) })
    }
}
