package de.nbeck.aapshc.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.nbeck.aapshc.R
import de.nbeck.aapshc.Settings
import java.text.DateFormat
import java.util.Date

/** Startbildschirm-Widget: letzte Nacht und letzter Sync. Zeigt nur gespeicherte Werte an. */
class NightWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NightWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val s = Settings(context)
            val night = s.lastNight
            val v = RemoteViews(context.packageName, R.layout.widget_night)
            if (night == null) {
                v.setTextViewText(R.id.widget_tir, "–")
                v.setTextViewText(R.id.widget_details, "Noch keine Nacht ausgewertet")
            } else {
                v.setTextViewText(R.id.widget_title, "Letzte Nacht · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(night.start)))
                v.setTextViewText(R.id.widget_tir, night.timeInRange?.let { "$it %" } ?: "–")
                val hypo = when {
                    night.hypoEvents == 0 -> "keine Hypo"
                    night.compressionEvents == night.hypoEvents -> "${night.hypoEvents} Tief (Kompr.-Verdacht)"
                    else -> "${night.hypoEvents} Hypo" + if (night.compressionEvents > 0) " (${night.compressionEvents} Kompr.)" else ""
                }
                v.setTextViewText(R.id.widget_details, "$hypo · ${night.sleepMinutes / 60} h ${night.sleepMinutes % 60} min Schlaf")
            }
            val sync = when {
                s.lastSyncAt == 0L -> "Noch nicht synchronisiert"
                s.lastSyncMessage.startsWith("Fehler") -> "Sync-Fehler · " + time(s.lastSyncAt)
                else -> "Sync " + time(s.lastSyncAt)
            }
            v.setTextViewText(R.id.widget_sync, sync)
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        private fun time(at: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    }
}
