package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import org.json.JSONObject

internal class SklandQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf("as.hypergryph.com")),
    now: () -> Long = System::currentTimeMillis
) : SafeQrProvider(Platform.SKLAND, now) {
    private val base = "https://as.hypergryph.com"
    private val headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json", "Origin" to "https://user.hypergryph.com", "Referer" to "https://user.hypergryph.com/")

    override fun create(): QrTicket {
        val root = transport.request("POST", "$base/general/v1/gen_scan/login", jsonBody(JSONObject()), headers).json()
        if (root.number("status") != 0) problem("森空岛暂时无法生成二维码，请稍后重试")
        val data = root.optJSONObject("data") ?: problem("森空岛登录响应缺少 data")
        val state = mutableMapOf("scanId" to data.required("scanId"))
        data.optJSONArray("enableScanAppList")?.let { apps ->
            val names = (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.text("name") }
            if (names.isNotEmpty()) state["apps"] = names.joinToString(",")
        }
        return ticket(data.required("scanUrl"), state)
    }

    override fun check(ticket: QrTicket): QrPoll {
        val root = transport.request("GET", query("$base/general/v1/scan_status", "scanId" to ticket.key("scanId")), headers = headers).json()
        return when (root.number("status")) {
            100 -> QrPoll.Waiting
            101 -> QrPoll.Scanned
            3 -> QrPoll.Expired("已在手机上取消登录")
            4 -> QrPoll.Expired("二维码已过期")
            0 -> {
                val scanCode = root.optJSONObject("data")?.text("scanCode") ?: problem("森空岛登录响应缺少 scanCode")
                val result = transport.request("POST", "$base/user/auth/v1/token_by_scan_code",
                    jsonBody(JSONObject().put("scanCode", scanCode)), headers).json()
                if (result.number("status") != 0) problem("森空岛登录凭证换取失败，请重新扫码")
                val token = result.optJSONObject("data")?.text("token") ?: problem("森空岛登录响应缺少 token")
                if (token.length !in 16..4096 || token.any { it.isWhitespace() || it.isISOControl() })
                    problem("森空岛登录 token 格式异常，请重新扫码")
                QrPoll.Confirmed(token)
            }
            else -> QrPoll.Waiting
        }
    }
}
