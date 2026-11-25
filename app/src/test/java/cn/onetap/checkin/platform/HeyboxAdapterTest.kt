package cn.onetap.checkin.platform

import cn.onetap.checkin.core.Account
import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.AppState
import cn.onetap.checkin.core.HttpStatusException
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.Runner
import cn.onetap.checkin.core.StateStore
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskResult
import cn.onetap.checkin.core.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HeyboxAdapterTest {
    private val cookie = "heybox_id=123; pkey=fixture-session; x_xhh_tokenid=fixture-token"
    private val target = Target(HeyboxAdapter.TARGET_ID, HeyboxAdapter.TARGET_TYPE, "每日签到",
        metadata = mapOf("ownerUserId" to "123"))

    private fun identity(pkey: String = "fixture-refreshed", uid: String = "123") = JSONObject("""
        {"status":"ok","result":{"pkey":"$pkey","profile":{"heybox_id":"$uid","nickname":"测试用户"},"account_detail":{"userid":"$uid"}}}
    """.trimIndent())

    private fun task(state: String) =
        JSONObject("""{"status":"ok","result":{"task_list":[{"tasks":[{"type":"sign","state":"$state"}]}]}}""")

    private fun rejected(status: String) = JSONObject().put("status", status)
    private fun store() = MemoryStore(AppState(accounts = listOf(Account(
        id = "account", platform = Platform.HEYBOX, userId = "123", displayName = "测试用户",
        note = "", credential = cookie, targets = listOf(target), enabled = setOf(target.id)
    ))))

    private class MemoryStore(private var state: AppState) : StateStore {
        @Synchronized override fun read() = state
        @Synchronized override fun update(change: (AppState) -> AppState): AppState {
            state = change(state)
            return state
        }
    }

    @Test fun webSignatureMatchesHistoricalOfficialJavaScriptVectors() {
        assertEquals("YZP3V05", HeyboxWebSigning.sign(HeyboxAdapter.TASK_PATH, 1792200000, "0123456789ABCDEF0123456789ABCDEF"))
        assertEquals("D7YIU46", HeyboxWebSigning.sign(HeyboxAdapter.IDENTITY_PATH, 1792200000, "FEDCBA9876543210FEDCBA9876543210"))
        assertEquals(HeyboxWebSigning.sign(HeyboxAdapter.TASK_PATH, 1792200000, "ABC"),
            HeyboxWebSigning.sign("//task///list_v2", 1792200000, "ABC"))
    }

    @Test fun signerUsesFreshNonceAndSecondsTimestamp() {
        val params = HeyboxWebSigning.parameters(HeyboxAdapter.TASK_PATH, 1792200000123)
        assertEquals(setOf("version", "_time", "nonce", "hkey"), params.keys)
        assertEquals("1792200000", params["_time"])
        val nonce = params.getValue("nonce")
        assertTrue(Regex("[0-9A-F]{32}").matches(nonce))
        assertNotEquals(nonce, HeyboxWebSigning.parameters(HeyboxAdapter.TASK_PATH, 1792200000123)["nonce"])
        assertEquals(HeyboxWebSigning.sign(HeyboxAdapter.TASK_PATH, 1792200000, nonce), params["hkey"])
    }

    @Test fun validatesWithCookieAndEscapesOnlyServerIssuedPkey() {
        val imported = "fixture%3D+/%25%3A"
        val adapter = HeyboxAdapter { path, params, cookies ->
            assertEquals(HeyboxAdapter.IDENTITY_PATH, path)
            assertEquals("web", params["app"])
            assertEquals("web", params["os_type"])
            assertTrue(params.keys.intersect(setOf("pkey", "user_pkey", "imei")).isEmpty())
            assertEquals(HeyboxAdapter.COOKIE_NAMES, cookies.keys)
            assertEquals(imported, cookies["pkey"])
            assertEquals(imported, cookies["user_pkey"])
            assertEquals("fixture%2Btoken", cookies["x_xhh_tokenid"])
            identity("fixture=+/%:@_-.")
        }
        val validated = adapter.validate("user_heybox_id=123; user_pkey=$imported; x_xhh_tokenid=fixture%2Btoken; BDUSS=unrelated")
        assertEquals("测试用户", validated.displayName)
        assertEquals(target.id, validated.targets.single().id)
        assertEquals("123", validated.targets.single().metadata["ownerUserId"])
        val refreshed = adapter.credentials(validated.updatedCredential!!)
        assertEquals("fixture%3D+/%25%3A@_-.", refreshed["pkey"])
        assertEquals(refreshed["pkey"], refreshed["user_pkey"])
    }

    @Test fun rejectsMissingConflictingAndMalformedCookiesWithoutSendingRequests() {
        val adapter = HeyboxAdapter { _, _, _ -> error("Invalid Cookie reached transport") }
        val malformed = listOf("pkey=fixture-secret", "heybox_id=123", "heybox_id=0; pkey=fixture-secret",
            "heybox_id=123; user_heybox_id=456; pkey=fixture-secret",
            "heybox_id=123; pkey=fixture-secret; user_pkey=conflict",
            "heybox_id=123; pkey=fixture-secret; pkey=conflict", "heybox_id=123; pkey=fixture-secret\r\n")
        for (raw in malformed) {
            val error = assertThrows(AdapterException::class.java) { adapter.validate(raw) }
            assertEquals(TaskStatus.EXPIRED, error.status)
            assertFalse(error.toString().contains("fixture-secret"))
        }
    }

    @Test fun requiresServerIdentityAndPkeyToValidate() {
        for (response in listOf(JSONObject("""{"status":"ok"}"""),
            JSONObject("""{"status":"ok","result":{"pkey":"fixture"}}"""),
            JSONObject("""{"status":"ok","result":{"profile":{"heybox_id":"123"}}}"""),
            identity().apply { getJSONObject("result").getJSONObject("account_detail").put("userid", "456") })) {
            val adapter = HeyboxAdapter { _, _, _ -> response }
            assertEquals(TaskStatus.UNKNOWN, assertThrows(AdapterException::class.java) { adapter.validate(cookie) }.status)
        }
        val otherAccount = HeyboxAdapter { _, _, _ -> identity(uid = "456") }
        assertEquals(TaskStatus.EXPIRED, assertThrows(AdapterException::class.java) { otherAccount.validate(cookie) }.status)
    }

    @Test fun runnerRefreshesOnceAndPersistsNewPkeyBeforeAnyTaskRequest() = runBlocking {
        val store = store()
        val paths = mutableListOf<String>()
        var serverPkey = "fixture-session"
        var rotations = 0
        var signed = false
        val adapter = HeyboxAdapter { path, params, cookies ->
            paths += path
            assertEquals(serverPkey, cookies["pkey"])
            assertEquals(serverPkey, cookies["user_pkey"])
            assertEquals("123", params["heybox_id"])
            assertEquals("web", params["app"])
            assertEquals("2.5", params["web_version"])
            assertEquals("999.0.4", params["version"])
            assertEquals("Windows", params["x_os_type"])
            assertEquals("Chrome", params["device_info"])
            assertEquals("", params["device_id"])
            assertEquals(HeyboxWebSigning.sign(path, params.getValue("_time").toLong(), params.getValue("nonce")), params["hkey"])
            if (path == HeyboxAdapter.IDENTITY_PATH) {
                serverPkey = "fixture-rotated-${++rotations}"
                identity(serverPkey)
            } else {
                // Check persistence inside the first task request, before it can use the new session.
                assertTrue(store.read().accounts.single().credential.contains("pkey=$serverPkey;"))
                assertEquals(HeyboxAdapter.TASK_PATH, path)
                task(if (signed) "finish" else "todo")
            }
        }
        val runner = Runner(store, mapOf(adapter.platform to adapter))
        assertEquals(0, runner.run())
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH, HeyboxAdapter.TASK_PATH, HeyboxAdapter.TASK_PATH), paths)
        assertEquals(1, rotations)
        val first = store.read().records.single()
        assertEquals(TaskStatus.MANUAL, first.status)
        assertEquals("打开小黑盒 App 即可签到", first.message)
        assertEquals("heybox open app required", first.diagnostic)

        paths.clear()
        signed = true
        assertEquals(1, runner.run())
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH, HeyboxAdapter.TASK_PATH), paths)
        assertEquals(2, rotations)
        assertEquals(TaskStatus.ALREADY, store.read().records.last().status)
        assertEquals(serverPkey, adapter.credentials(store.read().accounts.single().credential)["pkey"])
    }

    @Test fun runnerDoesNotRetryRestoreLoginWhenItsResponseIsLost() = runBlocking {
        val store = store()
        val paths = mutableListOf<String>()
        val adapter = HeyboxAdapter { path, _, _ ->
            paths += path
            throw IOException("fixture credential should not reach results")
        }
        assertEquals(0, Runner(store, mapOf(adapter.platform to adapter)).run())
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH), paths)
        assertEquals(TaskStatus.UNKNOWN, store.read().records.single().status)
        assertEquals("结果未确认，请稍后重试", store.read().records.single().message)
        assertEquals(cookie, store.read().accounts.single().credential)
    }

    @Test fun runnerRecordsExpiredAndSkipsAllTasksOnLoginRejection() = runBlocking {
        for (status in listOf("login", "relogin")) {
            val store = store()
            val adapter = HeyboxAdapter { path, _, _ ->
                assertEquals(HeyboxAdapter.IDENTITY_PATH, path)
                rejected(status)
            }
            assertEquals(0, Runner(store, mapOf(adapter.platform to adapter)).run())
            val result = store.read().records.single()
            assertEquals(TaskStatus.EXPIRED, result.status)
            assertEquals("登录已失效，请重新扫码登录", result.message)
        }
    }

    @Test fun queryOnlyReadsTheTaskAndChecksOwnerBeforeSendingCredentials() {
        val paths = mutableListOf<String>()
        val adapter = HeyboxAdapter { path, _, _ -> paths += path; task("todo") }
        assertEquals(TaskStatus.PENDING, adapter.query(cookie, target).status)
        assertEquals(TaskStatus.UNKNOWN, adapter.execute(cookie, target.copy(id = "another-task")).status)
        assertEquals(TaskStatus.EXPIRED, assertThrows(AdapterException::class.java) {
            adapter.execute(cookie, target.copy(metadata = mapOf("ownerUserId" to "456")))
        }.status)
        assertEquals(listOf(HeyboxAdapter.TASK_PATH), paths)
    }

    @Test fun executeOnlyReadsTheTaskList() {
        val cases = listOf(
            task("todo") to TaskResult(TaskStatus.MANUAL, "打开小黑盒 App 即可签到", "heybox open app required"),
            task("finish") to TaskResult(TaskStatus.ALREADY, "今日已签到"),
            task("unknown") to TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "heybox missing or unrecognized sign state"),
            rejected("login") to TaskResult(TaskStatus.EXPIRED, "登录已失效，请重新扫码登录", "heybox auth required"),
            rejected("relogin") to TaskResult(TaskStatus.EXPIRED, "登录已失效，请重新扫码登录", "heybox auth required"),
            rejected("show_captcha") to TaskResult(TaskStatus.MANUAL, "请在小黑盒完成验证后重试", "heybox manual verification required")
        )
        for ((response, expected) in cases) {
            val paths = mutableListOf<String>()
            val adapter = HeyboxAdapter { path, _, _ ->
                paths += path
                response
            }
            assertEquals(expected, adapter.execute(cookie, target))
            assertEquals(listOf(HeyboxAdapter.TASK_PATH), paths)
        }
    }

    @Test fun missingAndAmbiguousSignTasksRemainUnknown() {
        val invalid = listOf("{}", """{"status":"ok","result":{"task_list":[]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"type":"share","state":"finish"}]}]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"type":"sign","state":"finish"},{"type":"sign","state":"todo"}]}]}}""")
        for (raw in invalid) assertEquals(TaskStatus.UNKNOWN, HeyboxAdapter.parseTaskState(JSONObject(raw)).status)
    }

    @Test fun taskListHttpAuthenticationAndUnknownRedirectKeepTheirClassification() {
        val unauthorized = HeyboxAdapter { path, _, _ ->
            assertEquals(HeyboxAdapter.TASK_PATH, path)
            throw HttpStatusException(401, TaskStatus.EXPIRED, "登录已失效")
        }
        val authError = assertThrows(AdapterException::class.java) { unauthorized.execute(cookie, target) }
        assertEquals(TaskStatus.EXPIRED, authError.status)
        assertEquals("登录已失效，请重新扫码登录", authError.message)
        assertEquals("HTTP 401", authError.diagnostic)

        val redirected = HeyboxAdapter { path, _, _ ->
            assertEquals(HeyboxAdapter.TASK_PATH, path)
            throw HttpStatusException(302, TaskStatus.UNKNOWN, "结果未确认，请稍后重试")
        }
        val redirectError = assertThrows(AdapterException::class.java) { redirected.execute(cookie, target) }
        assertEquals(TaskStatus.UNKNOWN, redirectError.status)
        assertEquals("HTTP 302", redirectError.diagnostic)
    }
}
