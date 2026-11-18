package cn.onetap.checkin.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalThemePalette = staticCompositionLocalOf { ThemePalette.resolve(ThemeMode.SYSTEM, false) }
/** 当前是否为深色，供渐变卡片等自绘组件取色。 */
internal val LocalDarkTheme = staticCompositionLocalOf { false }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
    )
}

@Composable
fun OneTapTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = ThemePalette.resolve(mode, systemDark)
    val dark = mode.isDark(systemDark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(colors.primary), onPrimary = Color(0xFF1A2380),
            primaryContainer = Color(0xFF2E3A8C), onPrimaryContainer = Color(0xFFDDE1FF),
            secondary = Color(0xFFC4C3E8), onSecondary = Color(0xFF2D2D4A),
            secondaryContainer = Color(0xFF2B2E40), onSecondaryContainer = Color(0xFFE0E1F5),
            tertiary = Color(0xFFE7B6FF), tertiaryContainer = Color(0xFF4B2C66),
            background = Color(colors.background), onBackground = Color(colors.onSurface),
            surface = Color(colors.surface), onSurface = Color(colors.onSurface),
            surfaceVariant = Color(0xFF232733), onSurfaceVariant = Color(colors.onSurfaceVariant),
            surfaceContainerLowest = Color(0xFF0B0D12), surfaceContainerLow = Color(0xFF151821),
            surfaceContainer = Color(0xFF1B1F2A), surfaceContainerHigh = Color(0xFF222633),
            surfaceContainerHighest = Color(0xFF2A2F3D),
            outline = Color(0xFF4A5063), outlineVariant = Color(0xFF2E3342),
            error = Color(colors.error), errorContainer = Color(0xFF5C1E1A), onErrorContainer = Color(0xFFFFDAD5)
        )
    } else {
        lightColorScheme(
            primary = Color(colors.primary), onPrimary = Color.White,
            primaryContainer = Color(0xFFE0E4FF), onPrimaryContainer = Color(0xFF15206B),
            secondary = Color(0xFF5B5D7A), onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6E7F5), onSecondaryContainer = Color(0xFF1B1D33),
            tertiary = Color(0xFF8A4FD8), tertiaryContainer = Color(0xFFF1E3FF),
            background = Color(colors.background), onBackground = Color(colors.onSurface),
            surface = Color(colors.surface), onSurface = Color(colors.onSurface),
            surfaceVariant = Color(0xFFE9ECF4), onSurfaceVariant = Color(colors.onSurfaceVariant),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8FC),
            surfaceContainer = Color(0xFFEFF1F7), surfaceContainerHigh = Color(0xFFE9ECF3),
            surfaceContainerHighest = Color(0xFFE3E6EE),
            outline = Color(0xFFB7BCCB), outlineVariant = Color(0xFFDDE0EA),
            error = Color(colors.error), errorContainer = Color(0xFFFFE3E0), onErrorContainer = Color(0xFF5F1410)
        )
    }
    CompositionLocalProvider(LocalThemePalette provides colors, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
