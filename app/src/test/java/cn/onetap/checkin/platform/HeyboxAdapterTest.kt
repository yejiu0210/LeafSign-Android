package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only. No credentials, account access, or platform writes. */
class HeyboxAdapterTest {
    private val cookie = "heybox_id=123; pkey=synthetic-session; x_xhh_tokenid=synthetic-official-token"
    private fun identity(uid: Int = 123) = JSONObject("""{"status":"ok","result":{"pkey":"synthetic-server-session","profile":{"heybox_id":"$uid","nickname":"测试用户"},"account_detail":{"userid":"$uid"}}}""")
    private fun task(state: String) = JSONObject("""{"status":"ok","result":{"task_list":[{"tasks":[{"type":"sign","title":"签到","state":"$state"}]}]}}""")
    private fun target() = Target(HeyboxAdapter.TARGET_ID, HeyboxAdapter.TARGET_TYPE, "每日签到", metadata = mapOf("ownerUserId" to "123"))
    private class MemoryStore(var state: AppState) : StateStore {
        override fun read() = state
        override fun update(change: (AppState) -> AppState): AppState { state = change(state); return state }
    }
    private fun account(id: String = "main", uid: String = "123", platform: Platform = Platform.HEYBOX, credential: String = cookie) =
        Account(id = id, platform = platform, userId = uid, displayName = "测试用户", note = "", credential = credential,
            targets = listOf(target()), enabled = setOf(HeyboxAdapter.TARGET_ID))

    @Test fun webSignatureMatchesCurrentOfficialJavaScriptVectors() {
        // Independently executed today's downloaded official Tr/zM.g with Node crypto MD5.
        assertEquals("YZP3V05", HeyboxWebSigning.sign("/task/list_v2/", 1792200000, "0123456789ABCDEF0123456789ABCDEF"))
        assertEquals("D7YIU46", HeyboxWebSigning.sign("/account/restore_login", 1792200000, "FEDCBA9876543210FEDCBA9876543210"))
        assertEquals("DDWS724", HeyboxWebSigning.sign("/task/sign_v3/sign", 1792200000, "00000000000000000000000000000000"))
        assertEquals(HeyboxWebSigning.sign("/task/list_v2/", 1792200000, "ABC"),
            HeyboxWebSigning.sign("//task///list_v2", 1792200000, "ABC"))
    }

    @Test fun requestParametersUseFreshNonceWithoutPretendingToBeMobileDevice() {
        val one = HeyboxWebSigning.parameters("/task/list_v2/", 1792200000123)
        val two = HeyboxWebSigning.parameters("/task/list_v2/", 1792200000123)
        assertEquals(setOf("version", "_time", "nonce", "hkey"), one.keys)
        assertEquals("1792200000", one["_time"])
        assertTrue(Regex("[0-9A-F]{32}").matches(one.getValue("nonce")))
        assertNotEquals(one["nonce"], two["nonce"])
        assertEquals(HeyboxWebSigning.sign("/task/list_v2/", 1792200000, one.getValue("nonce")), one["hkey"])
    }

    @Test fun credentialsOnlyForwardExactPlatformCookieNames() {
        val parsed = HeyboxAdapter().credentials("$cookie; MUSIC_U=foreign-secret; BDUSS=foreign-secret; SESSDATA=foreign-secret")
        assertEquals(HeyboxAdapter.COOKIE_NAMES, parsed.keys)
        assertEquals("synthetic-session", parsed["user_pkey"])
        assertEquals("123", parsed["user_heybox_id"])
    }

    @Test fun missingAndConflictingCookieAliasesAreRejectedWithoutEchoingSecrets() {
        for (raw in listOf("pkey=synthetic-secret", "heybox_id=123", "heybox_id=123; pkey=synthetic-secret; user_pkey=different",
            "heybox_id=123; user_heybox_id=456; pkey=synthetic-secret", "heybox_id=not-an-id; pkey=synthetic-secret",
            "heybox_id=123; pkey=synthetic-secret\r\nHeader: secret", "heybox_id=123; pkey=first; pkey=synthetic-secret")) {
            try { HeyboxAdapter().credentials(raw); fail("Invalid Cookie must fail") }
            catch (error: AdapterException) {
                assertEquals(TaskStatus.EXPIRED, error.status)
                assertFalse(error.message.contains("synthetic-secret"))
                assertFalse(error.diagnostic.contains("synthetic-secret"))
            }
        }
    }

