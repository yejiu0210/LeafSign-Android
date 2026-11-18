package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal class TiebaQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf("passport.baidu.com", "wappass.baidu.com", "tieba.baidu.com")),
    now: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) : SafeQrProvider(Platform.TIEBA, now) {
    private val base = "https://passport.baidu.com"
    private val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://tieba.baidu.com/")

    override fun create(): QrTicket {
        val time = now()
        var root = transport.request("GET", query("$base/v2/api/getqrcode", "lp" to "pc", "qrloginfrom" to "pc",
            "tpl" to "tb", "apiver" to "v3", "tt" to "$time", "_" to "$time"), headers = headers).json()
        if (root.number("errno") != 0) {
            root = transport.request("GET", query("$base/v2/api/getqrcode", "lp" to "pc", "_" to "${now()}"), headers = headers).json()
        }
        if (root.number("errno") != 0) problem("百度暂时无法生成二维码，请稍后重试")
        val sign = root.required("sign")
        val imageUrl = baiduImageUrl(root.required("imgurl"))
        val image = transport.image(imageUrl.toString(), headers)
        if (image.isEmpty()) problem("百度二维码图片为空，请重新生成")
        val content = query("https://wappass.baidu.com/wp/", "qrlogin" to null, "t" to "$time", "error" to "0", "sign" to sign,
            "cmd" to "login", "lp" to "pc", "tpl" to "tb", "uaonly" to "", "client_id" to "", "adapter" to "3",
            "client" to "", "qrloginfrom" to "pc", "wechat" to "0", "traceid" to "")
        return QrTicket(platform, content, image, time + QR_LIFETIME_MS, mapOf("sign" to sign))
    }

    override fun check(ticket: QrTicket): QrPoll {
        val time = now().toString()
        val root = transport.request("GET", query("$base/channel/unicast", "channel_id" to ticket.key("sign"),
            "tpl" to "tb", "callback" to "", "apiver" to "v3", "tt" to time, "_" to time), headers = headers).json()
        if (root.number("errno") != 0) return QrPoll.Waiting
        val event = parseWrappedJson(root.required("channel_v"))
        return when (event.number("status")) {
            1 -> QrPoll.Scanned
            0 -> exchange(event.required("v"))
            else -> QrPoll.Waiting
        }
    }

    private fun exchange(value: String): QrPoll {
        val time = now().toString()
        val response = transport.request("GET", query("$base/v3/login/main/qrbdusslogin", "v" to time, "bduss" to value,
            "u" to "", "loginVersion" to "v4", "qrcode" to "1", "tpl" to "tb", "apiver" to "v3", "tt" to time,
            "traceid" to "", "callback" to "bd__cbs__cupstt"), headers = headers)
        if (response.raw.code !in 200..299) problem("百度登录凭证换取失败，请重新扫码")
        val root = parseBaiduJsonp(response.raw.body)
        val error = root.optJSONObject("errInfo") ?: problem("百度登录响应缺少 errInfo")
        if (error.number("no") != 0) problem("百度登录凭证换取失败，请重新扫码")
        val cookies = cookieValues(response.raw.setCookies, setOf("BDUSS", "PTOKEN", "STOKEN"))
        if (cookies["BDUSS"].isNullOrEmpty()) problem("百度登录响应缺少 BDUSS，请重新扫码")
        supplementStoken(cookies)
        return QrPoll.Confirmed(cookieHeader(cookies.filterKeys { it in setOf("BDUSS", "STOKEN") }))
    }

    private fun supplementStoken(cookies: MutableMap<String, String>) {
        // STOKEN is optional. Both requests together share one deadline; no redirect chain is followed.
        val deadline = monotonicMillis() + 15_000L
        try {
            fun remaining() = (deadline - monotonicMillis()).coerceAtMost(15_000L)
            val firstBudget = remaining()
            if (firstBudget <= 0) return
            val auth = transport.request("GET", query("$base/v3/login/api/auth/", "return_type" to "5", "tpl" to "tb",
                "u" to "https://tieba.baidu.com/"), headers = headers + ("Cookie" to cookieHeader(cookies)), timeoutMillis = firstBudget)
            val received = cookieValues(auth.raw.setCookies, setOf("STOKEN"))["STOKEN"]
            if (received != null) { cookies["STOKEN"] = received; return }
            val location = auth.location?.takeIf { it.startsWith("https://tieba.baidu.com/") }?.toHttpUrlOrNull() ?: return
            if (!location.isHttps || location.host != "tieba.baidu.com" || location.port != 443 ||
                location.username.isNotEmpty() || location.password.isNotEmpty()) return
            val secondBudget = remaining()
            if (secondBudget <= 0) return
            val result = transport.request("GET", location.toString(), headers = headers + ("Cookie" to cookieHeader(cookies)), timeoutMillis = secondBudget)
            cookieValues(result.raw.setCookies, setOf("STOKEN"))["STOKEN"]?.let { cookies["STOKEN"] = it }
        } catch (_: Exception) { /* Optional enrichment must not discard a confirmed BDUSS or expose a raw error. */ }
    }
}
