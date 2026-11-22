package cn.onetap.checkin.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeyboxStateTest {
    private val target = Target("heybox:daily", "daily", "每日签到", metadata = mapOf("ownerUserId" to "123"))
    private val identity = Identity("123", "测试用户", listOf(target))

    @Test fun newlySavedHeyboxAccountAndHistorySurviveReload() {
        val credential = "heybox_id=123; pkey=fixture-session"
        val saved = Rules.addOrUpdate(AppState(), identity.copy(updatedCredential = credential),
            Platform.HEYBOX, "heybox_id=123; pkey=previous-fixture", "测试备注", null)
        val account = saved.accounts.single()
        val record = Record(accountId = account.id, platform = account.platform, accountLabel = account.note,
            targetId = target.id, task = target.label, period = "2026-11-22", status = TaskStatus.SUCCESS,
            message = "签到成功")
        val state = saved.copy(records = listOf(record), settings = Settings(auto = true))

        val reloaded = StateJson.decode(JSONObject(StateJson.encode(state).toString()))
        assertEquals(credential, reloaded.accounts.single().credential)
        assertEquals(state, reloaded)
    }

    @Test fun legacyAccountAndHistoryArePreservedWithoutRewritingStoredJson() {
        // Same fields used by the 0.4.x Store, before the retirement migration.
        val json = JSONObject("""
            {
              "accounts": [{
                "id":"old-account", "platform":"HEYBOX", "userId":"123", "name":"旧账号",
                "note":"旧备注", "credential":"user_heybox_id=123; user_pkey=legacy-fixture",
                "targets":[{"id":"heybox:daily","type":"daily","label":"每日签到","metadata":{"ownerUserId":"123"}}],
                "enabled":["heybox:daily"]
              }],
              "records": [{
                "id":"old-record", "accountId":"old-account", "platform":"HEYBOX", "accountLabel":"旧备注",
                "targetId":"heybox:daily", "task":"每日签到", "period":"2026-10-17", "time":1,
                "status":"ALREADY", "message":"今日已签到"
              }],
              "settings":{"auto":true}
            }
        """.trimIndent())
        val original = json.toString()
        val state = StateJson.decode(json)
        assertEquals("user_heybox_id=123; user_pkey=legacy-fixture", state.accounts.single().credential)
        assertEquals(setOf(target.id), state.accounts.single().enabled)
        assertEquals("old-record", state.records.single().id)
        assertTrue(state.settings.auto)
        assertEquals(original, json.toString())
        assertEquals(state, StateJson.decode(StateJson.encode(state)))
    }

    @Test fun unknownPlatformsStillFailWithoutSilentlyDroppingData() {
        val state = Rules.addOrUpdate(AppState(), identity, Platform.HEYBOX, "fixture", "", null)
        val record = Record(accountId = state.accounts.single().id, platform = Platform.HEYBOX,
            accountLabel = "测试用户", targetId = target.id, task = target.label, period = "2026-11-22",
            status = TaskStatus.ALREADY, message = "今日已签到")
        for (key in listOf("accounts", "records")) {
            val root = StateJson.encode(state.copy(records = listOf(record)))
            val row = root.getJSONArray(key).getJSONObject(0).put("platform", "FUTURE_PLATFORM")
            assertThrows(IllegalArgumentException::class.java) { StateJson.decode(root) }
            assertEquals("FUTURE_PLATFORM", row.getString("platform"))
        }
    }
}
