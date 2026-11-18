package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.NetClient
import cn.onetap.checkin.core.RawResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody

internal data class QrResponse(val raw: RawResponse, val location: String? = null) {
    override fun toString() = "QrResponse(raw=$raw, location=<redacted>)"
}

internal interface QrTransport {
    fun request(method: String, url: String, body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(), timeoutMillis: Long = 40_000): QrResponse
    fun image(url: String, headers: Map<String, String>): ByteArray
}

internal class OfficialQrTransport(hosts: Set<String>) : QrTransport {
    private val net = NetClient(hosts)
    override fun request(method: String, url: String, body: RequestBody?, headers: Map<String, String>, timeoutMillis: Long): QrResponse {
        val response = net.rawWithLocation(method, url, body, headers, timeoutMillis)
        return QrResponse(response.response, response.location)
    }

    override fun image(url: String, headers: Map<String, String>): ByteArray {
        val parsed = baiduImageUrl(url)
        // Only the exact host returned by Baidu for this image is admitted; no wildcard network client.
        return NetClient(setOf(parsed.host)).download(parsed.toString(), headers)
    }
}

internal fun baiduImageUrl(value: String): okhttp3.HttpUrl {
    val url = when {
        value.startsWith("//") -> "https:$value"
        "://" in value -> value
        else -> "https://$value"
    }.toHttpUrl()
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        !url.host.endsWith(".baidu.com")) problem("百度二维码图片地址不受信任")
    return url
}
