package cn.onetap.checkin.platform

import okhttp3.HttpUrl.Companion.toHttpUrl

internal object HeyboxWebRequest {
    const val HOST = "api.xiaoheihe.cn"
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "https://xiaoheihe.cn/",
        "Origin" to "https://www.xiaoheihe.cn",
        "Accept" to "application/json, text/plain, */*"
    )
    private val clientParameters = mapOf(
        "os_type" to "web", "app" to "web", "client_type" to "web", "web_version" to "2.5",
        "x_client_type" to "web", "x_app" to "heybox_website", "x_os_type" to "Windows",
        "device_info" to "Chrome", "device_id" to ""
    )

    // The signer supplies version along with the signature fields.
    fun parameters(path: String, now: Long = System.currentTimeMillis()) =
        clientParameters + HeyboxWebSigning.parameters(path, now)

    fun url(path: String, parameters: Map<String, String>) = "https://$HOST$path".toHttpUrl().newBuilder()
        .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build().toString()
}
