package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class BilibiliQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf("passport.bilibili.com")),
    now: () -> Long = System::currentTimeMillis
) : SafeQrProvider(Platform.BILIBILI, now) {
    private val base = "https://passport.bilibili.com/x/passport-login/web/qrcode"
    private val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.bilibili.com/")
    private val allowed = linkedSetOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "bili_ticket", "bili_ticket_expires")

    override fun create(): QrTicket {
        val root = transport.request("GET", "$base/generate", headers = headers).json()
        if (root.number("code") != 0) problem("哔哩哔哩暂时无法生成二维码，请稍后重试")
        val data = root.optJSONObject("data") ?: problem("哔哩哔哩登录响应缺少 data")
        return ticket(data.required("url"), mapOf("qrcode_key" to data.required("qrcode_key")))
    }

    override fun check(ticket: QrTicket): QrPoll {
        val response = transport.request("GET", query("$base/poll", "qrcode_key" to ticket.key("qrcode_key")), headers = headers)
        val root = response.json()
        if (root.number("code") != 0) problem("哔哩哔哩登录查询失败，请重新生成二维码")
        val data = root.optJSONObject("data") ?: problem("哔哩哔哩登录响应缺少 data")
        val status = data.number("code")
        ticket.lastStatusCode = status // Retain only the numeric code; unknown states keep polling.
        return when (status) {
            86101 -> QrPoll.Waiting
            86090 -> QrPoll.Scanned
            86038 -> QrPoll.Expired("二维码已过期")
            0 -> {
                val cookies = cookieValues(response.raw.setCookies, allowed)
                if (cookies["SESSDATA"].isNullOrEmpty() || cookies["bili_jct"].isNullOrEmpty()) {
                    val url = data.text("url")?.toHttpUrlOrNull()
                    for (name in allowed) if (cookies[name].isNullOrEmpty()) {
                        url?.queryParameter(name)?.takeIf(::validCookieValue)?.let { cookies[name] = it }
                    }
                }
                if (cookies["SESSDATA"].isNullOrEmpty() || cookies["bili_jct"].isNullOrEmpty())
                    problem("哔哩哔哩登录响应缺少 SESSDATA 或 bili_jct，请重新扫码")
                QrPoll.Confirmed(cookieHeader(allowed.mapNotNull { name -> cookies[name]?.let { name to it } }.toMap()))
            }
            else -> QrPoll.Waiting
        }
    }
}
