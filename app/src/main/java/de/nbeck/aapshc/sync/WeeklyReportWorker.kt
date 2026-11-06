package de.nbeck.aapshc.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.ns.NightscoutAuthException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** Sonntagabends ein Rückblick auf die Woche als Benachrichtigung – nur Anzeige, nichts wird hochgeladen. */
class WeeklyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.weeklyReport || !settings.isNightscoutConfigured) return Result.success()
        val now = Instant.now()
        val text = runCatching { reportText(now) }.getOrElse { return Result.retry() }
        Notifier.weeklyReport(applicationContext, text)
        return Result.success()
    }

    private suspend fun reportText(now: Instant): String {
        val runner = AnalysisRunner(applicationContext)
        val weekStart = now.minus(Duration.ofDays(7))
        return try {
            val report = runner.run(days = 14)
            fun week(from: Instant, to: Instant) = NightAnalysis.week(
                report.nights.filter { it.start in from..to },
                report.items.filter { it.start in from..to },
            )
            weeklyText(week(weekStart, now), week(weekStart.minus(Duration.ofDays(7)), weekStart))
        } catch (_: NightscoutAuthException) {
            // Ohne Leserecht auf CGM-Werte nur der Sport-Teil.
            val (sessions, minutes) = runner.exerciseMinutes(weekStart, now)
            "Sport: ${hm(minutes)} in $sessions Einheiten. Für die Glukose-Auswertung braucht das Token api:entries:read."
        }
    }

    companion object {
        private const val NAME = "weekly-report"

        fun weeklyText(week: NightAnalysis.WeekSummary, previous: NightAnalysis.WeekSummary?): String = buildList {
            week.avgTimeInRange?.let { tir ->
                val trend = previous?.avgTimeInRange?.let { " (Vorwoche $it %)" }.orEmpty()
                add("Nächte im Ziel Ø $tir %$trend")
            }
            if (week.nights > 0) add(if (week.hypoNights == 0) "keine Hypo-Nacht" else "${week.hypoNights} Hypo-Nächte")
            add("Sport ${hm(week.exerciseMinutes)} in ${week.exerciseSessions} Einheiten")
        }.joinToString(" · ")

        private fun hm(minutes: Long) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

        /** Erster Lauf am nächsten Sonntag um 19 Uhr, danach wöchentlich. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!Settings(context).weeklyReport) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val now = ZonedDateTime.now()
            var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).withHour(19).withMinute(0).withSecond(0)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            // KEEP: Ein erneutes Planen beim App-Start soll den Sonntagstermin nicht verschieben.
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
