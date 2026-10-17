package cn.onetap.checkin.core

import cn.onetap.checkin.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeSettingsTest {
    @Test fun oldSettingsKeepTheirValuesAndDefaultToSystemTheme() {
        val previous = JSONObject("""{"auto":true,"hour":22,"minute":45,"notifications":true,"offerCatchup":false}""")
        assertEquals(Settings(true, 22, 45, true, false, ThemeMode.SYSTEM), SettingsJson.decode(previous))
    }

    @Test fun missingSettingsUseExistingDefaults() {
        assertEquals(Settings(), SettingsJson.decode(JSONObject()))
    }

    @Test fun explicitThemePersistsWithOtherSettings() {
        val settings = Settings(true, 7, 15, true, false, ThemeMode.DARK)
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
    }

    @Test fun unrecognizedStoredThemeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, SettingsJson.decode(JSONObject().put("themeMode", "future-mode")).themeMode)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
    }

    @Test fun systemThemeFollowsBothSystemStates() {
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
    }

    @Test fun explicitThemeOverridesBothSystemStates() {
        for (systemDark in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
        }
    }
}