    @Test fun validatesServerIdentityAndCreatesOnlyOrdinaryDailySign() {
        val adapter = HeyboxAdapter { path, params, cookies ->
            assertEquals(HeyboxAdapter.IDENTITY_PATH, path)
            assertEquals("web", params["os_type"])
            assertFalse(params.containsKey("imei"))
            assertFalse(params.containsKey("device_info"))
            assertEquals("synthetic-session", cookies["pkey"])
            assertFalse("Official web restore_login authenticates by Cookie, not a credential URL", params.containsKey("pkey"))
            assertFalse(params.containsKey("user_pkey"))
            identity()
        }
        val actual = adapter.validate(cookie)
        assertEquals("123", actual.userId)
        assertEquals("测试用户", actual.displayName)
        assertEquals(1, actual.targets.size)
        assertEquals("heybox:daily", actual.targets.single().id)
        assertEquals("123", actual.targets.single().metadata["ownerUserId"])
        assertEquals("false", actual.targets.single().metadata["resetVerified"])
        assertEquals("synthetic-server-session", adapter.credentials(actual.updatedCredential!!)["pkey"])
        assertEquals("synthetic-server-session", adapter.credentials(actual.updatedCredential!!)["user_pkey"])
    }

    @Test fun cookieIdentityIsNeverAcceptedWithoutServerProof() {
        for (response in listOf(JSONObject("""{"status":"ok"}"""),
            JSONObject("""{"status":"ok","result":{"pkey":"synthetic"}}"""),
            JSONObject("""{"status":"ok","result":{"profile":{"heybox_id":"123"}}}"""),
            JSONObject("""{"status":"ok","result":{"pkey":"synthetic","profile":{"heybox_id":"123"},"account_detail":{"userid":"456"}}}"""))) {
            val adapter = HeyboxAdapter { _, _, _ -> response }
            try { adapter.validate(cookie); fail("Missing or conflicting server proof must fail") }
            catch (error: AdapterException) { assertEquals(TaskStatus.UNKNOWN, error.status) }
        }
        try { HeyboxAdapter { _, _, _ -> identity(456) }.validate(cookie); fail("Different account must fail") }
        catch (error: AdapterException) { assertEquals(TaskStatus.EXPIRED, error.status) }
    }

    @Test fun finishedSignTaskIsTheOnlyCompletionEvidence() {
        assertEquals(TaskStatus.ALREADY, HeyboxAdapter.parseTaskState(task("finish")).status)
        assertEquals(TaskStatus.PENDING, HeyboxAdapter.parseTaskState(task("todo")).status)
        for (raw in listOf("{}", """{"status":"ok"}""", """{"status":"ok","result":{"task_list":[]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"type":"share","state":"finish"}]}]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"title":"签到","state":"finish"}]}]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"type":"sign","state":"finish"},{"type":"sign","state":"todo"}]}]}}""",
            """{"status":"ok","result":{"task_list":[{"tasks":[{"type":"sign","state":true}]}]}}""")) {
            assertEquals(raw, TaskStatus.UNKNOWN, HeyboxAdapter.parseTaskState(JSONObject(raw)).status)
        }
        assertEquals(TaskStatus.UNKNOWN, HeyboxAdapter.parseTaskState(task("new-state")).status)
    }

    @Test fun officialAuthenticationAndVerificationStatesStopExecution() {
        for (status in listOf("login", "relogin"))
            assertEquals(TaskStatus.EXPIRED, HeyboxAdapter.parseTaskState(JSONObject().put("status", status)).status)
        for (status in listOf("need_google_check", "show_captcha", "lack_token", "name_verify", "need_bind_phone"))
            assertEquals(TaskStatus.MANUAL, HeyboxAdapter.parseTaskState(JSONObject().put("status", status)).status)
        val result = HeyboxAdapter.parseTaskState(JSONObject("""{"status":"unexpected-synthetic-secret","msg":"synthetic-secret"}"""))
        assertEquals(TaskStatus.UNKNOWN, result.status)
        assertFalse(result.message.contains("synthetic-secret"))
        assertFalse(result.diagnostic.contains("synthetic-secret"))
    }

