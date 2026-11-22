package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

internal const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
internal const val QR_LIFETIME_MS = 170_000L
internal class QrProblem(val userMessage: String) : Exception()
internal fun problem(message: String): Nothing = throw QrProblem(message)

internal abstract class SafeQrProvider(final override val platform: Platform, protected val now: () -> Long) : QrLoginProvider {
    final override fun start(): QrTicket = try { create() }
    catch (e: Exception) { throw QrLoginException(safeMessage(e)) }

    final override fun poll(ticket: QrTicket): QrPoll = synchronized(ticket) {
        if (ticket.platform != platform) return@synchronized QrPoll.Failed("二维码平台不匹配，请重新生成")
        ticket.terminalResult?.let { return@synchronized it }
        val result = try {
            if (now() >= ticket.expiresAt) QrPoll.Expired("二维码已过期") else check(ticket)
        } catch (e: Exception) { QrPoll.Failed(safeMessage(e)) }
        if (result is QrPoll.Confirmed || result is QrPoll.Expired || result is QrPoll.Failed) ticket.terminalResult = result
        result
    }

    protected abstract fun create(): QrTicket
    protected abstract fun check(ticket: QrTicket): QrPoll
    protected fun ticket(content: String, state: Map<String, String>, image: ByteArray? = null) =
        QrTicket(platform, content, image, now() + QR_LIFETIME_MS, state)

    private fun safeMessage(e: Exception) = when (e) {
        is QrProblem -> e.userMessage
        is IOException -> "网络连接失败，请检查网络后重试"
        else -> "登录响应无法解析，请重新生成二维码后重试"
    }
}

internal fun JSONObject.text(field: String): String? = (opt(field) as? String)?.takeIf { it.isNotBlank() }
internal fun JSONObject.required(field: String): String = text(field) ?: problem("登录响应缺少必要字段：$field")
internal fun JSONObject.number(field: String): Int = opt(field)?.toString()?.toIntOrNull()
    ?: problem("登录响应缺少或无法识别状态字段：$field")
internal fun QrTicket.key(name: String) = state[name]?.takeIf { it.isNotBlank() }
    ?: problem("二维码状态不完整，请重新生成")
internal fun query(url: String, vararg params: Pair<String, String?>) = url.toHttpUrl().newBuilder()
    .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()
internal fun jsonBody(data: JSONObject) = data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
internal fun QrResponse.json(): JSONObject {
    if (raw.code !in 200..299) problem("登录服务器响应异常，请稍后重试")
    return parseWrappedJson(raw.body)
}

internal fun parseWrappedJson(body: String): JSONObject {
    var text = body.trim().removePrefix("\uFEFF").trim().removeSuffix(";").trim()
    while (text.startsWith('(') && text.endsWith(')')) text = text.substring(1, text.length - 1).trim()
    return JSONObject(text)
}

internal fun parseBaiduJsonp(body: String): JSONObject {
    val trimmed = body.trim()
    val plain = if (trimmed.startsWith('{')) trimmed else {
        val first = trimmed.indexOf('(')
        val last = trimmed.lastIndexOf(')')
        if (first < 0 || last <= first) problem("百度登录响应格式无法识别")
        trimmed.substring(first + 1, last)
    }
    // One HTML decoding pass, including numeric entities. Never execute the callback/script.
    val decoded = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|quot|apos|amp|lt|gt);", RegexOption.IGNORE_CASE).replace(plain) { match ->
        val entity = match.groupValues[1]
        when (entity.lowercase()) {
            "quot" -> "\""; "apos" -> "'"; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"
            else -> {
                val code = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else match.value
            }
        }
    }
    return parseWrappedJson(decoded)
}

internal fun cookieValues(
    lines: List<String>, allowed: Set<String>, result: MutableMap<String, String> = linkedMapOf()
): MutableMap<String, String> {
    for (line in lines) {
        val part = line.substringBefore(';').trim()
        val split = part.indexOf('=')
        if (split <= 0) continue
        val name = part.substring(0, split).trim()
        if (name !in allowed) continue
        val value = part.substring(split + 1).trim()
        if (value.isEmpty()) result.remove(name)
        else if (validCookieValue(value)) result[name] = value
        else problem("登录凭证格式异常，请重新扫码")
    }
    return result
}
internal fun validCookieValue(value: String) = value.isNotEmpty() && value.length <= 16_384 &&
    value.all { it.code in 33..126 && it != ';' }
internal fun cookieHeader(cookies: Map<String, String>) = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
