package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Stat
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Synthetic protocol fixtures only; these tests do not claim a real member-account check-in. */
class NeteaseVipTest {
    private val target = Target(NeteaseAdapter.TARGET_ID, NeteaseAdapter.TARGET_TYPE, "黑胶乐签",
        metadata = mapOf("ownerUserId" to "123"))
    private val identity = """{"code":200,"account":{"id":123},"profile":{"userId":123,"nickname":"测试用户"}}"""
    private val growthPath = "/vipnewcenter/app/level/growhpoint/basic"
    private val growth = """{"code":200,"data":{"userLevel":{"level":5,"growthPoint":"2500","nextLevelGrowthPoint":3000}}}"""
    private fun state(signed: Boolean) = """{"code":200,"data":{"signInfoList":[{"today":false,"sign":true},{"today":true,"sign":$signed}]}}"""

    private data class Call(val path: String, val data: JSONObject, val cookies: Map<String, String>, val eapi: Boolean)
    private fun fake(responses: List<String>, calls: MutableList<Call>,
        pause: (Long) -> Unit = {}): NeteaseAdapter {
        val queue = ArrayDeque(responses.map(::JSONObject))
        return NeteaseAdapter(pause = pause) { path, data, cookies, eapi ->
            calls += Call(path, JSONObject(data.toString()), cookies.toMap(), eapi)
            check(queue.isNotEmpty()) { "Unexpected extra request" }
            queue.removeFirst()
        }
    }

    @Test fun onlyExplicitServerTodayAndBooleanSignDetermineCompletion() {
        assertEquals(TaskStatus.ALREADY, NeteaseAdapter.parseVipState(JSONObject(state(true))).status)
        assertEquals(TaskStatus.PENDING, NeteaseAdapter.parseVipState(JSONObject(state(false))).status)
        for (raw in listOf(
            """{"code":200}""",
            """{"code":200,"data":{"signInfoList":[]}}""",
            """{"code":200,"data":{"signInfoList":[{"today":false,"sign":true}]}}""",
            """{"code":200,"data":{"signInfoList":[{"today":"true","sign":true}]}}""",
            """{"code":200,"data":{"signInfoList":[{"today":true,"sign":"true"}]}}""",
            """{"code":200,"data":{"signInfoList":[{"today":true,"sign":1}]}}""",
            """{"code":200,"data":{"signInfoList":[{"today":true,"sign":false},{"today":true,"sign":true}]}}""",
            """{"code":"200","data":{"signInfoList":[{"today":true,"sign":true}]}}""",
            """{"code":0,"data":{"signInfoList":[{"today":true,"sign":true}]}}"""
        )) assertEquals(raw, TaskStatus.UNKNOWN, NeteaseAdapter.parseVipState(JSONObject(raw)).status)
    }

    @Test fun eapiMatchesIndependentDotNetMd5AndAesVector() {
        // .NET MD5 + AES/ECB/PKCS7 over the upstream EAPI envelope, independently computed.
        val encrypted = NeteaseAdapter.encryptEapi("/api/vipnewcenter/app/minidesk/music/sign/pc", """{"type":1,"e_r":false}""")
        assertEquals(setOf("params"), encrypted.keys)
        assertEquals("E422E21FE1C070374FA6D7A8393DDEC270055A7D4A63F577D6BD7B9E89F1E63E23F29EADE5EC16BDC9335E4A158791D24D9A2F923245077453812812644C7C983860D964F422B6D029A48224B45BA8590F30AD1CE6F72AB204BA3E894567242EA7A08EF07BECE31E7582B1A519D285F82A5E742AD2C00CDD042B35048AAD665B", encrypted["params"])
    }

