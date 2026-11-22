package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.HttpStatusException
import cn.onetap.checkin.core.Stat
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Isolated synthetic responses exercising the production execute path. No platform credentials or traffic. */
class TiebaSignSelfCheckTest {
    private val target = Target("tieba:forum:101", "forum", "合成测试吧", metadata = mapOf(
        "ownerUserId" to "42", "forumId" to "101", "forumName" to "合成测试"
    ))

    @Test fun positiveWriteNeedsIndependentSignedStateBeforeSuccess() {
        val fixture = Fixture(listOf(guide(0), guide(1)))
        val result = fixture.adapter.execute("synthetic-session", target)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals("签到成功，经验 +8", result.message)
        assertEquals("tieba forumGuide is_sign=1; stats 未识别：字段 level_id,level_name,cur_score,levelup_score,cont_sign_num",
            result.diagnostic)
        assertEquals(listOf("login", "identity", "guide", "sign", "guide"), fixture.calls)
        assertEquals(1, fixture.writes)
    }

    @Test fun repeatedSignedStatusNeedsSelfCheckBeforeAlready() {
        val fixture = Fixture(listOf(guide(0), guide(1)), """{"error_code":160002}""")
        assertEquals(TaskStatus.ALREADY, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(1, fixture.writes)
        assertEquals(2, fixture.reads)
    }

    @Test fun writeAloneCannotSucceedWhenEveryReadStillUnsigned() {
        for (ack in listOf(SUCCESS_ACK, """{"error_code":160002}""")) {
            val fixture = Fixture(List(4) { guide(0) }, ack)
            val result = fixture.adapter.execute("synthetic-session", target)
            assertEquals(TaskStatus.UNKNOWN, result.status)
            assertEquals("结果未确认，请稍后重试", result.message)
            assertEquals(1, fixture.writes)
            assertEquals(4, fixture.reads)
            assertEquals(listOf(400L, 800L), fixture.waits)
        }
    }

    @Test fun eventualSignedStateRetriesOnlyTheRead() {
        val fixture = Fixture(listOf(guide(0), guide(0), guide(0), guide(1)))
        assertEquals(TaskStatus.SUCCESS, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(1, fixture.writes)
        assertEquals(4, fixture.reads)
        assertEquals(listOf(400L, 800L), fixture.waits)
    }

    @Test fun missingForumOrMalformedSignedValueNeverConfirmsCompletion() {
        for (unavailable in listOf(
            """{"error_code":0,"like_forum":[]}""",
            """{"error_code":0,"like_forum":[{"forum_id":"999","is_sign":1}]}""",
            """{"error_code":0,"like_forum":[{"forum_id":"101"}]}""",
            """{"error_code":0,"like_forum":[{"forum_id":"101","is_sign":2}]}""",
            """{"error_code":0}"""
        )) {
            val fixture = Fixture(listOf(guide(0)) + List(3) { { JSONObject(unavailable) } })
            val result = fixture.adapter.execute("synthetic-session", target)
            assertEquals(unavailable, TaskStatus.UNKNOWN, result.status)
            assertEquals("结果未确认，请稍后重试", result.message)
            assertEquals(1, fixture.writes)
            assertEquals(4, fixture.reads)
        }
    }

    @Test fun readTimeoutRetriesAreBoundedAndNeverRepeatSignWrite() {
        val fixture = Fixture(listOf(guide(0)) + List(3) { { throw IOException("synthetic failure") } })
        assertEquals(TaskStatus.UNKNOWN, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(1, fixture.writes)
        assertEquals(4, fixture.reads)
        assertEquals(listOf(1500L, 3000L), fixture.waits)
    }

    @Test fun existingSignedStateNeedsNoWrite() {
        val fixture = Fixture(listOf(guide(1)))
        val result = fixture.adapter.execute("synthetic-session", target)
        assertEquals(TaskStatus.ALREADY, result.status)
        assertEquals("今日已签到", result.message)
        assertEquals(0, fixture.writes)
        assertEquals(1, fixture.reads)
    }

    @Test fun signedForumQueryUsesFollowedStatsWithoutAnotherReadOrChangingTargetMetadata() {
        val fixture = Fixture(listOf(guide(1)))
        fixture.followedForum = JSONObject("""{"id":"101","name":"合成测试","level_id":"7","level_name":"中级粉丝",
            "cur_score":100,"levelup_score":"150"}""")
        val identity = fixture.adapter.validate("synthetic-session")
        val forum = identity.targets.single { it.type == "forum" }
        assertFalse(forum.metadata.containsKey("level_id"))
        val stats = listOf(Stat("等级", "Lv.7 中级粉丝"), Stat("经验", "", 100, 150))
        assertEquals(stats, fixture.adapter.query("synthetic-session", forum)?.stats)
        assertEquals(stats, fixture.adapter.execute("synthetic-session", forum).stats)
        assertEquals(listOf("login", "followed", "identity", "guide"), fixture.calls)
    }

    @Test fun confirmedSignPrefersNewStatsAndKeepsThemAvailableForQuery() {
        val fixture = Fixture(listOf(guide(0), guide(1)), """{"error_code":0,"user_info":{
            "level_id":8,"level_name":"高级粉丝","cur_score":153,"levelup_score":300,
            "cont_sign_num":"12","sign_bonus_point":"8"}}""")
        fixture.followedForum = JSONObject("""{"id":"101","name":"合成测试","level_id":"7","level_name":"中级粉丝",
            "cur_score":145,"levelup_score":"150"}""")
        val forum = fixture.adapter.validate("synthetic-session").targets.first()
        val result = fixture.adapter.execute("synthetic-session", forum)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals("签到成功，经验 +8", result.message)
        assertEquals(listOf(Stat("等级", "Lv.8 高级粉丝"), Stat("经验", "", 153, 300), Stat("连签", "12 天")), result.stats)
        assertEquals(result.stats, fixture.adapter.query("synthetic-session", forum)?.stats)
        assertEquals(listOf("login", "followed", "identity", "guide", "sign", "guide"), fixture.calls)
    }

    @Test fun confirmedAlreadyCanUseStatsEvenWhenTheWriteDidNotReportTheBonus() {
        val fixture = Fixture(listOf(guide(0), guide(1)), """{"error_code":0,"user_info":{
            "level_name":"中级粉丝","levelup_score":180,"cont_sign_num":12}}""")
        fixture.followedForum = JSONObject("""{"id":"101","name":"合成测试","level_id":"7","cur_score":100,"levelup_score":150}""")
        val forum = fixture.adapter.validate("synthetic-session").targets.first()
        val result = fixture.adapter.execute("synthetic-session", forum)
        assertEquals(TaskStatus.ALREADY, result.status)
        assertEquals(listOf(Stat("等级", "Lv.7 中级粉丝"), Stat("经验", "", 100, 180), Stat("连签", "12 天")), result.stats)
    }

    @Test fun missingOrMaxLevelFieldsKeepTheConfirmedState() {
        for ((fields, stats) in listOf(
            "" to emptyList(),
            ""","level_id":"20","cur_score":1000,"levelup_score":"--","level_name":{ }""" to listOf(Stat("等级", "Lv.20")),
            ""","level_id":"20","cur_score":1000,"levelup_score":0""" to listOf(Stat("等级", "Lv.20"))
        )) {
            val fixture = Fixture(listOf(guide(1)))
            fixture.followedForum = JSONObject("""{"id":"101","name":"合成测试"$fields}""")
            val forum = fixture.adapter.validate("synthetic-session").targets.first()
            val result = fixture.adapter.query("synthetic-session", forum)
            assertEquals(TaskStatus.ALREADY, result?.status)
            assertEquals(stats, result?.stats)
        }
    }

    @Test fun writeWithUnknownOrMissingRewardCanOnlyBecomeAlreadyFromIndependentRead() {
        for (ack in listOf("""{"error_code":0}""", """{"error_code":999}""", "{}")) {
            val fixture = Fixture(listOf(guide(0), guide(1)), ack)
            assertEquals(ack, TaskStatus.ALREADY, fixture.adapter.execute("synthetic-session", target).status)
            assertEquals(1, fixture.writes)
        }
    }

    @Test fun manualAndExpiredReadResultsStopFurtherReadsAndWrites() {
        for ((message, status) in listOf("请输入验证码" to TaskStatus.MANUAL, "请先登录" to TaskStatus.EXPIRED)) {
            val fixture = Fixture(listOf(guide(0), { JSONObject().put("error_code", 999).put("error_msg", message) }))
            try {
                fixture.adapter.execute("synthetic-session", target)
                fail("Read restriction must stop immediately")
            } catch (e: AdapterException) {
                assertEquals(status, e.status)
            }
            assertEquals(1, fixture.writes)
            assertEquals(2, fixture.reads)
            assertTrue(fixture.waits.isEmpty())
        }
    }

    @Test fun manualOrExpiredAcknowledgementDoesNotPollAgain() {
        for ((message, status) in listOf("请输入验证码" to TaskStatus.MANUAL, "请先登录" to TaskStatus.EXPIRED)) {
            val fixture = Fixture(listOf(guide(0)), JSONObject().put("error_code", 999).put("error_msg", message).toString())
            assertEquals(status, fixture.adapter.execute("synthetic-session", target).status)
            assertEquals(1, fixture.writes)
            assertEquals(1, fixture.reads)
        }
    }

    @Test fun changedWebIdentityStopsBeforeTrustingOtherAccountsSignStateWhenIdentityCacheExpires() {
        for (changeAfterWrite in listOf(false, true)) {
            val fixture = Fixture(listOf(guide(0), guide(1)))
            fixture.identity = { call -> JSONObject().put("no", 0).put("data", JSONObject().put("id", if (changeAfterWrite && call == 1) "42" else "99")) }
            if (changeAfterWrite) fixture.onWrite = { fixture.now += 300_000 }
            try {
                fixture.adapter.execute("synthetic-session", target)
                fail("Cross-account state must not be trusted")
            } catch (e: AdapterException) {
                assertEquals(TaskStatus.EXPIRED, e.status)
            }
            assertEquals(if (changeAfterWrite) 1 else 0, fixture.writes)
            assertEquals(if (changeAfterWrite) 1 else 0, fixture.reads)
        }
    }

    @Test fun explicitWebIdentityExpiryOrChallengeStopsBeforeSign() {
        for ((message, status) in listOf("登录过期" to TaskStatus.EXPIRED, "请完成安全验证" to TaskStatus.MANUAL)) {
            val fixture = Fixture(emptyList())
            fixture.identity = { JSONObject().put("no", 999).put("error", message) }
            try {
                fixture.adapter.execute("synthetic-session", target)
                fail("Invalid web authorization must stop")
            } catch (e: AdapterException) {
                assertEquals(status, e.status)
            }
            assertEquals(0, fixture.writes)
            assertEquals(0, fixture.reads)
        }
    }

    @Test fun readQueryRemainsReadOnlyAndRequiresSameOwnerAndForum() {
        val fixture = Fixture(listOf(guide(0), guide(1)))
        assertEquals(TaskStatus.PENDING, fixture.adapter.query("synthetic-session", target)?.status)
        // Repeated reads share the short-lived guide; after its TTL the server is read again.
        assertEquals(TaskStatus.PENDING, fixture.adapter.query("synthetic-session", target)?.status)
        fixture.now += 20_000
        assertEquals(TaskStatus.ALREADY, fixture.adapter.query("synthetic-session", target)?.status)
        assertEquals(0, fixture.writes)
        assertTrue(fixture.adapter.requiresFreshCompletionCheck(target))
        assertFalse(fixture.adapter.requiresFreshCompletionCheck(target.copy(bound = false)))
        assertFalse(fixture.adapter.requiresFreshCompletionCheck(target.copy(type = "daily-like")))
    }

    @Test fun readOnlyRequestsRetryTransientFailuresWithBackoff() {
        val fixture = Fixture(listOf(
            { throw HttpStatusException(502, TaskStatus.UNKNOWN, "fixture") },
            { throw IOException("fixture") },
            guide(1)
        ))
        fixture.onLogin = { if (it == 1) throw HttpStatusException(302, TaskStatus.UNKNOWN, "fixture") }
        fixture.onFollowed = { if (it <= 2) throw HttpStatusException(503, TaskStatus.UNKNOWN, "fixture") }
        fixture.identity = {
            if (it == 1) throw IOException("fixture")
            JSONObject("""{"no":0,"data":{"id":"42"}}""")
        }
        assertEquals("42", fixture.adapter.validate("synthetic-session").userId)
        assertEquals(TaskStatus.ALREADY, fixture.adapter.query("synthetic-session", target)?.status)
        assertEquals(listOf("login", "login", "followed", "followed", "followed",
            "identity", "identity", "guide", "guide", "guide"), fixture.calls)
        assertEquals(listOf(1500L, 1500L, 3000L, 1500L, 1500L, 3000L), fixture.waits)
        assertEquals(0, fixture.writes)
    }

    @Test fun exhaustedWebReadErrorsReturnNoSignState() {
        for ((code, status, attempts) in listOf(
            Triple(302, TaskStatus.UNKNOWN, 3),
            Triple(302, TaskStatus.MANUAL, 1),
            Triple(302, TaskStatus.EXPIRED, 1),
            Triple(308, TaskStatus.UNKNOWN, 1),
            Triple(503, TaskStatus.UNKNOWN, 3)
        )) {
            val error = HttpStatusException(code, status, "fixture")
            val fixture = Fixture(List(attempts) { { throw error } })
            assertNull(fixture.adapter.query("synthetic-session", target))
            assertEquals(attempts, fixture.reads)
            assertEquals(attempts - 1, fixture.waits.size)
            assertEquals(0, fixture.writes)
        }
    }

    @Test fun webIdentityRedirectToWappassDoesNotBlockSigningWithBdussOnly() {
        val fixture = Fixture(listOf(guide(1)))
        fixture.identity = { attempt ->
            if (attempt == 1) throw HttpStatusException(302, TaskStatus.EXPIRED, "fixture",
                "HTTP 302; tieba endpoint=/mo/q/newmoindex; location_host=wappass.baidu.com")
            JSONObject("""{"no":0,"data":{"id":"42"}}""")
        }
        assertEquals(TaskStatus.SUCCESS, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(listOf("login", "identity", "sign", "identity", "guide"), fixture.calls)
        assertEquals(1, fixture.writes)
    }

    @Test fun webRedirectsAfterSigningRetryAsUnknownWithoutRepeatingWrite() {
        for (status in listOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) {
            val diagnostic = "HTTP 302; tieba endpoint=/c/f/forum/forumGuide; location_host=wappass.baidu.com"
            val error = HttpStatusException(302, status, "fixture", diagnostic)
            val fixture = Fixture(listOf(guide(0)) + List(3) { { throw error } })
            val result = fixture.adapter.execute("synthetic-session", target)
            assertEquals(TaskStatus.UNKNOWN, result.status)
            assertEquals(diagnostic, result.diagnostic)
            assertEquals(4, fixture.reads)
            assertEquals(listOf(1500L, 3000L), fixture.waits)
            assertEquals(1, fixture.writes)
        }
    }

    @Test fun appLoginRedirectStillStopsSigning() {
        for (status in listOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) {
            val error = HttpStatusException(302, status, "fixture")
            val fixture = Fixture(emptyList())
            fixture.onLogin = { throw error }
            assertSame(error, assertThrows(AdapterException::class.java) {
                fixture.adapter.execute("synthetic-session", target)
            })
            assertEquals(listOf("login"), fixture.calls)
        }
    }

    @Test fun ambiguousWriteIsNotReplayedWhenReadConfirmsSignedOrCannotConfirmState() {
        val diagnostic = "HTTP 302; tieba endpoint=/c/c/forum/sign; location_host=tiebac.baidu.com"
        for ((after, expected) in listOf(guide(1) to TaskStatus.ALREADY,
            { JSONObject("""{"error_code":0,"like_forum":[]}""") } to TaskStatus.UNKNOWN)) {
            val fixture = Fixture(listOf(guide(0)) + List(3) { after })
            fixture.onWrite = { throw HttpStatusException(302, TaskStatus.UNKNOWN, "fixture", diagnostic) }
            val result = fixture.adapter.execute("synthetic-session", target)
            assertEquals(expected, result.status)
            assertEquals(diagnostic, result.diagnostic)
            assertEquals(1, fixture.writes)
        }
    }

    @Test fun ambiguousWriteRetriesOnceAfterFreshUnsignedReadsAndObeysWriteInterval() {
        for (secondWriteSucceeds in listOf(true, false)) {
            val postRetry = if (secondWriteSucceeds) listOf(guide(1)) else List(3) { guide(0) }
            val fixture = Fixture(List(4) { guide(0) } + postRetry)
            val writeTimes = mutableListOf<Long>()
            fixture.onWrite = {
                writeTimes += fixture.now
                if (writeTimes.size == 1 || !secondWriteSucceeds)
                    throw HttpStatusException(302, TaskStatus.UNKNOWN, "fixture")
            }
            val result = fixture.adapter.execute("synthetic-session", target)
            assertEquals(if (secondWriteSucceeds) TaskStatus.SUCCESS else TaskStatus.UNKNOWN, result.status)
            assertEquals(listOf(0L, TiebaAdapter.WRITE_INTERVAL_MS), writeTimes)
            assertEquals(listOf("sign", "guide", "guide", "guide", "sign"), fixture.calls.drop(3).take(5))
        }
    }

    private fun guide(value: Any): () -> JSONObject = {
        JSONObject().put("error_code", 0).put("like_forum", org.json.JSONArray().put(
            JSONObject().put("forum_id", "101").put("is_sign", value)
        ))
    }

    private class Fixture(
        guides: List<() -> JSONObject>,
        private val acknowledgement: String = SUCCESS_ACK
    ) : TiebaTransport {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        var now = 0L
        var onWrite: () -> Unit = {}
        var onLogin: (Int) -> Unit = {}
        var onFollowed: (Int) -> Unit = {}
        var followedForum: JSONObject? = null
        val adapter = TiebaAdapter(null, this, pause = { waits += it; now += it }, clock = { now })
        var identity: (Int) -> JSONObject = { JSONObject("""{"no":0,"data":{"id":"42"}}""") }
        private val remainingGuides = ArrayDeque(guides)
        private var identities = 0
        var reads = 0
            private set
        var writes = 0
            private set

        override fun get(url: String, headers: Map<String, String>): JSONObject {
            assertEquals("https://tieba.baidu.com/mo/q/newmoindex?need_user=1", url)
            assertEquals("BDUSS=synthetic-session", headers["Cookie"])
            calls += "identity"
            return identity(++identities)
        }

        override fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>): JSONObject = when (url) {
            "https://tiebac.baidu.com/c/s/login" -> {
                calls += "login"
                onLogin(calls.count { it == "login" })
                assertEquals("synthetic-session", fields["bdusstoken"])
                JSONObject("""{"error_code":0,"user":{"id":"42","name":"合成账号"},"anti":{"tbs":"0123456789abcdef0123456789"}}""")
            }
            "https://tiebac.baidu.com/c/f/forum/like" -> {
                calls += "followed"
                onFollowed(calls.count { it == "followed" })
                followedForum?.let { JSONObject("""{"error_code":0,"forum_list":{"non-gconforum":[$it]},"has_more":0}""") }
                    ?: JSONObject("""{"error_code":0,"forum_list":[],"has_more":0}""")
            }
            "https://tieba.baidu.com/c/f/forum/forumGuide" -> {
                calls += "guide"; reads++
                assertEquals(mapOf("sort_type" to "3", "call_from" to "3"), fields)
                assertEquals("hybrid", headers["Subapp-Type"])
                check(remainingGuides.isNotEmpty()) { "Unexpected extra status read" }
                remainingGuides.removeFirst()()
            }
            "https://tiebac.baidu.com/c/c/forum/sign" -> {
                calls += "sign"; writes++
                onWrite()
                assertEquals("合成测试", fields["kw"])
                assertEquals("synthetic-session", fields["BDUSS"])
                assertNotNull(fields["sign"])
                JSONObject(acknowledgement)
            }
            else -> error("Unexpected endpoint: $url")
        }
    }

    companion object {
        private const val SUCCESS_ACK = """{"error_code":0,"user_info":{"sign_bonus_point":8}}"""
    }
}
