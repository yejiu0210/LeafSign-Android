package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.AppState
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.Rules
import cn.onetap.checkin.platform.HeyboxAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeyboxQrProviderTest {
    private val qr = "00000000-0000-4000-8000-000000000001"
    private val scanUrl = "https://api.xiaoheihe.cn/account/qr_login/?qr=$qr&app=web"
    private val anonymous = response("""{"status":"login","msg":"请登录后使用该功能","result":{}}""")
    private val missingCredential = QrPoll.Failed("小黑盒未返回登录凭据，请重新扫码")

    private fun startReply(url: String = scanUrl) =
        response("""{"status":"ok","msg":"","version":"1.0","result":{"qr_url":"$url","expire":120}}""")

    private fun stateReply(state: String, vararg cookies: String, message: String = "", fields: JSONObject = JSONObject()) =
        okReply(fields.put("error_msg", message).put("error", state), *cookies)

    private fun okReply(fields: JSONObject, vararg cookies: String) =
        response(JSONObject().put("status", "ok").put("msg", "").put("version", "1.0").put("result", fields).toString(), *cookies)

    @Test fun generationUsesTheExactScanUrlAndServerExpiryWithoutSigningOrCookies() {
        val rawUrl = "https://api.xiaoheihe.cn:443/account/qr_login/?qr=$qr&app=web"
        val transport = ScriptTransport(startReply(rawUrl))
        val ticket = HeyboxQrProvider(transport) { TEST_NOW }.start()
        val request = transport.requests.single()
        assertGet(request, "/account/get_qrcode_url/", setOf("app"))
        assertNull(request.headers["Cookie"])
        assertEquals(Platform.HEYBOX, ticket.platform)
        assertEquals(rawUrl, ticket.content)
        assertEquals(qr, ticket.state["qr"])
        assertEquals(TEST_NOW + 120_000, ticket.expiresAt)
        assertNull(ticket.image)
        assertFalse(ticket.toString().contains(qr))
    }

    @Test fun generationRejectsMissingQrAndUntrustedUrls() {
        val missing = listOf("https://api.xiaoheihe.cn/account/qr_login/?app=web",
            "https://api.xiaoheihe.cn/account/qr_login/?qr=",
            "https://api.xiaoheihe.cn/account/qr_login/?qr=$qr&qr=$qr")
        for (url in missing) {
            val provider = HeyboxQrProvider(ScriptTransport(startReply(url))) { TEST_NOW }
            val error = assertThrows(QrLoginException::class.java) { provider.start() }
            assertEquals("二维码参数缺失", error.message)
        }
        val invalid = listOf("http://api.xiaoheihe.cn/account/qr_login/?qr=$qr",
            "https://api.xiaoheihe.cn.example.test/account/qr_login/?qr=$qr",
            "https://user@api.xiaoheihe.cn/account/qr_login/?qr=$qr",
            "https://api.xiaoheihe.cn:444/account/qr_login/?qr=$qr",
            "https://api.xiaoheihe.cn/account/other/?qr=$qr")
        for (url in invalid) {
            val provider = HeyboxQrProvider(ScriptTransport(startReply(url))) { TEST_NOW }
            val error = assertThrows(QrLoginException::class.java) { provider.start() }
            assertEquals("小黑盒二维码地址不受信任", error.message)
        }
    }

    @Test fun pollingUsesTheDecodedQrAndMapsTheObservedWaitingAndExpiredResponses() {
        val ticketUrl = "https://api.xiaoheihe.cn/account/qr_login/?qr=fixture%2B%26%3D&app=web"
        val waiting = response("""{"status":"ok","msg":"","version":"1.0","result":{"error_msg":"","error":"wait"}}""")
        val expired = response("""{"status":"ok","msg":"","version":"1.0","result":{"error_msg":"二维码已过期","error":"cancel"}}""")
        val transport = ScriptTransport(startReply(ticketUrl), waiting, stateReply("ready"), expired)
        val provider = HeyboxQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        val result = provider.poll(ticket)
        assertEquals(QrPoll.Expired("二维码已过期"), result)
        assertSame(result, provider.poll(ticket))
        assertEquals(4, transport.requests.size)
        for (request in transport.requests.drop(1)) {
            assertGet(request, "/account/qr_state/", setOf("qr", "app"))
            assertEquals("fixture+&=", request.url.toHttpUrl().queryParameter("qr"))
            assertNull(request.headers["Cookie"])
        }
    }

    @Test fun everyOtherInnerStatusExpiresWithTheServerMessageOrDefault() {
        for ((state, message, expected) in listOf(
            Triple("new_state", "请重新生成二维码", "请重新生成二维码"),
            Triple("cancel", "扫码登录已取消", "扫码登录已取消"),
            Triple("timeout", "", "二维码已过期"),
            Triple("", "", "二维码已过期")
        )) {
            val provider = HeyboxQrProvider(ScriptTransport(startReply(), stateReply(state, message = message))) { TEST_NOW }
            assertEquals(QrPoll.Expired(expected), provider.poll(provider.start()))
        }
    }

    @Test fun outerRejectionsShowTwoFactorOrTheServerMessageAndNeverConfirm() {
        val cases = listOf(
            Triple("need_google_check", "服务端提示", "账号开启了二次验证，请先在小黑盒 App 里关闭或改用手动导入"),
            Triple("failed", "请求失败了", "请求失败了"),
            Triple("failed", "", "二维码状态查询失败")
        )
        for ((status, message, expected) in cases) {
            val poll = response("""{"status":"$status","msg":"$message","result":{"error":"ok"}}""", "pkey=fixture", "heybox_id=123")
            val transport = ScriptTransport(startReply(), poll)
            val provider = HeyboxQrProvider(transport) { TEST_NOW }
            assertEquals(QrPoll.Failed(expected), provider.poll(provider.start()))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun completeQrResultTakesPriorityOverSetCookieAndCanBeValidatedAndSaved() {
        val fields = JSONObject().put("pkey", "fixture=+%")
            .put("profile", JSONObject().put("heybox_id", 123))
            .put("account_detail", JSONObject().put("userid", 456))
        val transport = ScriptTransport(startReply(), stateReply("ok", "pkey=old-session", "heybox_id=456", fields = fields))
        val provider = HeyboxQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        val confirmed = provider.poll(ticket) as QrPoll.Confirmed
        val adapter = HeyboxAdapter { path, params, cookies ->
            assertEquals(HeyboxAdapter.IDENTITY_PATH, path)
            assertEquals("web", params["app"])
            assertFalse(params.containsKey("hkey"))
            assertEquals("123", cookies["heybox_id"])
            assertEquals("fixture%3D+%25", cookies["pkey"])
            JSONObject("""{"status":"ok","result":{"pkey":"fixture-rotated","account_detail":{"userid":123}}}""")
        }
        val identity = adapter.validate(confirmed.credential)
        val saved = Rules.addOrUpdate(AppState(), identity, Platform.HEYBOX, confirmed.credential, "", null)
        assertEquals("fixture-rotated", adapter.credentials(saved.accounts.single().credential)["pkey"])
        assertSame(confirmed, provider.poll(ticket))
        assertEquals(2, transport.requests.size)
    }

    @Test fun qrResultUsesAccountDetailWhenThereIsNoProfileId() {
        val fields = JSONObject().put("pkey", "fixture").put("account_detail", JSONObject().put("userid", "123"))
        val transport = ScriptTransport(startReply(), stateReply("ok", fields = fields))
        val provider = HeyboxQrProvider(transport) { TEST_NOW }
        assertCredential("123", "fixture", provider.poll(provider.start()))
        assertEquals(2, transport.requests.size)
    }

    @Test fun incompleteQrResultRestoresOnceWithOnlyTicketCookiesAndPrefersTheNewPkey() {
        val waiting = stateReply("wait", "user_pkey=fixture%3D+%25; HttpOnly", "other=drop", "x_xhh_tokenid=drop")
        val confirmed = stateReply("ok", "user_heybox_id=123")
        val restored = okReply(JSONObject().put("pkey", "rotated+%").put("profile", JSONObject().put("heybox_id", 123)))
        val transport = ScriptTransport(startReply(), waiting, confirmed, restored)
        val provider = HeyboxQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        val result = provider.poll(ticket)
        assertCredential("123", "rotated+%25", result)
        val restore = transport.requests.last()
        assertGet(restore, "/account/restore_login", setOf("app"))
        assertEquals("user_pkey=fixture%3D+%25; user_heybox_id=123", restore.headers["Cookie"])
        assertSame(result, provider.poll(ticket))
        assertEquals(4, transport.requests.size)
    }

    @Test fun successfulRestoreCanFallBackToEitherPkeyCookieWithoutEncodingItAgain() {
        for (name in listOf("pkey", "user_pkey")) {
            val restored = okReply(JSONObject().put("account_detail", JSONObject().put("userid", 123)),
                "$name=fixture%3D+%25")
            val transport = ScriptTransport(startReply(), stateReply("ok", "heybox_id=123"), restored)
            val provider = HeyboxQrProvider(transport) { TEST_NOW }
            assertCredential("123", "fixture%3D+%25", provider.poll(provider.start()))
            assertEquals(3, transport.requests.size)
        }
    }

    @Test fun missingCredentialsOrRejectedRestoreFailAfterOneAttempt() {
        val cases = listOf(
            stateReply("ok") to anonymous,
            stateReply("ok", "pkey=fixture", "heybox_id=123") to anonymous,
            stateReply("ok") to okReply(JSONObject().put("pkey", "fixture")),
            stateReply("ok") to okReply(JSONObject().put("profile", JSONObject().put("heybox_id", 123))),
            stateReply("ok", fields = JSONObject().put("profile", JSONObject().put("heybox_id", 123))) to
                okReply(JSONObject().put("pkey", "fixture").put("profile", JSONObject().put("heybox_id", 456)))
        )
        for ((poll, restored) in cases) {
            val transport = ScriptTransport(startReply(), poll, restored)
            val provider = HeyboxQrProvider(transport) { TEST_NOW }
            val ticket = provider.start()
            val result = provider.poll(ticket)
            assertEquals(missingCredential, result)
            assertSame(result, provider.poll(ticket))
            assertEquals(3, transport.requests.size)
        }
    }

    @Test fun expiredTicketDoesNotReachTheNetworkAfterTheServerDeadline() {
        var now = TEST_NOW
        val transport = ScriptTransport(startReply())
        val provider = HeyboxQrProvider(transport) { now }
        val ticket = provider.start()
        now += 120_000
        assertEquals(QrPoll.Expired("二维码已过期"), provider.poll(ticket))
        assertEquals(1, transport.requests.size)
    }

    private fun assertGet(request: QrRequest, path: String, parameters: Set<String>) {
        val url = request.url.toHttpUrl()
        assertEquals("GET", request.method)
        assertEquals("api.xiaoheihe.cn", url.host)
        assertEquals(path, url.encodedPath)
        assertEquals(parameters, url.queryParameterNames)
        assertEquals("web", url.queryParameter("app"))
        assertNull(request.body)
        assertEquals(DESKTOP_UA, request.headers["User-Agent"])
        assertEquals("https://login.xiaoheihe.cn/", request.headers["Referer"])
    }

    private fun assertCredential(uid: String, pkey: String, result: QrPoll) {
        val credential = (result as QrPoll.Confirmed).credential
        assertEquals("heybox_id=$uid; user_heybox_id=$uid; pkey=$pkey; user_pkey=$pkey", credential)
        assertFalse(result.toString().contains(pkey))
    }
}
