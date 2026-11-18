package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.platform.NeteaseAdapter
import okhttp3.FormBody
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

internal class NeteaseQrProvider(
    private val transport: QrTransport = OfficialQrTransport(setOf("music.163.com", "interfacepc.music.163.com")),
    now: () -> Long = System::currentTimeMillis,
    private val encryptWeapi: (String) -> Map<String, String> = NeteaseAdapter::encryptWeapi,
    private val encryptEapi: (String, String) -> Map<String, String> = NeteaseAdapter::encryptEapi
) : SafeQrProvider(Platform.NETEASE, now) {
    private val sessionNames = setOf("NMTID", "_ntes_nuid", "_ntes_nnid", "WEVNSM", "WNMCID")
    private val credentialNames = setOf("MUSIC_U", "__csrf")

    private fun request(channel: String, path: String, data: JSONObject, deviceId: String, cookies: MutableMap<String, String>): QrResponse {
        val url: String
        val fields: Map<String, String>
        val baseCookie: String
        val ua: String
        if (channel == "eapi") {
            val header = JSONObject().put("os", "pc").put("appver", "3.1.17.204416")
                .put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
                .put("channel", "netease").put("deviceId", deviceId).put("versioncode", "140")
                .put("mobilename", "").put("buildver", (now() / 1000).toString())
                .put("resolution", "1920x1080").put("__csrf", "")
                .put("requestId", "${now()}_${SecureRandom().nextInt(1000).toString().padStart(4, '0')}")
            data.put("e_r", false).put("header", header)
            fields = encryptEapi("/api/login/qrcode/$path", data.toString())
            baseCookie = NeteaseAdapter.eapiCookie(header)
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
            url = "https://interfacepc.music.163.com/eapi/login/qrcode/$path"
        } else {
            data.put("csrf_token", "")
            fields = encryptWeapi(data.toString())
            baseCookie = "deviceId=$deviceId"
            ua = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
            url = "https://music.163.com/weapi/login/qrcode/$path"
        }
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to ua,
            "Cookie" to listOf(baseCookie, cookieHeader(cookies)).filter { it.isNotEmpty() }.joinToString("; "))
        val response = transport.request("POST", url, body, headers)
        // Capture session rotation even when the business response cannot be parsed or requests fallback.
        for (line in response.raw.setCookies) {
            val pair = line.substringBefore(';').trim()
            val name = pair.substringBefore('=').trim()
            if ('=' !in pair || name !in sessionNames) continue
            val parsed = cookieValues(listOf(line), sessionNames)
            if (name in parsed) cookies[name] = parsed.getValue(name) else cookies.remove(name)
        }
        return response
    }

    private fun payload(root: JSONObject) = root.optJSONObject("data")?.takeIf { it.has("code") } ?: root

    override fun create(): QrTicket {
        val deviceId = UUID.randomUUID().toString().replace("-", "")
        val cookies = linkedMapOf<String, String>()
        val eapiResponse = request("eapi", "unikey", JSONObject().put("type", 3), deviceId, cookies)
        // Only a completed but unusable response falls back. A network failure is not blindly retried.
        val eapiKey = try {
            val root = eapiResponse.json()
            if (payload(root).number("code") == 200) unikey(root) else null
        } catch (_: JSONException) { null }
        catch (_: QrProblem) { null }
        if (eapiKey != null) return newTicket(eapiKey, "eapi", 3, deviceId, cookies)

        var type = 1
        var root = request("weapi", "unikey", JSONObject().put("type", type), deviceId, cookies).json()
        if (payload(root).number("code") != 200) {
            type = 3
            root = request("weapi", "unikey", JSONObject().put("type", type), deviceId, cookies).json()
        }
        if (payload(root).number("code") != 200) problem("网易云暂时无法生成二维码，请稍后重试")
        val key = unikey(root) ?: problem("网易云登录响应缺少 unikey")
        return newTicket(key, "weapi", type, deviceId, cookies)
    }

    private fun unikey(root: JSONObject) = root.text("unikey") ?: root.optJSONObject("data")?.text("unikey")

    private fun newTicket(key: String, channel: String, type: Int, deviceId: String, cookies: Map<String, String>): QrTicket =
        ticket(query("https://music.163.com/login", "codekey" to key),
            mapOf("unikey" to key, "channel" to channel, "type" to type.toString(), "deviceId" to deviceId)).also { ticket ->
            synchronized(ticket) { ticket.sessionCookies.putAll(cookies) }
        }

    override fun check(ticket: QrTicket): QrPoll {
        // SafeQrProvider.poll holds the ticket monitor for this entire request/read/update sequence.
        val channel = ticket.key("channel").takeIf { it == "eapi" || it == "weapi" }
            ?: problem("二维码状态不完整，请重新生成")
        val deviceId = ticket.key("deviceId").takeIf { Regex("[0-9a-f]{32}").matches(it) }
            ?: problem("二维码状态不完整，请重新生成")
        val type = ticket.key("type").toIntOrNull()?.takeIf { it == 1 || it == 3 }
            ?: problem("二维码状态不完整，请重新生成")
        val previousCookies = ticket.sessionCookies.values.toList()
        val response = request(channel, "client/login", JSONObject().put("key", ticket.key("unikey")).put("type", type), deviceId, ticket.sessionCookies)
        val root = response.json()
        val data = payload(root)
        val code = data.number("code")
        ticket.lastStatusCode = code
        return when (code) {
            800 -> QrPoll.Expired("二维码已过期")
            801 -> QrPoll.Waiting
            802 -> QrPoll.Scanned
            803 -> {
                val cookies = cookieValues(response.raw.setCookies, credentialNames)
                if (cookies["MUSIC_U"].isNullOrEmpty()) {
                    val fallback = data.text("cookie") ?: root.text("cookie")
                    val parsed = cookieValues(fallback?.split(';').orEmpty(), credentialNames)
                    parsed.forEach { (name, value) -> if (cookies[name].isNullOrEmpty()) cookies[name] = value }
                }
                if (cookies["MUSIC_U"].isNullOrEmpty()) problem("网易云登录响应缺少 MUSIC_U，请重新扫码")
                QrPoll.Confirmed(cookieHeader(cookies))
            }
            8821 -> QrPoll.Failed("网易云要求进行行为验证（8821），请稍后重试，或改用网页登录 / 手动导入")
            else -> {
                val cookieLines = response.raw.setCookies + listOfNotNull(data.text("cookie"), root.text("cookie")).flatMap { it.split(';') }
                val values = cookieLines.mapNotNull { line ->
                    line.substringBefore(';').takeIf { '=' in it }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
                } + previousCookies + ticket.sessionCookies.values
                val message = safeServerMessage(data.text("message") ?: root.text("message") ?: data.text("msg"), values)
                QrPoll.Failed("网易云返回状态 $code：$message，请重新扫码或使用备用登录")
            }
        }
    }

    private fun safeServerMessage(message: String?, cookieValues: List<String>): String {
        fun clean(text: String) = text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        var text = clean(message.orEmpty())
        for (value in cookieValues.map(::clean).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }) {
            text = text.replace(value, "[已隐藏]")
        }
        return text.trim().take(60).ifEmpty { "未提供说明" }
    }
}
