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
                background = 0xFF101916.toInt(), surface = 0xFF17221E.toInt(),
                onSurface = 0xFFE0E6E1.toInt(), onSurfaceVariant = 0xFFBDC9C1.toInt(),
                primary = 0xFF79D8C6.toInt(), success = 0xFF79D8C6.toInt(),
                warning = 0xFFF0C66A.toInt(), error = 0xFFFFB4AB.toInt()
            )
        } else {
            ThemePalette(
                background = 0xFFF5F8F7.toInt(), surface = 0xFFF8FAF8.toInt(),
                onSurface = 0xFF191D1B.toInt(), onSurfaceVariant = 0xFF404943.toInt(),
                primary = 0xFF087F74.toInt(), success = 0xFF087F74.toInt(),
                warning = 0xFF845800.toInt(), error = 0xFFBA1A1A.toInt()
            )
        }
    }
}
