package de.nbeck.aapshc.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Farben der Glukose-Diagramme. Geprüft mit dem Palette-Validator (auch bei Rot-Grün-Schwäche
 * unterscheidbar); hell und dunkel sind eigene Stufen, keine automatische Umkehrung.
 * Gelb liegt auf hellem Grund unter 3:1 – deshalb tragen alle Diagramme Legende und Zahlen als Text.
 */
@Immutable
data class ChartColors(
    val below: Color,
    val inRange: Color,
    val above: Color,
    val series: Color,
    val targetBand: Color,
    val grid: Color,
)

private val LightChart = ChartColors(
    below = Color(0xFFD03B3B),
    inRange = Color(0xFF2A78D6),
    above = Color(0xFFEDA100),
    series = Color(0xFF2A78D6),
    targetBand = Color(0xFFF0EFEC),
    grid = Color(0xFFE2E1DC),
)

private val DarkChart = ChartColors(
    below = Color(0xFFD03B3B),
    inRange = Color(0xFF3987E5),
    above = Color(0xFFC98500),
    series = Color(0xFF3987E5),
    targetBand = Color(0xFF383835),
    grid = Color(0xFF34342F),
)

val LocalChartColors = staticCompositionLocalOf { LightChart }

private val BrandLight = lightColorScheme(
    primary = Color(0xFF1C5CAB),
    secondary = Color(0xFF52606F),
    tertiary = Color(0xFF00696B),
)
private val BrandDark = darkColorScheme(
    primary = Color(0xFF9EC5F4),
    secondary = Color(0xFFBAC8DA),
    tertiary = Color(0xFF4CDADB),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    CompositionLocalProvider(LocalChartColors provides if (dark) DarkChart else LightChart) {
        MaterialTheme(
            colorScheme = colors,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
