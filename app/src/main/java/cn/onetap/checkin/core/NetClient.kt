package cn.onetap.checkin.core

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Exact host allowlist, TLS only, no redirects, no ambient cookies or HTTP logging. */
class NetClient(private val allowedHosts: Set<String>) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

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
        if(Thread.currentThread().isInterrupted) throw InterruptedIOException("request cancelled")
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host in allowedHosts && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty()) { "平台域名不在允许列表" }
        val req = Request.Builder().url(parsed).header("User-Agent", "OneTapCheckIn/0.1 Android")
            .apply { headers.forEach { (k,v) -> header(k,v) } }.method(method, body).build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401) throw AdapterException(TaskStatus.EXPIRED, "登录凭证已失效", "HTTP 401")
            if (response.code == 403 || response.code == 412 || response.code == 429)
                throw AdapterException(TaskStatus.MANUAL, "平台限制请求，请在官方入口完成验证后再试", "HTTP ${response.code}")
            if (!response.isSuccessful) throw AdapterException(TaskStatus.UNKNOWN, "服务器响应异常，结果待确认", "HTTP ${response.code}")
            val raw = response.body?.string() ?: throw IOException("empty response")
            if (raw.length > 4_000_000) throw AdapterException(TaskStatus.UNKNOWN, "响应超出限制")
            return try { JSONObject(raw) } catch (_: Exception) { throw AdapterException(TaskStatus.UNKNOWN, "平台返回非 JSON 数据，结果待确认", "HTTP ${response.code}; non-json") }
        }
    }
}