    @Test fun completedTaskAndUnknownTaskNeverTriggerSignWrite() {
        for (state in listOf(task("finish"), task("unknown"))) {
            val paths = mutableListOf<String>()
            val adapter = HeyboxAdapter { path, _, _ ->
                paths += path
                when (path) { HeyboxAdapter.IDENTITY_PATH -> identity(); HeyboxAdapter.TASK_PATH -> state; else -> error("Unexpected write") }
            }
            val expected = if (state.toString().contains("finish")) TaskStatus.ALREADY else TaskStatus.UNKNOWN
            assertEquals(expected, adapter.execute(cookie, target()).status)
            assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH, HeyboxAdapter.TASK_PATH), paths)
        }
    }

    @Test fun executesOnlyDailySignThenConfirmsTaskCompletion() {
        val paths = mutableListOf<String>()
        var reads = 0
        val adapter = HeyboxAdapter { path, _, _ ->
            paths += path
            when (path) {
                HeyboxAdapter.IDENTITY_PATH -> identity()
                HeyboxAdapter.TASK_PATH -> task(if (++reads == 1) "todo" else "finish")
                HeyboxAdapter.SIGN_PATH -> JSONObject("""{"status":"ok","result":{"state":"success"}}""")
                else -> error("Unexpected endpoint")
            }
        }
        assertEquals(TaskStatus.SUCCESS, adapter.execute(cookie, target()).status)
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH, HeyboxAdapter.TASK_PATH, HeyboxAdapter.SIGN_PATH, HeyboxAdapter.TASK_PATH), paths)
    }

    @Test fun writeOkWithoutTaskConfirmationRemainsUnknown() {
        val adapter = HeyboxAdapter { path, _, _ ->
            when (path) {
                HeyboxAdapter.IDENTITY_PATH -> identity()
                HeyboxAdapter.TASK_PATH -> task("todo")
                HeyboxAdapter.SIGN_PATH -> JSONObject("""{"status":"ok","result":{"state":"success"}}""")
                else -> error("Unexpected endpoint")
            }
        }
        assertEquals(TaskStatus.UNKNOWN, adapter.execute(cookie, target()).status)
    }

    @Test fun unrecognizedWriteWithConfirmedTaskOnlyReportsCurrentCompletion() {
        var reads = 0
        val adapter = HeyboxAdapter { path, _, _ ->
            when (path) {
                HeyboxAdapter.IDENTITY_PATH -> identity()
                HeyboxAdapter.TASK_PATH -> task(if (++reads == 1) "todo" else "finish")
                else -> JSONObject("""{"status":"unknown"}""")
            }
        }
        assertEquals(TaskStatus.ALREADY, adapter.execute(cookie, target()).status)
    }

    @Test fun ignoredFailedAndMissingWriteStatesNeverBecomeNewSuccess() {
        for (state in listOf("ignore", "fail", "unknown", null)) {
            var reads = 0
            val adapter = HeyboxAdapter { path, _, _ ->
                when (path) {
                    HeyboxAdapter.IDENTITY_PATH -> identity()
                    HeyboxAdapter.TASK_PATH -> task(if (++reads == 1) "todo" else "finish")
                    else -> JSONObject().put("status", "ok").put("result", JSONObject().apply { if (state != null) put("state", state) })
                }
            }
            val result = adapter.execute(cookie, target())
            assertEquals("write=$state", TaskStatus.ALREADY, result.status)
            assertTrue(result.message.contains("本次写入结果未单独确认"))
        }
    }

    @Test fun queryAndEverySignOperationUseTheRefreshedServerPkey() {
        for (execute in listOf(false, true)) {
            var reads = 0
            val adapter = HeyboxAdapter { path, _, cookies ->
                if (path == HeyboxAdapter.IDENTITY_PATH) {
                    assertEquals("synthetic-session", cookies["pkey"])
                    identity()
                } else {
                    assertEquals("synthetic-server-session", cookies["pkey"])
                    assertEquals("synthetic-server-session", cookies["user_pkey"])
                    if (path == HeyboxAdapter.TASK_PATH) task(if (++reads == 1) "todo" else "finish")
                    else JSONObject("""{"status":"ok","result":{"state":"success"}}""")
                }
            }
            assertEquals(if (execute) TaskStatus.SUCCESS else TaskStatus.PENDING,
                if (execute) adapter.execute(cookie, target()).status else adapter.query(cookie, target()).status)
        }
    }

    @Test fun refreshPersistsOnlyExactExistingPlatformAndAccountCredential() {
        val main = account()
        val otherAccount = account(id = "other", uid = "456")
        val otherPlatform = account(id = "tieba", platform = Platform.TIEBA)
        val store = MemoryStore(AppState(accounts = listOf(main, otherAccount, otherPlatform)))
        val adapter = HeyboxAdapter(store) { _, _, _ -> identity() }
        val result = adapter.validate(cookie)
        assertEquals(result.updatedCredential, store.read().accounts.first { it.id == "main" }.credential)
        assertEquals(cookie, store.read().accounts.first { it.id == "other" }.credential)
        assertEquals(cookie, store.read().accounts.first { it.id == "tieba" }.credential)
    }

    @Test fun refreshedSessionUsesTheOfficialEscapedCookieRepresentation() {
        val rawSession = "synthetic-session=+/%:@_-."
        val wireSession = "synthetic-session%3D+/%25%3A@_-."
        val store = MemoryStore(AppState(accounts = listOf(account())))
        val adapter = HeyboxAdapter(store) { path, params, cookies ->
            assertFalse(params.containsKey("pkey"))
            assertFalse(params.containsKey("user_pkey"))
            if (path == HeyboxAdapter.IDENTITY_PATH) identity().also { it.getJSONObject("result").put("pkey", rawSession) }
            else {
                assertEquals(wireSession, cookies["pkey"])
                assertEquals(wireSession, cookies["user_pkey"])
                task("finish")
            }
        }
        assertEquals(TaskStatus.ALREADY, adapter.query(cookie, target()).status)
        assertEquals(wireSession, adapter.credentials(store.read().accounts.single().credential)["pkey"])
    }

    @Test fun importedCookieValuesRemainEncodedOnceAndPreservePlusSigns() {
        val encodedSession = "synthetic%3D+/%25%3A"
        val imported = "user_heybox_id=123; user_pkey=$encodedSession; x_xhh_tokenid=synthetic%2Btoken"
        val adapter = HeyboxAdapter { path, params, cookies ->
            assertEquals(HeyboxAdapter.IDENTITY_PATH, path)
            assertFalse(params.containsKey("pkey"))
            assertEquals(encodedSession, cookies["pkey"])
            assertEquals(encodedSession, cookies["user_pkey"])
            assertEquals("synthetic%2Btoken", cookies["x_xhh_tokenid"])
            identity()
        }
        assertEquals("123", adapter.validate(imported).userId)
    }

    @Test fun refreshDoesNotOverwriteUserCredentialChangedDuringRequest() {
        val store = MemoryStore(AppState(accounts = listOf(account())))
        val replacement = "heybox_id=123; pkey=synthetic-user-replacement"
        val adapter = HeyboxAdapter(store) { _, _, _ ->
            store.update { it.copy(accounts = it.accounts.map { a -> a.copy(credential = replacement) }) }
            identity()
        }
        assertNotNull(adapter.validate(cookie).updatedCredential)
        assertEquals(replacement, store.read().accounts.single().credential)
    }

    @Test fun importedReplacementReturnsRefreshWithoutMutatingOldStoredCredential() {
        val store = MemoryStore(AppState(accounts = listOf(account())))
        val adapter = HeyboxAdapter(store) { _, _, _ -> identity() }
        val imported = "heybox_id=123; pkey=synthetic-imported-session"
        val result = adapter.validate(imported)
        assertEquals("synthetic-server-session", adapter.credentials(result.updatedCredential!!)["pkey"])
        assertEquals(cookie, store.read().accounts.single().credential)
    }

    @Test fun loginAndCaptchaDuringWriteDoNotRetryOrContinueOtherWrites() {
        for ((status, expected) in listOf("login" to TaskStatus.EXPIRED, "show_captcha" to TaskStatus.MANUAL)) {
            val paths = mutableListOf<String>()
            val adapter = HeyboxAdapter { path, _, _ ->
                paths += path
                when (path) {
                    HeyboxAdapter.IDENTITY_PATH -> identity()
                    HeyboxAdapter.TASK_PATH -> task("todo")
                    else -> JSONObject().put("status", status)
                }
            }
            try { adapter.execute(cookie, target()); fail("Authentication interruption must stop") }
            catch (error: AdapterException) { assertEquals(expected, error.status) }
            assertEquals(1, paths.count { it == HeyboxAdapter.SIGN_PATH })
            assertEquals(1, paths.count { it == HeyboxAdapter.TASK_PATH })
        }
    }

    @Test fun queryOnlyReadsAndDoesNotWriteOrConsumeAnyResource() {
        val paths = mutableListOf<String>()
        val adapter = HeyboxAdapter { path, _, _ ->
            paths += path
            if (path == HeyboxAdapter.IDENTITY_PATH) identity() else task("todo")
        }
        assertEquals(TaskStatus.PENDING, adapter.query(cookie, target()).status)
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH, HeyboxAdapter.TASK_PATH), paths)
    }

    @Test fun wrongTargetOrChangedAccountStopsBeforeTaskWrite() {
        val paths = mutableListOf<String>()
        val adapter = HeyboxAdapter { path, _, _ -> paths += path; identity() }
        assertEquals(TaskStatus.UNKNOWN, adapter.execute(cookie, target().copy(id = "another-task")).status)
        assertTrue(paths.isEmpty())
        assertEquals(TaskStatus.EXPIRED, adapter.execute(cookie, target().copy(metadata = mapOf("ownerUserId" to "456"))).status)
        assertEquals(listOf(HeyboxAdapter.IDENTITY_PATH), paths)
    }
}
