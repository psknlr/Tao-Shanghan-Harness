package org.impfai.hermes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 墨綠 + 簡帛黃：古籍質感的 M3 色板
private val InkGreen = Color(0xFF2E5E4E)
private val InkGreenDark = Color(0xFF9BD0BB)
private val BambooGold = Color(0xFFC9A227)
private val PaperBg = Color(0xFFFCF9F2)
private val PaperSurface = Color(0xFFF5EFE0)
private val NightBg = Color(0xFF121412)
private val NightSurface = Color(0xFF1C201D)

private val LightColors = lightColorScheme(
    primary = InkGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8DB),
    onPrimaryContainer = Color(0xFF0A2A1F),
    secondary = BambooGold,
    onSecondary = Color(0xFF3A2E00),
    secondaryContainer = Color(0xFFF4E7C3),
    onSecondaryContainer = Color(0xFF3A2E00),
    background = PaperBg,
    onBackground = Color(0xFF1B1C1A),
    surface = PaperBg,
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = PaperSurface,
    onSurfaceVariant = Color(0xFF474B45),
)

private val DarkColors = darkColorScheme(
    primary = InkGreenDark,
    onPrimary = Color(0xFF10352A),
    primaryContainer = Color(0xFF224A3C),
    onPrimaryContainer = Color(0xFFCDE8DB),
    secondary = Color(0xFFE0C566),
    onSecondary = Color(0xFF2E2400),
    background = NightBg,
    onBackground = Color(0xFFE2E4DE),
    surface = NightSurface,
    onSurface = Color(0xFFE2E4DE),
)

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
