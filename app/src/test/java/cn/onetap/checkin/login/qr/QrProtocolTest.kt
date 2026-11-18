package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.RawResponse
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

internal const val TEST_NOW = 1_000_000L
internal fun response(body: String, vararg cookies: String, code: Int = 200, location: String? = null) =
    QrResponse(RawResponse(code, body, cookies.toList()), location)
internal data class QrRequest(val method: String, val url: String, val body: RequestBody?, val headers: Map<String, String>, val timeout: Long) {
    fun bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()
}
internal class ScriptTransport(vararg responses: QrResponse) : QrTransport {
    val requests = mutableListOf<QrRequest>()
    val replies = java.util.ArrayDeque(responses.toList())
    val images = mutableListOf<String>()
    var imageBytes = byteArrayOf(1, 2, 3)
    var failure: Exception? = null
    var onRequest: (QrRequest) -> Unit = {}
    override fun request(method: String, url: String, body: RequestBody?, headers: Map<String, String>, timeoutMillis: Long): QrResponse {
        val request = QrRequest(method, url, body, headers, timeoutMillis)
        requests += request
        onRequest(request)
        failure?.let { throw it }
        return replies.removeFirst()
    }
    override fun image(url: String, headers: Map<String, String>): ByteArray {
        images += url
        failure?.let { throw it }
        return imageBytes
    }
}

class QrProtocolTest {
    private fun providers(transport: QrTransport) = listOf(BilibiliQrProvider(transport) { TEST_NOW },
        NeteaseQrProvider(transport, { TEST_NOW }), SklandQrProvider(transport) { TEST_NOW }, TiebaQrProvider(transport, { TEST_NOW }))
    private fun ticket(platform: Platform) = QrTicket(platform, "secret-content", expiresAt = TEST_NOW + QR_LIFETIME_MS,
        state = mapOf("qrcode_key" to "secret-key", "unikey" to "secret-key", "type" to "3", "channel" to "eapi",
            "deviceId" to "0123456789abcdef0123456789abcdef", "scanId" to "secret-key", "sign" to "secret-key"))

    @Test fun allProvidersSanitizeNetworkErrorsInStartAndPoll() {
        val transport = ScriptTransport().apply { failure = IOException("Cookie=secret-network-token") }
        for (provider in providers(transport)) {
            val error = assertThrows(QrLoginException::class.java) { provider.start() }
            assertEquals("网络连接失败，请检查网络后重试", error.message)
            assertNull(error.cause)
            assertEquals(QrPoll.Failed(error.message!!), provider.poll(ticket(provider.platform)))
        }
    }

    @Test fun allProvidersSanitizeUnexpectedExceptionsAndMalformedServerBodies() {
        for (provider in providers(ScriptTransport().apply { failure = IllegalStateException("secret-cookie") })) {
            assertFalse(assertThrows(QrLoginException::class.java) { provider.start() }.toString().contains("secret"))
            val result = provider.poll(ticket(provider.platform)) as QrPoll.Failed
            assertFalse(result.message.contains("secret"))
        }
        for (platform in Platform.entries) {
            val provider = providers(ScriptTransport(response("not-json-secret-token"))).first { it.platform == platform }
            assertFalse(assertThrows(QrLoginException::class.java) { provider.start() }.toString().contains("secret"))
        }
    }

    @Test fun expiredAndWrongPlatformTicketsNeverReachTheNetwork() {
        val transport = ScriptTransport()
        for (provider in providers(transport)) {
            assertTrue(provider.poll(ticket(provider.platform).copy(expiresAt = TEST_NOW)) is QrPoll.Expired)
            val other = Platform.entries.first { it != provider.platform }
            assertTrue(provider.poll(ticket(other)) is QrPoll.Failed)
            assertTrue(provider.poll(ticket(provider.platform).copy(state = emptyMap())) is QrPoll.Failed)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun wrapperAndJsonpParserDecodeOnlyDataIncludingHtmlEntities() {
        assertEquals(0, parseWrappedJson(" (( {\"errno\":0} )) ; ").getInt("errno"))
        val root = parseBaiduJsonp("bd__cbs__cupstt({&quot;errInfo&quot;:{&quot;no&quot;:&#34;0&#34;},&quot;x&quot;:&quot;&amp;lt;&#x4e2d;&quot;});")
        assertEquals("0", root.getJSONObject("errInfo").getString("no"))
        assertEquals("&lt;中", root.getString("x"))
    }

    @Test fun cookieParserFiltersAttributesAndUnknownNamesAndPreservesEquals() {
        assertEquals(mapOf("BDUSS" to "a=b"), cookieValues(listOf("BDUSS=a=b; Path=/", "unknown=secret; Secure"), setOf("BDUSS")))
        assertTrue(cookieValues(listOf("BDUSS=a", "BDUSS=; Max-Age=0"), setOf("BDUSS")).isEmpty())
        assertThrows(QrProblem::class.java) { cookieValues(listOf("BDUSS=a\r\nsecret"), setOf("BDUSS")) }
    }

    @Test fun sensitiveModelToStringIsRedactedAndFactoryCoversEveryPlatform() {
        assertFalse(ticket(Platform.BILIBILI).toString().contains("secret"))
        assertFalse(QrPoll.Confirmed("secret-credential").toString().contains("secret"))
        for (platform in Platform.entries) assertEquals(platform, QrLogin.provider(platform).platform)
    }
}
