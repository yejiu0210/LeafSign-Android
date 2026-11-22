package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.platform.HeyboxAdapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal class HeyboxQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf(HOST)),
    now: () -> Long = System::currentTimeMillis
) : SafeQrProvider(Platform.HEYBOX, now) {
    private val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://login.xiaoheihe.cn/")

    override fun create(): QrTicket {
        val response = transport.request("GET", "$BASE/account/get_qrcode_url/?app=web", headers = headers).json()
        if (response.text("status") != "ok") problem("小黑盒暂时无法生成二维码，请稍后重试")
        val result = response.optJSONObject("result") ?: problem("小黑盒登录响应缺少二维码")
        val content = result.required("qr_url")
        val url = content.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == HOST && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() &&
                it.encodedPath == "/account/qr_login/" && it.fragment == null }
            ?: problem("小黑盒二维码地址不受信任")
        val qr = url.queryParameterValues("qr").singleOrNull()?.takeIf(::validQrId)
            ?: problem("二维码参数缺失")
        val expiresIn = result.number("expire").takeIf { it > 0 } ?: problem("小黑盒二维码有效期异常")
        return QrTicket(platform, content, expiresAt = now() + expiresIn * 1000L, state = mapOf("qr" to qr))
    }

    override fun check(ticket: QrTicket): QrPoll {
        val qr = ticket.state["qr"]?.takeIf(::validQrId) ?: problem("二维码参数缺失")
        val url = query("$BASE/account/qr_state/", "qr" to qr, "app" to "web")
        val response = transport.request("GET", url, headers = headers)
        val root = response.json()
        when (root.text("status")) {
            "ok" -> Unit
            "need_google_check" -> return QrPoll.Failed("账号开启了二次验证，请先在小黑盒 App 里关闭或改用手动导入")
            else -> return QrPoll.Failed(root.text("msg") ?: "二维码状态查询失败")
        }
        val result = root.optJSONObject("result") ?: problem("小黑盒登录响应缺少扫码状态")
        cookieValues(response.raw.setCookies, LOGIN_COOKIES, ticket.sessionCookies)
        return when (result.text("error")) {
            "wait" -> QrPoll.Waiting
            "ready" -> QrPoll.Scanned
            "ok" -> confirm(result, ticket.sessionCookies)
            else -> QrPoll.Expired(result.text("error_msg") ?: "二维码已过期")
        }
    }

    private fun confirm(result: JSONObject, cookies: MutableMap<String, String>): QrPoll {
        val qrId = result.heyboxId()
        val qrPkey = result.wirePkey()
        if (qrId != null && qrPkey != null) return confirmed(qrId, qrPkey)

        val restoreHeaders = if (cookies.isEmpty()) headers else headers + ("Cookie" to cookieHeader(cookies))
        val response = transport.request("GET", "$BASE${HeyboxAdapter.IDENTITY_PATH}?app=web", headers = restoreHeaders)
        val restored = response.json()
        if (restored.text("status") != "ok") return missingCredential()
        cookieValues(response.raw.setCookies, LOGIN_COOKIES, cookies)
        val restoredResult = restored.optJSONObject("result") ?: return missingCredential()
        val restoredId = restoredResult.heyboxId()
        if (qrId != null && restoredId != null && qrId != restoredId) return missingCredential()
        val uid = restoredId ?: qrId ?: HeyboxAdapter.positiveId(cookies["heybox_id"])
            ?: HeyboxAdapter.positiveId(cookies["user_heybox_id"]) ?: return missingCredential()
        val pkey = restoredResult.wirePkey() ?: cookies["pkey"] ?: cookies["user_pkey"] ?: qrPkey
            ?: return missingCredential()
        return confirmed(uid, pkey)
    }

    private fun JSONObject.heyboxId() = HeyboxAdapter.positiveId(optJSONObject("profile")?.opt("heybox_id"))
        ?: HeyboxAdapter.positiveId(optJSONObject("account_detail")?.opt("userid"))

    private fun JSONObject.wirePkey() = text("pkey")?.takeIf(::validCookieValue)?.let { HeyboxAdapter.cookieWireValue(it) }

    private fun confirmed(uid: String, pkey: String): QrPoll {
        val credential = cookieHeader(mapOf("heybox_id" to uid, "user_heybox_id" to uid, "pkey" to pkey, "user_pkey" to pkey))
        return if (credential.length <= 32768) QrPoll.Confirmed(credential) else missingCredential()
    }

    private fun missingCredential() = QrPoll.Failed("小黑盒未返回登录凭据，请重新扫码")

    private fun validQrId(value: String) = value.length in 1..256 && value.none { it.isWhitespace() || it.isISOControl() }

    private companion object {
        const val HOST = "api.xiaoheihe.cn"
        const val BASE = "https://$HOST"
        val LOGIN_COOKIES = setOf("pkey", "user_pkey", "heybox_id", "user_heybox_id")
    }
}
