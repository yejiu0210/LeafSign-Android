package cn.onetap.checkin.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalThemePalette = staticCompositionLocalOf { ThemePalette.resolve(ThemeMode.SYSTEM, false) }

@Composable
fun OneTapTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = ThemePalette.resolve(mode, systemDark)
    val scheme = if (mode.isDark(systemDark)) {
        darkColorScheme(
            primary = Color(colors.primary), onPrimary = Color(0xFF00382F),
            primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFF97F5E2),
            secondary = Color(0xFFA7CEC8), onSecondary = Color(0xFF103731),
            background = Color(colors.background), onBackground = Color(colors.onSurface),
            surface = Color(colors.surface), onSurface = Color(colors.onSurface),
            onSurfaceVariant = Color(colors.onSurfaceVariant),
            error = Color(colors.error)
        )
    } else {
        lightColorScheme(
            primary = Color(colors.primary), secondary = Color(0xFF426563),
            background = Color(colors.background), onBackground = Color(colors.onSurface),
            surface = Color(colors.surface), onSurface = Color(colors.onSurface),
            onSurfaceVariant = Color(colors.onSurfaceVariant),
            error = Color(colors.error)
        )
    }
    CompositionLocalProvider(LocalThemePalette provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
