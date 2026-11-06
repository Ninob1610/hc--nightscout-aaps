package de.nbeck.aapshc

import java.util.Locale
import kotlin.math.abs

/** Anzeigeeinheit. Intern rechnet die App immer in mg/dl wie Nightscout. */
enum class GlucoseUnit(val label: String) {
    MGDL("mg/dl"),
    MMOL("mmol/l");

    fun value(mgdl: Int): String =
        if (this == MGDL) "$mgdl" else String.format(Locale.GERMANY, "%.1f", mgdl / MGDL_PER_MMOL)

    fun withUnit(mgdl: Int) = "${value(mgdl)} $label"

    /** Differenz mit Vorzeichen, z. B. „+28“ oder „−1,6“. */
    fun delta(mgdl: Int): String = (if (mgdl > 0) "+" else if (mgdl < 0) "−" else "") + value(abs(mgdl))

    val low: String get() = value(70)
    val high: String get() = value(180)
    val range: String get() = "$low–$high"

    companion object {
        const val MGDL_PER_MMOL = 18.0182
    }
}
