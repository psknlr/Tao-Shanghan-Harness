package org.impfai.hermes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    tertiary = Color(0xFF7A4E7E),           // 注家紫（C 層呼應）
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DCF1),
    onTertiaryContainer = Color(0xFF31102F),
    background = PaperBg,
    onBackground = Color(0xFF1B1C1A),
    surface = PaperBg,
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = PaperSurface,
    onSurfaceVariant = Color(0xFF474B45),
    outline = Color(0xFF787D75),
    outlineVariant = Color(0xFFD8DCD2),
)

private val DarkColors = darkColorScheme(
    primary = InkGreenDark,
    onPrimary = Color(0xFF10352A),
    primaryContainer = Color(0xFF224A3C),
    onPrimaryContainer = Color(0xFFCDE8DB),
    secondary = Color(0xFFE0C566),
    onSecondary = Color(0xFF2E2400),
    secondaryContainer = Color(0xFF4A3B10),
    onSecondaryContainer = Color(0xFFF4E7C3),
    tertiary = Color(0xFFE2B8E0),
    onTertiary = Color(0xFF48244A),
    background = NightBg,
    onBackground = Color(0xFFE2E4DE),
    surface = NightSurface,
    onSurface = Color(0xFFE2E4DE),
    surfaceVariant = Color(0xFF262B26),
    onSurfaceVariant = Color(0xFFC3C8BF),
    outline = Color(0xFF8B9186),
    outlineVariant = Color(0xFF3C423B),
)

// 更柔和的圓角體系：卡片 16dp、小組件 10dp、大面板 24dp
private val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// 古籍正文用襯線字族；標題加重
private val HermesTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 28.sp),
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
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = HermesShapes,
        typography = HermesTypography,
        content = content,
    )
}
