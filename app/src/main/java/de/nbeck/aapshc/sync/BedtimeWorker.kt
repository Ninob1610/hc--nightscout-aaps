package de.nbeck.aapshc.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.nbeck.aapshc.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Läuft etwa 30 min vor der üblichen Einschlafzeit. Meldet sich nur, wenn der heutige Abend
 * Merkmale hat, die früher auffällig oft zu einer echten Hypo-Nacht geführt haben.
 * Nur ein Hinweis – die App setzt nie selbst ein Ziel.
 */
class BedtimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        try {
            if (settings.bedtimeHint && settings.isNightscoutConfigured) {
                val runner = AnalysisRunner(applicationContext)
                val now = Instant.now()
                val history = runner.run(days = 45).nights
                BedtimeRisk.message(runner.tonight(now), history)?.let { Notifier.bedtime(applicationContext, it) }
            }
        } catch (_: Exception) {
            // Ohne Daten oder Rechte gibt es heute keinen Hinweis; morgen erneut.
        } finally {
            schedule(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "bedtime-hint"
        private val LEAD = Duration.ofMinutes(30)

        fun schedule(context: Context) {
            val settings = Settings(context)
            val wm = WorkManager.getInstance(context)
            if (!settings.bedtimeHint) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val now = ZonedDateTime.now()
            val bedtime = LocalTime.of(settings.typicalBedtimeMinutes / 60, settings.typicalBedtimeMinutes % 60)
            var next = now.with(bedtime).minus(LEAD)
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<BedtimeWorker>()
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
