package de.nbeck.aapshc.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import de.nbeck.aapshc.Settings
import de.nbeck.aapshc.sync.SyncWorker
import java.text.DateFormat
import java.util.Date

/** Schnelleinstellungs-Kachel „HC-Sync“: startet einen Sync sofort. */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val settings = Settings(this)
        qsTile?.apply {
            state = if (settings.isNightscoutConfigured) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (settings.lastSyncAt > 0) {
                    (if (settings.lastSyncMessage.startsWith("Fehler")) "Fehler " else "") +
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(settings.lastSyncAt))
                } else null
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        SyncWorker.runNow(this)
        qsTile?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "läuft …"
            updateTile()
        }
    }
}
