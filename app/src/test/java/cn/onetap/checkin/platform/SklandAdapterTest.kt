package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Rules
import cn.onetap.checkin.core.Stat
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only. No production credentials, account writes, or simulated production UI. */
class SklandAdapterTest {
    @Test fun fixedSignaturesMatchIndependentDotNetVectors() {
        // Computed separately with .NET HMACSHA256 and MD5, not the Kotlin function under test.
        // First HMAC hex: d27599a097d7ca4b942a83f02de9fccd072c03e0ea64cdce9d3153b4bbd6e9a0.
        assertEquals("2e1e6d540613cef0b8a6d90809a7a3e6", SklandAdapter.signature(
            "test-sign-token", "/api/v1/game/player/binding", "", "1700000000"
        ))
        // Second HMAC hex: f303c463b60f0722f6c237c5b70bc8aae0756754d89d286f0a4f578f850bc57b.
        assertEquals("2f29ed6f2c77036947492c3b4bbdb079", SklandAdapter.signature(
            "test-sign-token", "/api/v1/game/attendance", "{\"uid\":\"12345\"}", "1700000000"
        ))
        assertNotEquals(SklandAdapter.signature("a", "/path", "", "1700000000"),
            SklandAdapter.signature("b", "/path", "", "1700000000"))
        assertNotEquals(SklandAdapter.signature("a", "/path", "", "1700000000"),
            SklandAdapter.signature("a", "/path", "{}", "1700000000"))
    }

    @Test fun twoAccountsKeepDistinctOwnersAndAllRoleServerSelections() {
        val apps = JSONArray("""[
          {"appCode":"arknights","bindingList":[
            {"uid":"ark-official","nickName":"主官服","channelMasterId":"1","channelName":"官服"},
            {"uid":"ark-bili","nickName":"主B服","channelMasterId":"2","channelName":"bilibili服"}
          ]},
          {"appCode":"endfield","bindingList":[
            {"uid":"binding-id-must-not-be-role","channelMasterId":"binding-channel-must-not-be-server","roles":[
              {"roleId":"actual-role-a","serverId":"actual-server-a","nickname":"管理员A","serverName":"区服A"},
              {"roleId":"actual-role-b","serverId":"actual-server-b","nickname":"管理员B","serverName":"区服B"}
            ]}
          ]}
        ]""")
        val main = SklandAdapter.parseTargets("owner-main", apps)
        val clone = SklandAdapter.parseTargets("owner-clone", apps)
        assertEquals(4, main.size)
        assertTrue(main.all { it.metadata["ownerUserId"] == "owner-main" })
        assertTrue(clone.all { it.metadata["ownerUserId"] == "owner-clone" })
        assertEquals(setOf("actual-role-a", "actual-role-b"), main.filter { it.type == "endfield" }.map { it.metadata["roleId"] }.toSet())
        assertEquals(setOf("actual-server-a", "actual-server-b"), main.filter { it.type == "endfield" }.map { it.metadata["serverId"] }.toSet())
        assertTrue(main.filter { it.type == "endfield" }.none { it.metadata["roleId"] == "binding-id-must-not-be-role" })
        assertEquals(4, main.map { it.id }.toSet().size)
        assertTrue(main.all { it.metadata["resetVerified"] == "false" })
    }

    @Test fun noBindingsAreUnboundWhileMalformedBindingsAreUnknown() {
        val empty = SklandAdapter.parseTargets("owner", JSONArray())
        assertEquals(2, empty.size)
        assertTrue(empty.all { !it.bound })
        val malformed = assertThrows(AdapterException::class.java) {
            SklandAdapter.parseTargets("owner", JSONArray("""[{"appCode":"endfield","bindingList":"changed"}]"""))
        }
        assertEquals(TaskStatus.UNKNOWN, malformed.status)
        val missingRole = SklandAdapter.parseTargets("owner", JSONArray("""[{"appCode":"endfield","bindingList":[{"uid":"binding-only","roles":[]}]}]"""))
            .first { it.type == "endfield" }
        assertTrue(missingRole.bound)
        assertEquals("true", missingRole.metadata["roleMissing"])
        assertNull(missingRole.metadata["roleId"])
    }

