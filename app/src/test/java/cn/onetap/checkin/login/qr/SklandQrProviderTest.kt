package cn.onetap.checkin.login.qr

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SklandQrProviderTest {
    private val start = response("""{"status":0,"data":{"scanId":"id&=+","scanUrl":"arknights://scan?fixture=1","enableScanAppList":[{"name":"森空岛"},{"name":"明日方舟"}]}}""")
    private val scanned = response("""{"status":0,"data":{"scanCode":"one-use-secret"}}""")

    @Test fun startPreservesCustomSchemeAndAppNamesAndUsesRequiredHeaders() {
        val transport = ScriptTransport(start, response("""{"status":100}"""))
        val provider = SklandQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals("arknights://scan?fixture=1", ticket.content)
        assertEquals("森空岛,明日方舟", ticket.state["apps"])
        assertEquals(TEST_NOW + QR_LIFETIME_MS, ticket.expiresAt)
        assertEquals("{}", transport.requests.first().bodyText())
        assertEquals("https://user.hypergryph.com", transport.requests.first().headers["Origin"])
        assertEquals("application/json", transport.requests.first().headers["Content-Type"])
        assertEquals(DESKTOP_UA, transport.requests.first().headers["User-Agent"])
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals("id&=+", transport.requests.last().url.toHttpUrl().queryParameter("scanId"))
    }

    @Test fun optionalAppsMayBeAbsent() {
        val provider = SklandQrProvider(ScriptTransport(response("""{"status":0,"data":{"scanId":"id","scanUrl":"https://user.hypergryph.com/scan"}}""")))
        assertNull(provider.start().state["apps"])
    }

    @Test fun mapsWaitingScannedUnknownAndExplicitCancellation() {
        val transport = ScriptTransport(start, response("""{"status":100}"""), response("""{"status":101}"""),
            response("""{"status":555}"""), response("""{"status":3}"""))
        val provider = SklandQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Expired("已在手机上取消登录"), provider.poll(ticket))
    }

    @Test fun statusFourExpiresTicket() {
        val provider = SklandQrProvider(ScriptTransport(start, response("""{"status":4}"""))) { TEST_NOW }
        assertEquals(QrPoll.Expired("二维码已过期"), provider.poll(provider.start()))
    }

    @Test fun exchangesScanCodeForRawTokenExactlyOnceAcrossRepeatedPolls() {
        val token = "fixture-token-123456789"
        val transport = ScriptTransport(start, scanned, response("""{"status":0,"data":{"token":"$token"}}"""))
        val provider = SklandQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        val result = provider.poll(ticket)
        assertEquals(QrPoll.Confirmed(token), result)
        assertEquals("one-use-secret", JSONObject(transport.requests.last().bodyText()).getString("scanCode"))
        assertTrue(transport.requests.last().url.endsWith("/user/auth/v1/token_by_scan_code"))
        assertSame(result, SklandQrProvider(transport) { TEST_NOW }.poll(ticket))
        assertEquals(3, transport.requests.size)
    }

    @Test fun failedOneUseExchangeIsNotBlindlyRetriedAndDoesNotLeakExceptionText() {
        val transport = ScriptTransport(start, scanned).apply {
            onRequest = { if (it.url.endsWith("token_by_scan_code")) throw IOException("one-use-secret") }
        }
        val provider = SklandQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        val result = provider.poll(ticket) as QrPoll.Failed
        assertEquals("网络连接失败，请检查网络后重试", result.message)
        assertSame(result, provider.poll(ticket))
        assertEquals(3, transport.requests.size)
    }

    @Test fun missingStartAndScanCodeFieldsFailWithoutEchoingResponses() {
        for (body in listOf("""{"status":0}""", """{"status":0,"data":{"scanId":"secret"}}""",
            """{"status":0,"data":{"scanUrl":"secret"}}""", """{"status":9,"message":"secret"}""")) {
            assertFalse(assertThrows(QrLoginException::class.java) { SklandQrProvider(ScriptTransport(response(body))).start() }.message!!.contains("secret"))
        }
        val provider = SklandQrProvider(ScriptTransport(start, response("""{"status":0,"data":{"message":"secret"}}"""))) { TEST_NOW }
        val result = provider.poll(provider.start()) as QrPoll.Failed
        assertTrue(result.message.contains("scanCode"))
        assertFalse(result.message.contains("secret"))
    }

    @Test fun invalidTokensAndFailedExchangeBusinessStatusCannotConfirm() {
        val bodies = listOf(JSONObject().put("status", 0).put("data", JSONObject()), JSONObject().put("status", 9).put("message", "secret")) +
            listOf("short-secret", "x".repeat(4097), "has spaces secret token", "secret-token-123\n456").map {
                JSONObject().put("status", 0).put("data", JSONObject().put("token", it))
            }
        for (body in bodies) {
            val provider = SklandQrProvider(ScriptTransport(start, scanned, response(body.toString()))) { TEST_NOW }
            assertFalse((provider.poll(provider.start()) as QrPoll.Failed).message.contains("secret"))
        }
    }
}
