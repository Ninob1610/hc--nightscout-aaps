package de.nbeck.aapshc.sync

import de.nbeck.aapshc.Settings

/** Überträgt eine ausgewertete Nacht in die Kurzfassung für das Widget. */
object WidgetData {
    fun store(settings: Settings, n: NightGlucose) {
        val known = settings.lastNight
        if (known != null && known.end > n.end.toEpochMilli()) return // nie mit einer älteren Nacht überschreiben
        settings.lastNight = Settings.NightSnapshot(
            start = n.start.toEpochMilli(),
            end = n.end.toEpochMilli(),
            timeInRange = n.timeInRange.takeIf { n.values >= 12 },
            hypoEvents = n.hypoEvents,
            compressionEvents = n.compressionEvents,
            sleepMinutes = n.sleepMinutes,
        )
    }
}
