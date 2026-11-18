package cn.onetap.checkin.core

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Exact host allowlist, TLS only, no redirects, no ambient cookies or HTTP logging. */
data class RawResponse(val code: Int, val body: String, val setCookies: List<String>) {
    override fun toString() = "RawResponse(code=$code, body=<redacted>, setCookies=<redacted>)"
}

internal data class RawResponseWithLocation(val response: RawResponse, val location: String?) {
    override fun toString() = "RawResponseWithLocation(response=$response, location=<redacted>)"
}

internal class HttpStatusException(
    val code: Int, status: TaskStatus, message: String, diagnostic: String = "HTTP $code"
) : AdapterException(status, message, diagnostic) {
    val isRedirect: Boolean get() = code in 300..399
    val isTransient: Boolean get() = status == TaskStatus.UNKNOWN &&
        (code == 302 || code == 303 || code == 307 || code in 500..599)
}

class NetClient internal constructor(private val allowedHosts: Set<String>, base: OkHttpClient) {
    constructor(allowedHosts: Set<String>) : this(allowedHosts, baseClient)
    companion object {
        private val baseClient by lazy { OkHttpClient() }
    }
    private val client = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    /** Unlike JSON requests, HTTP errors and redirects are returned without following them. */
    fun raw(method: String, url: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): RawResponse =
        rawWithLocation(method, url, body, headers).response

    internal fun rawWithLocation(method: String, url: String, body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(), timeoutMillis: Long = 40_000): RawResponseWithLocation {
        require(timeoutMillis in 1..40_000)
        val call = client.newCall(buildRequest(method, url, body, headers))
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val responseBody = response.body
            val bytes = boundedBytes(responseBody)
            val text = bytes.toString(responseBody?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                .removePrefix("\uFEFF")
            val cookies = response.headers.values("Set-Cookie").mapNotNull { cookie ->
                cookie.substringBefore(';').trim().takeIf { it.indexOf('=') > 0 && it.none { c -> c.code < 32 || c.code == 127 } }
            }
            return RawResponseWithLocation(RawResponse(response.code, text, cookies), response.header("Location"))
        }
    }

    internal fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        client.newCall(buildRequest("GET", url, null, headers)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("图片下载失败")
            return boundedBytes(response.body)
        }
    }

    private fun boundedBytes(body: ResponseBody?): ByteArray {
        if (body == null) return byteArrayOf()
        val limit = 4_000_000L
        if (body.contentLength() > limit) throw IOException("响应超出限制")
        val source = body.source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("响应超出限制")
        return source.readByteArray()
    }

    private fun buildRequest(method: String, url: String, body: RequestBody?, headers: Map<String, String>): Request {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("request cancelled")
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host in allowedHosts && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty()) { "平台域名不在允许列表" }
        return Request.Builder().url(parsed).header("User-Agent", "OneTapCheckIn/0.1 Android")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.method(method, body).build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject = request("GET", url, null, headers)
    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        request("POST", url, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()), headers)
    fun postRawJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JSONObject =
        request("POST", url, body.toRequestBody("application/json; charset=utf-8".toMediaType()), headers)
    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return request("POST", url, body, headers)
    }
    private fun request(method: String, url: String, body: RequestBody?, headers: Map<String, String>): JSONObject {
        val request = buildRequest(method, url, body, headers)
        // OkHttp can still replay a POST on HTTP 503 with Retry-After: 0. Tieba reconciles sign writes itself.
        val req = if (body != null && request.url.host == "tiebac.baidu.com" && request.url.encodedPath == "/c/c/forum/sign") {
            request.newBuilder().method(method, object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                override fun isOneShot() = true
            }).build()
        } else request
        client.newCall(req).execute().use { response ->
            if (response.code in 300..399) throw redirectFailure(response.code, req.url, response.header("Location"))
            if (response.code == 401) throw HttpStatusException(401, TaskStatus.EXPIRED, "登录已失效，请重新登录")
            if (response.code == 403 || response.code == 412 || response.code == 429)
                throw HttpStatusException(response.code, TaskStatus.MANUAL, "请完成平台验证后重试")
            if (!response.isSuccessful) throw HttpStatusException(response.code, TaskStatus.UNKNOWN, "结果未确认，请稍后重试")
            val raw = response.body?.string() ?: throw IOException("empty response")
            if (raw.length > 4_000_000) throw AdapterException(TaskStatus.UNKNOWN, "响应超出限制")
            return try { JSONObject(raw) } catch (_: Exception) { throw AdapterException(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "HTTP ${response.code}; non-json") }
        }
    }

    private fun redirectFailure(code: Int, source: HttpUrl, location: String?): HttpStatusException {
        val destination = location?.takeIf { it.isNotBlank() }?.let(source::resolve)
        val tieba = source.host == "tiebac.baidu.com" || source.host == "tieba.baidu.com"
        val endpoint = source.encodedPath.takeIf { tieba && it in setOf(
            "/c/s/login", "/c/f/forum/like", "/c/c/forum/sign", "/c/f/forum/forumGuide", "/mo/q/newmoindex"
        ) }
        val diagnostic = buildString {
            append("HTTP $code")
            if (endpoint != null) append("; tieba endpoint=$endpoint")
            append("; location_host=${destination?.host ?: "missing"}")
        }
        if (tieba && destination != null) {
            val host = destination.host.removeSuffix(".")
            val path = destination.encodedPath.trimEnd('/').lowercase()
            val accountHost = host == "wappass.baidu.com" || host == "passport.baidu.com"
            val loginPage = path.substringAfterLast('/') == "login" || "login" in destination.queryParameterNames
            val verification = path.split('/').any { it in setOf("verify", "verification", "validate", "captcha") }
            if (accountHost && (verification || (host == "wappass.baidu.com" && !loginPage)))
                return HttpStatusException(code, TaskStatus.MANUAL, "需要在贴吧 App 完成验证", diagnostic)
            if (accountHost || ((host == "tieba.baidu.com" || host == "tiebac.baidu.com") && loginPage))
                return HttpStatusException(code, TaskStatus.EXPIRED, "登录已失效，请重新登录", diagnostic)
        }
        return HttpStatusException(code, TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic)
    }
}
