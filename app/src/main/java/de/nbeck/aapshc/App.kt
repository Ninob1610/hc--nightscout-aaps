package de.nbeck.aapshc

import android.app.Application
import de.nbeck.aapshc.sync.BedtimeWorker
import de.nbeck.aapshc.sync.Notifier
import de.nbeck.aapshc.sync.SyncWorker
import de.nbeck.aapshc.sync.WeeklyReportWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        SyncWorker.schedule(this)
        WeeklyReportWorker.schedule(this)
        BedtimeWorker.schedule(this)
    }
}
