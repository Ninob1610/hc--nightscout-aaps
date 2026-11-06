package de.nbeck.aapshc.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.ns.NightscoutAuthException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = SyncEngine(applicationContext).run(background = true)
        PostSync.onSuccess(applicationContext, result)
        Result.success()
    } catch (e: NightscoutAuthException) {
        // Token/Rechte falsch: Wiederholen ändert nichts, Hinweis steht im Status.
        PostSync.onBackgroundFailure(applicationContext, e)
        Result.failure()
    } catch (e: IOException) {
        // Netzwerk- oder Serverfehler: später erneut versuchen.
        PostSync.onBackgroundFailure(applicationContext, e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (e: Exception) {
        // Konfigurations- oder Berechtigungsproblem: steht im Status, nächster Lauf regulär.
        PostSync.onBackgroundFailure(applicationContext, e)
        Result.failure()
    }

    companion object {
        private const val NAME = "hc-nightscout-sync"
        private const val NAME_NOW = "hc-nightscout-sync-now"

        /** Einmaliger Lauf sofort (Schnelleinstellungs-Kachel), unabhängig von WLAN/Lade-Optionen. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Plant den Hintergrund-Sync gemäß Einstellungen neu (oder storniert ihn). */
        fun schedule(context: Context) {
            val settings = Settings(context)
            val wm = WorkManager.getInstance(context)
            if (!settings.autoSync || !settings.isNightscoutConfigured) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
