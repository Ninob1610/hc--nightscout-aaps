package de.nbeck.aapshc.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.nbeck.aapshc.R
import de.nbeck.aapshc.ui.MainActivity
import java.time.Duration

/** Lokale Benachrichtigungen. Nur Hinweise – die App verändert nie etwas an der AAPS-Therapie. */
object Notifier {
    private const val CHANNEL_HYPO = "hypo_risk"
    private const val CHANNEL_SYNC = "sync_errors"
    private const val CHANNEL_WEEKLY = "weekly_report"
    private const val ID_SYNC_ERROR = 1
    private const val ID_WEEKLY = 2
    private const val ID_BEDTIME = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HYPO, "Hypo-Hinweise nach Sport", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Erinnerung nach langem oder intensivem Training" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sync-Fehler", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WEEKLY, "Wochenbericht", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Text des Hypo-Hinweises; [history] sind frühere Trainings derselben Art (kann leer sein). */
    fun hypoRiskText(exercise: ExerciseInfo, history: List<ExerciseGlucose>): String {
        val minutes = Duration.between(exercise.start, exercise.end).toMinutes()
        val base = "${exercise.typeName}, $minutes min. Die Insulinempfindlichkeit kann bis in die Nacht erhöht sein."
        val withHypo = history.count { it.hypoAfter }
        val stats = if (history.size >= 3) {
            " Nach $withHypo von ${history.size} ähnlichen Trainings lag die Glukose in den 12 h danach unter ${Analysis.HYPO_MGDL} mg/dl."
        } else ""
        return base + stats + " Denk z. B. an ein höheres temporäres Ziel in AAPS."
    }

    fun hypoRisk(context: Context, exercise: ExerciseInfo, history: List<ExerciseGlucose>) {
        val text = hypoRiskText(exercise, history)
        notify(
            context, exercise.recordId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_HYPO)
                .setContentTitle("Nach dem Training: auf Hypos achten")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        )
    }

    fun bedtime(context: Context, text: String) = notify(
        context, ID_BEDTIME,
        NotificationCompat.Builder(context, CHANNEL_HYPO)
            .setContentTitle("Vor dem Schlafen: erhöhtes Hypo-Risiko")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
    )

    fun weeklyReport(context: Context, text: String) = notify(
        context, ID_WEEKLY,
        NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setContentTitle("Deine Woche")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
    )

    fun syncFailed(context: Context, message: String) = notify(
        context, ID_SYNC_ERROR,
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setContentTitle("Health-Connect-Sync fehlgeschlagen")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
    )

    fun clearSyncFailed(context: Context) = NotificationManagerCompat.from(context).cancel(ID_SYNC_ERROR)

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            id, builder.setSmallIcon(R.drawable.ic_notification).setContentIntent(open).setAutoCancel(true).build()
        )
    }
}
