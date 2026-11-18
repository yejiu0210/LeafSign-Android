package cn.onetap.checkin.core

import cn.onetap.checkin.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun oldSixFieldsKeepTheirValuesAndNewFieldsUseDefaults() {
        val old = JSONObject("""{"auto":true,"hour":20,"minute":25,"notifications":true,"offerCatchup":false,"themeMode":"DARK"}""")
        assertEquals(Settings(true,20,25,true,false,ThemeMode.DARK,4,true,false,""), SettingsJson.decode(old))
        assertTrue(SettingsJson.decode(old).autoRetry)
        assertEquals(Settings(), SettingsJson.decode(JSONObject()))
    }
    @Test fun allNewFieldsRoundTripAlongsideExistingSettings() {
        val settings = Settings(true,7,30,true,false,ThemeMode.LIGHT,8,false,true,"2026-11-13",autoRetry=false)
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
    }
    @Test fun concurrencyIsClampedWhenReadingStoredSettings() {
        for ((raw, expected) in listOf(-1 to 1, 0 to 1, 1 to 1, 4 to 4, 8 to 8, 99 to 8))
            assertEquals(expected, SettingsJson.decode(JSONObject().put("concurrency",raw)).concurrency)
    }
}