    @Test fun musicSignUsesTwoRequiredStepsAndVerifiesServerState() {
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, state(false), """{"code":200,"data":true}""", """{"code":200,"data":{"recordId":1}}""", state(true), growth), calls)
        val result = adapter.execute("MUSIC_U=synthetic; foreign=never-forward", target)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(listOf(Stat("黑胶等级", "V5"), Stat("成长值", "", 2500, 3000)), result.stats)
        assertEquals(listOf("/nuser/account/get", "/vipnewcenter/app/minidesk/music/sign/pc", "/vip-center-bff/task/sign",
            "/vipnewcenter/app/level/user/checkin/history/detail", "/vipnewcenter/app/minidesk/music/sign/pc", growthPath), calls.map { it.path })
        assertFalse(calls[0].eapi)
        assertTrue(calls[1].eapi)
        assertFalse(calls[2].eapi)
        assertEquals(0, calls[2].data.length())
        assertTrue(calls[3].eapi)
        assertEquals(1, calls[3].data.getInt("type"))
        assertTrue(calls[3].data.getLong("signDayTime") > 0)
        assertEquals(1, calls[4].data.getInt("type"))
        assertFalse(calls[5].eapi)
        assertEquals(0, calls[5].data.length())
        assertTrue(calls.all { it.cookies.keys == setOf("MUSIC_U") })
        assertTrue(calls.none { it.path.contains("dailyTask") || it.path.contains("reward") })
    }

    @Test fun allWriteCodes200StillNeedExplicitConfirmedTodaySign() {
        for (last in listOf(state(false), """{"code":200,"data":{}}""")) {
            val calls = mutableListOf<Call>()
            val waits = mutableListOf<Long>()
            val adapter = fake(listOf(identity, state(false), """{"code":200}""", """{"code":200}""") +
                List(4) { last }, calls, waits::add)
            val result = adapter.execute("synthetic", target)
            assertEquals(TaskStatus.UNKNOWN, result.status)
            assertEquals("结果未确认，请稍后重试", result.message)
            assertEquals(listOf(1000L, 1500L, 2500L), waits)
        }
    }

    @Test fun delayedVipStateConfirmationOnlyRepeatsTheRead() {
        val calls = mutableListOf<Call>()
        val waits = mutableListOf<Long>()
        val adapter = fake(listOf(identity, state(false), """{"code":200}""", """{"code":200}""",
            state(false), state(false), state(true), growth), calls, waits::add)
        val result = adapter.execute("synthetic", target)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals("签到成功", result.message)
        assertEquals(listOf(1000L, 1500L), waits)
        assertEquals(1, calls.count { it.path.endsWith("/task/sign") })
        assertEquals(1, calls.count { it.path.endsWith("/history/detail") })
        assertEquals(4, calls.count { it.path.endsWith("/music/sign/pc") })
    }

    @Test fun postWriteReadTimeoutIsRetriedWithoutEscapingAsAWriteTimeout() {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val statePath = "/vipnewcenter/app/minidesk/music/sign/pc"
        val adapter = NeteaseAdapter(pause = waits::add) { path, _, _, _ ->
            calls += path
            when (path) {
                "/nuser/account/get" -> JSONObject(identity)
                statePath -> when (calls.count { it == statePath }) {
                    1 -> JSONObject(state(false))
                    2 -> throw IOException("fixture")
                    else -> JSONObject(state(true))
                }
                else -> JSONObject("""{"code":200}""")
            }
        }
        assertEquals(TaskStatus.SUCCESS, adapter.execute("synthetic", target).status)
        assertEquals(listOf(1000L), waits)
        assertEquals(7, calls.size)
    }

    @Test fun alreadySignedDoesNotSendEitherWriteStep() {
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, state(true), growth), calls)
        assertEquals(TaskStatus.ALREADY, adapter.execute("synthetic", target).status)
        assertEquals(listOf("/nuser/account/get", "/vipnewcenter/app/minidesk/music/sign/pc", growthPath), calls.map { it.path })
    }

    @Test fun unknownDetailCannotClaimThisRequestSucceededEvenWhenTodayIsConfirmed() {
        val calls=mutableListOf<Call>()
        val adapter=fake(listOf(identity,state(false),"""{"code":200}""","""{"code":999}""",state(true),growth),calls)
        assertEquals(TaskStatus.ALREADY,adapter.execute("synthetic",target).status)
    }

    @Test fun growthStatsUseCandidateFieldsAndKeepDiagnosticLimitedToFieldNames() {
        val cases = listOf(
            """{"code":200,"data":{"levelCard":{"vipLevel":"7","growPoint":4800}}}""" to
                listOf(Stat("黑胶等级", "V7"), Stat("成长值", "4800")),
            """{"code":200,"data":{"userLevel":{"level":{},"growthPoint":"private-value","nextLevelGrowthPoint":false,
                "private-key":"secret"}}}""" to emptyList()
        )
        for ((response, expected) in cases) {
            val calls = mutableListOf<Call>()
            val adapter = fake(listOf(identity, state(true), response, identity, state(true)), calls)
            val result = adapter.query("synthetic", target)!!
            assertEquals(TaskStatus.ALREADY, result.status)
            assertEquals(expected, result.stats)
            if (expected.isEmpty()) {
                assertEquals("level,vipLevel,currentLevel,growthPoint,currentGrowthPoint,growPoint,nextLevelGrowthPoint," +
                    "nextGrowthPoint,nextLevelPoint,nextLevelNeedGrowthPoint", result.diagnostic.substringAfter("stats 未识别：字段 "))
            } else assertFalse(result.diagnostic.contains("stats 未识别"))
            assertEquals(result, adapter.execute("synthetic", target))
            assertEquals(1, calls.count { it.path == growthPath })
        }
    }

    @Test fun statReadFailuresPreserveCompletionAndAreCachedUntilTheNextValidation() {
        var reads = 0
        val adapter = NeteaseAdapter { path, _, _, _ ->
            when (path) {
                "/nuser/account/get" -> JSONObject(identity)
                "/vipnewcenter/app/minidesk/music/sign/pc" -> JSONObject(state(true))
                growthPath -> when (++reads) {
                    1 -> throw IOException("private-value")
                    2 -> throw AdapterException(TaskStatus.EXPIRED, "private-value")
                    else -> JSONObject(growth)
                }
                else -> error("Unexpected endpoint")
            }
        }
        for (attempt in 1..2) {
            adapter.validate("synthetic")
            val result = adapter.query("synthetic", target)!!
            assertEquals(TaskStatus.ALREADY, result.status)
            assertTrue(result.stats.isEmpty())
            assertFalse(result.diagnostic.contains("private-value"))
            assertEquals(result, adapter.execute("synthetic", target))
            assertEquals(attempt, reads)
        }
        adapter.validate("synthetic")
        assertEquals(listOf(Stat("黑胶等级", "V5"), Stat("成长值", "", 2500, 3000)), adapter.query("synthetic", target)?.stats)
        assertEquals(3, reads)
    }

    @Test fun eapiHeaderCookieUsesUpstreamPercentEncoding() {
        val header=JSONObject().put("os","pc").put("osver","x y/+中").put("MUSIC_U","synthetic-session")
        val cookie=NeteaseAdapter.eapiCookie(header).split("; ").toSet()
        assertEquals(setOf("os=pc","osver=x%20y%2F%2B%E4%B8%AD","MUSIC_U=synthetic-session"),cookie)
    }

    @Test fun missingStatusStopsWritesRatherThanGuessingPending() {
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, """{"code":200,"data":{}}"""), calls)
        assertEquals(TaskStatus.UNKNOWN, adapter.execute("synthetic", target).status)
        assertEquals(2, calls.size)
    }

    @Test fun identityOwnershipAndLegacyTargetCannotCrossExecute() {
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, identity), calls)
        val wrongOwner = target.copy(metadata = mapOf("ownerUserId" to "456"))
        assertEquals(TaskStatus.EXPIRED, adapter.execute("synthetic", wrongOwner).status)
        assertEquals(TaskStatus.EXPIRED, adapter.query("synthetic", wrongOwner)?.status)
        assertEquals(2, calls.size)
        assertEquals(TaskStatus.UNKNOWN, adapter.execute("synthetic", Target("netease:daily:android", "daily", "旧任务")).status)
        assertEquals(2, calls.size)
    }

    @Test fun queryOnlyReadsStateAndNeverCallsDetailOrTaskSign() {
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, state(false)), calls)
        assertEquals(TaskStatus.PENDING, adapter.query("synthetic", target)?.status)
        assertEquals(listOf("/nuser/account/get", "/vipnewcenter/app/minidesk/music/sign/pc"), calls.map { it.path })
    }

    @Test fun expiredOrManualResultStopsLaterSteps() {
        for ((raw, expected) in listOf("""{"code":301}""" to TaskStatus.EXPIRED,
            """{"code":999,"message":"请完成安全验证"}""" to TaskStatus.MANUAL)) {
            val calls = mutableListOf<Call>()
            val adapter = fake(listOf(identity, state(false), raw), calls)
            assertEquals(expected, adapter.execute("synthetic", target).status)
            assertEquals(3, calls.size)
        }
        val calls = mutableListOf<Call>()
        val adapter = fake(listOf(identity, state(false), """{"code":200}""", """{"code":301}"""), calls)
        assertEquals(TaskStatus.EXPIRED, adapter.execute("synthetic", target).status)
        assertEquals(4, calls.size)
    }

    @Test fun timedOutWriteIsNotConvertedToSuccessOrRepeatedInsideAdapter() {
        val calls = mutableListOf<String>()
        val adapter = NeteaseAdapter { path, _, _, _ ->
            calls += path
            when (path) {
                "/nuser/account/get" -> JSONObject(identity)
                "/vipnewcenter/app/minidesk/music/sign/pc" -> JSONObject(state(false))
                else -> throw IOException("synthetic timeout")
            }
        }
        try { adapter.execute("synthetic", target); fail("write timeout must reach Runner reconciliation") }
        catch (_: IOException) { assertEquals(3, calls.size) }
    }

    @Test fun validationDiscoversVipMusicSignAndYunbeiWithoutRequiringPaidVip() {
        val calls = mutableListOf<Call>()
        val actual = fake(listOf(identity), calls).validate("synthetic")
        assertEquals("123", actual.userId)
        assertEquals(listOf(NeteaseAdapter.TARGET_ID,NeteaseAdapter.YUNBEI_ID), actual.targets.map { it.id })
        assertEquals(NeteaseAdapter.TARGET_TYPE, actual.targets.first().type)
        assertTrue(actual.targets.all { it.metadata["ownerUserId"]=="123" })
        assertEquals(1,calls.size)
    }
}
