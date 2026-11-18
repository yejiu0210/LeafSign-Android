package cn.onetap.checkin.theme

enum class ThemeMode(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Shared color values for Compose screens and the native official-login Activity. */
data class ThemePalette(
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val success: Int,
    val warning: Int,
    val error: Int
) {
    companion object {
        fun resolve(mode: ThemeMode, systemDark: Boolean): ThemePalette = if (mode.isDark(systemDark)) {
            ThemePalette(
                background = 0xFF0F1117.toInt(), surface = 0xFF181B24.toInt(),
                onSurface = 0xFFE6E8F0.toInt(), onSurfaceVariant = 0xFFA3A8B8.toInt(),
                primary = 0xFFAAB4FF.toInt(), success = 0xFF5BD69A.toInt(),
                warning = 0xFFF3C164.toInt(), error = 0xFFFF8A80.toInt()
            )
        } else {
            ThemePalette(
                background = 0xFFF3F5FA.toInt(), surface = 0xFFFFFFFF.toInt(),
                onSurface = 0xFF1A1C24.toInt(), onSurfaceVariant = 0xFF5B6070.toInt(),
                primary = 0xFF4C5FD5.toInt(), success = 0xFF1E9E61.toInt(),
                warning = 0xFFB7791F.toInt(), error = 0xFFD93A3A.toInt()
            )
        }
    }
}