    @Test fun explicitDuplicateIsRequiredForAlreadySigned() {
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("endfield",
            JSONObject("""{"code":10001,"message":"参数错误"}""")).status)
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("arknights",
            JSONObject("""{"code":10001,"message":"未知错误"}""")).status)
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("arknights",
            JSONObject("""{"code":10000,"message":"请求异常"}""")).status)
        assertEquals(TaskStatus.ALREADY, SklandAdapter.parseAttendanceResult("endfield",
            JSONObject("""{"code":10001,"message":"请勿重复签到"}""")).status)
        assertEquals(TaskStatus.EXPIRED, SklandAdapter.parseAttendanceResult("endfield",
            JSONObject("""{"code":10002,"message":"用户未登录"}""")).status)
        assertEquals(TaskStatus.MANUAL, SklandAdapter.parseAttendanceResult("arknights",
            JSONObject("""{"code":10003,"message":"请勿修改设备本地时间"}""")).status)
    }

    @Test fun gameRewardSchemasAreSeparateAndMissingFieldsNeverSucceed() {
        val ark = JSONObject("""{"code":0,"data":{"awards":[{"resource":{"name":"龙门币"},"count":3000},
            {"resource":{"name":"合成玉"},"count":100}]}}""")
        val ef = JSONObject("""{"code":0,"data":{"ts":"1700000000","awardIds":[{"id":"reward"}],"resourceInfoMap":{"reward":{"name":"嵌晶玉","count":80}}}}""")
        for ((kind, response, value) in listOf(Triple("arknights", ark, "龙门币 ×3000、合成玉 ×100"), Triple("endfield", ef, "嵌晶玉 ×80"))) {
            val result = SklandAdapter.parseAttendanceResult(kind, response)
            assertEquals(TaskStatus.SUCCESS, result.status)
            assertEquals("签到成功", result.message)
            assertEquals(listOf(Stat("今日奖励", value)), result.stats)
        }
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("arknights", ef).status)
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("endfield", ark).status)
        for (json in listOf("{}", "{\"code\":0}", "{\"code\":0,\"data\":{}}", "{\"code\":0,\"data\":{\"awards\":[]}}")) {
            assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("arknights", JSONObject(json)).status)
        }
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceResult("arknights",
            JSONObject("""{"code":0.5,"data":{"awards":[{"resource":{"name":"合成玉"},"count":100}]}}""")).status)
    }

    @Test fun rewardTextIsLimitedToFortyCharacters() {
        val name = "超长的奖励名称".repeat(8)
        val response = JSONObject("""{"code":0,"data":{"awards":[{"resource":{"name":"$name"},"count":3}]}}""")
        val result = SklandAdapter.parseAttendanceResult("arknights", response)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(40, result.stats.single().value.length)
        assertTrue(result.stats.single().value.endsWith("…"))
    }

    @Test fun endfieldStateRequiresActualBoolean() {
        assertEquals(TaskStatus.PENDING, SklandAdapter.parseAttendanceState("endfield", JSONObject("""{"code":0,"data":{"hasToday":false}}""")).status)
        assertEquals(TaskStatus.ALREADY, SklandAdapter.parseAttendanceState("endfield", JSONObject("""{"code":0,"data":{"hasToday":true}}""")).status)
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceState("endfield", JSONObject("""{"code":0,"data":{}}""")).status)
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceState("endfield", JSONObject("""{"code":0,"data":{"hasToday":"false"}}""")).status)
    }

    @Test fun arkNoAvailableCalendarNeedsActualTodayRecord() {
        val ready = JSONObject("""{"code":0,"data":{"currentTs":"1700000000","calendar":[{"type":"daily","available":true,"done":false}],"records":[]}}""")
        assertEquals(TaskStatus.PENDING, SklandAdapter.parseAttendanceState("arknights", ready).status)
        val unavailable = JSONObject("""{"code":0,"data":{"currentTs":"1700000000","calendar":[{"type":"daily","available":false,"done":false}],"records":[]}}""")
        assertEquals(TaskStatus.UNKNOWN, SklandAdapter.parseAttendanceState("arknights", unavailable).status)
        unavailable.getJSONObject("data").getJSONArray("records").put(JSONObject().put("ts", "1700000000"))
        assertEquals(TaskStatus.ALREADY, SklandAdapter.parseAttendanceState("arknights", unavailable).status)
    }

    @Test fun arkAlreadySignedMapsAllTodayRecordsUsingTheServerDateInShanghai() {
        // 16:00 UTC starts the next day in Shanghai. Ignore the previous day's reward.
        val response = JSONObject("""{"code":0,"data":{
            "currentTs":1700064010,
            "calendar":[{"type":"daily","available":false,"done":true}],
            "records":[{"ts":1700063999,"resourceId":"old","count":1},
                {"ts":1700064000,"resourceId":"lmd","count":3000},
                {"ts":1700064000,"awardIds":[{"id":"jade"}]}],
            "resourceInfoMap":{"old":{"name":"昨日物品"},"lmd":{"name":"龙门币","count":9},"jade":{"name":"合成玉","count":100}}
        }}""")
        val result = SklandAdapter.parseAttendanceState("arknights", response)
        assertEquals(TaskStatus.ALREADY, result.status)
        assertEquals(listOf(Stat("今日奖励", "龙门币 ×3000、合成玉 ×100")), result.stats)
    }

    @Test fun endfieldSignedCalendarUsesTheLastConsecutiveDoneDailySlot() {
        val response = JSONObject("""{"code":0,"data":{"hasToday":true,"calendar":[
            {"type":"daily","done":true,"awardId":"first"},
            {"type":"daily","done":true,"awardId":"today","count":"80"},
            {"type":"special","done":true,"awardId":"bonus"},
            {"type":"daily","done":false,"awardId":"future"}],
            "resourceInfoMap":{"first":{"name":"已领物品","count":4},"today":{"name":"嵌晶玉"},
                "bonus":{"name":"额外物品","count":5},"future":{"name":"未来物品","count":100}}}}""")
        val result = SklandAdapter.parseAttendanceState("endfield", response)
        assertEquals(TaskStatus.ALREADY, result.status)
        assertEquals(listOf(Stat("今日奖励", "嵌晶玉 ×80")), result.stats)

        response.getJSONObject("data").put("hasToday", false)
        assertTrue(SklandAdapter.parseAttendanceState("endfield", response).stats.isEmpty())
    }

    @Test fun endfieldUsesDatedCalendarRewardsAndFallsBackToAnExplicitAwardList() {
        val response = JSONObject("""{"code":0,"data":{"hasToday":true,"currentTs":1700064010,
            "calendar":[{"type":"daily","done":true,"ts":1700063999,"awardId":"old"},
                {"type":"daily","done":true,"ts":1700064000,"awardId":"today"}],
            "resourceInfoMap":{"old":{"name":"昨日物品","count":4},"today":{"name":"嵌晶玉","count":80}}}}""")
        val expected = listOf(Stat("今日奖励", "嵌晶玉 ×80"))
        assertEquals(expected, SklandAdapter.parseAttendanceState("endfield", response).stats)
        response.getJSONObject("data").apply {
            remove("calendar")
            put("awardIds", JSONArray().put("today"))
        }
        assertEquals(expected, SklandAdapter.parseAttendanceState("endfield", response).stats)
    }

    @Test fun missingOrAmbiguousRewardDataRetainsAlreadyAndOnlyReportsFieldNames() {
        val ark = JSONObject("""{"code":0,"data":{"currentTs":1700064010,
            "calendar":[{"type":"daily","available":false,"done":true}],
            "records":[{"ts":9223372036854775807},{"ts":1700064000,"resourceId":"private-value"}],
            "privateKey":"private-value"}}""")
        val ef = JSONObject("""{"code":0,"data":{"hasToday":true,"calendar":[
            {"type":"daily","done":false,"awardId":"missed"},
            {"type":"daily","done":true,"awardId":"done"}],
            "resourceInfoMap":{"done":{"name":"private-value","count":80}}}}""")
        for ((kind, response) in listOf("arknights" to ark, "endfield" to ef)) {
            val result = SklandAdapter.parseAttendanceState(kind, response)
            assertEquals(TaskStatus.ALREADY, result.status)
            assertTrue(result.stats.isEmpty())
            assertEquals("stats 未识别：字段 records,calendar,ts,date,done,awardIds,awardId,resourceId,resourceInfoMap,id,name,count",
                Rules.safeDiagnostic(result.diagnostic))
        }
    }

    @Test fun errorDiagnosticsNeverEchoUntrustedMessagesOrCredentialFields() {
        val secret = "synthetic-secret-should-not-be-exported"
        val result = SklandAdapter.parseAttendanceResult("endfield", JSONObject()
            .put("code", 876543).put("message", secret).put("cred", secret)
            .put("data", JSONObject().put("token", secret).put("password", secret)))
        assertEquals(TaskStatus.UNKNOWN, result.status)
        assertFalse(result.message.contains(secret))
        assertFalse(result.diagnostic.contains(secret))
        assertFalse(result.diagnostic.contains("password"))
    }
}
