package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.platform.HeyboxAdapter
import cn.onetap.checkin.platform.HeyboxWebRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal class HeyboxQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf(HeyboxWebRequest.HOST)),
    now: () -> Long = System::currentTimeMillis
) : SafeQrProvider(Platform.HEYBOX, now) {
    override fun create(): QrTicket {
        val response = request("/account/get_qrcode_url/", body = ByteArray(0).toRequestBody()).json()
        if (response.text("status") != "ok") problem("小黑盒暂时无法生成二维码，请稍后重试")
        val result = response.optJSONObject("result") ?: problem("小黑盒登录响应缺少二维码")
        val content = result.required("qr_url")
        val url = content.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == HeyboxWebRequest.HOST && it.port == 443 &&
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
        val response = request("/account/qr_state/", mapOf("qr" to qr))
        collectCookies(response.raw.setCookies, ticket)
        val root = response.json()
        when (root.text("status")) {
            "ok" -> Unit
            "need_google_check" -> return QrPoll.Failed("账号开启了二次验证，请先在小黑盒 App 里关闭或改用手动导入")
            else -> return QrPoll.Failed(root.text("msg") ?: "二维码状态查询失败")
        }
        val result = root.optJSONObject("result") ?: problem("小黑盒登录响应缺少扫码状态")
        return when (result.text("error")) {
            "wait" -> QrPoll.Waiting
            "ready" -> QrPoll.Scanned
            "ok" -> confirm(result, ticket)
            else -> QrPoll.Expired(result.text("error_msg") ?: "二维码已过期")
        }
    }

    private fun request(
        path: String, extra: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap(),
        body: RequestBody? = null
    ): QrResponse {
        val url = HeyboxWebRequest.url(path, HeyboxWebRequest.parameters(path, now()) + extra)
        val headers = HeyboxWebRequest.headers.let { if (cookies.isEmpty()) it else it + ("Cookie" to cookieHeader(cookies)) }
        return transport.request(if (body == null) "GET" else "POST", url, body, headers)
    }

    private fun confirm(result: JSONObject, ticket: QrTicket): QrPoll {
        val cookies = ticket.sessionCookies
        val qrId = result.heyboxId()
        val qrPkey = result.wirePkey()
        confirmed(qrId ?: cookieId(cookies), qrPkey ?: cookiePkey(cookies), cookies)?.let { return it }

        val response = try {
            request(HeyboxAdapter.IDENTITY_PATH, cookies = cookies)
        } catch (_: IOException) {
            return missingCredential(result, ticket, "请求失败")
        }
        collectCookies(response.raw.setCookies, ticket)
        val restored = try {
            parseWrappedJson(response.raw.body)
        } catch (_: JSONException) {
            return missingCredential(result, ticket, "响应异常")
        }
        val status = restored.text("status")
        if (response.raw.code !in 200..299 || status != "ok") {
            val diagnostic = status?.let { if (it in SAFE_RESTORE_STATUSES) it else "未知" } ?: "无"
            return missingCredential(result, ticket, diagnostic)
        }
        val restoredResult = restored.optJSONObject("result") ?: return missingCredential(result, ticket, "ok")
        val restoredId = restoredResult.heyboxId()
        if (qrId != null && restoredId != null && qrId != restoredId) return missingCredential(result, ticket, "ok")
        return confirmed(restoredId ?: qrId ?: cookieId(cookies), restoredResult.wirePkey() ?: qrPkey ?: cookiePkey(cookies), cookies)
            ?: missingCredential(result, ticket, "ok")
    }

    private fun collectCookies(lines: List<String>, ticket: QrTicket) {
        for (line in lines) {
            val pair = line.substringBefore(';').split('=', limit = 2)
            if (pair.size != 2) continue
            val name = pair[0].trim()
            if (!COOKIE_NAME.matches(name)) continue
            ticket.observedCookieNames += name
            val value = pair[1].trim()
            if (value.isEmpty()) ticket.sessionCookies.remove(name)
            else if (validCookieValue(value)) ticket.sessionCookies[name] = value
        }
    }

    private fun JSONObject.heyboxId() = HeyboxAdapter.positiveId(optJSONObject("profile")?.opt("heybox_id"))
        ?: HeyboxAdapter.positiveId(optJSONObject("account_detail")?.opt("userid"))

    private fun JSONObject.wirePkey() = text("pkey")?.takeIf(::validCookieValue)?.let { HeyboxAdapter.cookieWireValue(it) }

    private fun cookieId(cookies: Map<String, String>) =
        HeyboxAdapter.positiveId(cookies["heybox_id"]) ?: HeyboxAdapter.positiveId(cookies["user_heybox_id"])

    private fun cookiePkey(cookies: Map<String, String>) = cookies["pkey"] ?: cookies["user_pkey"]

    private fun confirmed(uid: String?, pkey: String?, cookies: Map<String, String>): QrPoll.Confirmed? {
        if (uid == null || pkey == null) return null
        val accepted = cookies.filterKeys { it in HeyboxAdapter.COOKIE_NAMES } +
            mapOf("heybox_id" to uid, "user_heybox_id" to uid, "pkey" to pkey, "user_pkey" to pkey)
        return cookieHeader(accepted).takeIf { it.length <= 32768 }?.let { QrPoll.Confirmed(it) }
    }

    private fun missingCredential(result: JSONObject, ticket: QrTicket, restoreStatus: String): QrPoll.Failed {
        val fields = result.keys().asSequence().filter(DIAGNOSTIC_NAME::matches).sorted().joinToString(",").ifEmpty { "无" }
        val cookies = ticket.observedCookieNames.sorted().joinToString(",").ifEmpty { "无" }
        return QrPoll.Failed("小黑盒未返回登录凭据，请重新扫码（result 字段：$fields；Cookie：$cookies；restore_login：$restoreStatus）")
    }

    private fun validQrId(value: String) = value.length in 1..256 && value.none { it.isWhitespace() || it.isISOControl() }

    private companion object {
        val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val DIAGNOSTIC_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
        val SAFE_RESTORE_STATUSES = setOf("ok", "login", "relogin", "failed", "need_google_check",
            "show_captcha", "lack_token", "name_verify", "need_alipay_verify", "need_bind_phone", "need_phone_code")
    }
}
