package de.nbeck.aapshc.sync

import android.content.Context
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.ns.NightscoutAuthException
import java.time.Duration
import java.time.Instant

/** Was nach einem Sync-Lauf passiert: Hypo-Hinweise und Fehlerbenachrichtigung. */
object PostSync {
    /** Nur frische Trainings melden, nicht alles, was beim ersten Lauf nachgeholt wird. */
    private val FRESH = Duration.ofHours(6)
    private const val FAILURES_BEFORE_NOTICE = 3

    suspend fun onSuccess(context: Context, result: SyncResult) {
        val settings = Settings(context)
        settings.consecutiveFailures = 0
        Notifier.clearSyncFailed(context)
        if (!settings.notifyHypoRisk) return
        val now = Instant.now()
        result.newExercises
            .filter { it.end.isAfter(now.minus(FRESH)) }
            .filter { Duration.between(it.start, it.end).toMinutes() >= settings.hypoRiskMinutes }
            .forEach { exercise ->
                val history = runCatching { AnalysisRunner(context).history(exercise.typeName, exercise.start) }
                    .getOrDefault(emptyList())
                Notifier.hypoRisk(context, exercise, history)
            }
    }

    /** Nur aus dem Hintergrund aufrufen; im Vordergrund sieht man den Fehler ohnehin. */
    fun onBackgroundFailure(context: Context, error: Throwable) {
        val settings = Settings(context)
        val failures = settings.consecutiveFailures + 1
        settings.consecutiveFailures = failures
        if (!settings.notifyErrors) return
        // Token-/Rechteprobleme sofort melden, Netzwerkaussetzer erst, wenn sie sich häufen.
        val notify = if (error is NightscoutAuthException || error is SyncException) failures == 1
        else failures == FAILURES_BEFORE_NOTICE
        if (notify) Notifier.syncFailed(context, error.message ?: error.javaClass.simpleName)
    }
}
