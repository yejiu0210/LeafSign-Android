package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
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
        assertTrue(result.message.contains("自检通过"))
        assertEquals("tieba forumGuide is_sign=1", result.diagnostic)
        assertEquals(listOf("login", "identity", "guide", "sign", "identity", "guide"), fixture.calls)
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
            assertTrue(result.message.contains("仍显示今日未签到"))
            assertEquals(1, fixture.writes)
            assertEquals(4, fixture.reads)
            assertEquals(listOf(1000L, 1000L), fixture.waits)
        }
    }

    @Test fun eventualSignedStateRetriesOnlyTheRead() {
        val fixture = Fixture(listOf(guide(0), guide(0), guide(0), guide(1)))
        assertEquals(TaskStatus.SUCCESS, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(1, fixture.writes)
        assertEquals(4, fixture.reads)
        assertEquals(listOf(1000L, 1000L), fixture.waits)
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
            assertTrue(result.message.contains("未能取得"))
            assertEquals(1, fixture.writes)
            assertEquals(4, fixture.reads)
        }
    }

    @Test fun readTimeoutRetriesAreBoundedAndNeverRepeatSignWrite() {
        val fixture = Fixture(listOf(guide(0)) + List(3) { { throw IOException("synthetic failure") } })
        assertEquals(TaskStatus.UNKNOWN, fixture.adapter.execute("synthetic-session", target).status)
        assertEquals(1, fixture.writes)
        assertEquals(4, fixture.reads)
        assertEquals(2, fixture.waits.size)
    }

    @Test fun existingSignedStateNeedsNoWrite() {
        val fixture = Fixture(listOf(guide(1)))
        val result = fixture.adapter.execute("synthetic-session", target)
        assertEquals(TaskStatus.ALREADY, result.status)
        assertTrue(result.message.contains("自检通过"))
        assertEquals(0, fixture.writes)
        assertEquals(1, fixture.reads)
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

    @Test fun changedWebIdentityStopsBeforeTrustingOtherAccountsSignState() {
        for (changeAfterWrite in listOf(false, true)) {
            val fixture = Fixture(listOf(guide(0), guide(1)))
            fixture.identity = { call -> JSONObject().put("no", 0).put("data", JSONObject().put("id", if (changeAfterWrite && call == 1) "42" else "99")) }
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
        assertEquals(TaskStatus.ALREADY, fixture.adapter.query("synthetic-session", target)?.status)
        assertEquals(0, fixture.writes)
        assertTrue(fixture.adapter.requiresFreshCompletionCheck(target))
        assertFalse(fixture.adapter.requiresFreshCompletionCheck(target.copy(bound = false)))
        assertFalse(fixture.adapter.requiresFreshCompletionCheck(target.copy(type = "daily-like")))
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
        val adapter = TiebaAdapter(null, this) { waits += it }
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
                assertEquals("synthetic-session", fields["bdusstoken"])
                JSONObject("""{"error_code":0,"user":{"id":"42","name":"合成账号"},"anti":{"tbs":"0123456789abcdef0123456789"}}""")
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
