package cn.onetap.checkin.login.qr

import cn.onetap.checkin.platform.TiebaAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TiebaQrProviderTest {
    private val start = response(""" ( {"errno":0,"sign":"sign&=+","imgurl":"passport.baidu.com/qr-image"} ) """)
    private fun event(status: Int, value: String? = null): QrResponse {
        val channel = JSONObject().put("status", status)
        if (value != null) channel.put("v", value)
        return response(JSONObject().put("errno", 0).put("channel_v", channel.toString()).toString())
    }
    private fun exchanged(vararg cookies: String) = response("bd__cbs__cupstt({&quot;errInfo&quot;:{&quot;no&quot;:&quot;0&quot;}});", *cookies)
    private fun provider(transport: QrTransport) = TiebaQrProvider(transport, { TEST_NOW })

    @Test fun startParsesWrappedJsonDownloadsImageAndBuildsContentWithEncodedSign() {
        val transport = ScriptTransport(start)
        val ticket = provider(transport).start()
        assertArrayEquals(transport.imageBytes, ticket.image)
        assertEquals(listOf("https://passport.baidu.com/qr-image"), transport.images)
        val content = ticket.content.toHttpUrl()
        assertEquals("wappass.baidu.com", content.host)
        assertEquals("sign&=+", content.queryParameter("sign"))
        assertTrue(ticket.content.contains("?qrlogin&t="))
        assertEquals("tb", content.queryParameter("tpl"))
        assertEquals(TEST_NOW + QR_LIFETIME_MS, ticket.expiresAt)
        assertEquals(DESKTOP_UA, transport.requests.single().headers["User-Agent"])
        assertEquals("https://tieba.baidu.com/", transport.requests.single().headers["Referer"])
    }

    @Test fun rejectedTplFallsBackToOnlyLpAndTimestamp() {
        val transport = ScriptTransport(response("""{"errno":1}"""), start)
        provider(transport).start()
        assertEquals(setOf("lp", "_"), transport.requests.last().url.toHttpUrl().queryParameterNames)
        assertEquals(2, transport.requests.size)
    }

    @Test fun mapsLongPollTimeoutScannedUnknownAndLocalExpiry() {
        var now = TEST_NOW
        val transport = ScriptTransport(start, response("""{"errno":1,"message":"secret"}"""), event(1), event(55))
        val provider = TiebaQrProvider(transport, { now })
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals("sign&=+", transport.requests.last().url.toHttpUrl().queryParameter("channel_id"))
        now = ticket.expiresAt
        assertTrue(provider.poll(ticket) is QrPoll.Expired)
        assertEquals(4, transport.requests.size)
    }

    @Test fun confirmationUsesExchangedBdussAnd302StokenWithCookieWhitelist() {
        val transport = ScriptTransport(start, event(0, "temporary&=+"), exchanged("BDUSS=official; HttpOnly", "PTOKEN=ptoken", "OTHER=drop"),
            response("", "STOKEN=stoken; Path=/", "BDUSS=must-not-replace", code = 302))
        val provider = provider(transport)
        val ticket = provider.start()
        val result = provider.poll(ticket) as QrPoll.Confirmed
        assertEquals(mapOf("BDUSS" to "official", "STOKEN" to "stoken"), TiebaAdapter().credentials(result.credential))
        assertEquals("temporary&=+", transport.requests[2].url.toHttpUrl().queryParameter("bduss"))
        assertEquals("BDUSS=official; PTOKEN=ptoken", transport.requests[3].headers["Cookie"])
        assertSame(result, provider.poll(ticket))
        assertEquals(4, transport.requests.size)
    }

    @Test fun followsExactlyOneHttpsTiebaLocationToGetStoken() {
        val transport = ScriptTransport(start, event(0, "temporary"), exchanged("BDUSS=official"),
            response("", code = 302, location = "https://tieba.baidu.com/login?ticket=fixture"),
            response("", "STOKEN=stoken", code = 302, location = "https://tieba.baidu.com/not-followed"))
        val provider = provider(transport)
        assertEquals(QrPoll.Confirmed("BDUSS=official; STOKEN=stoken"), provider.poll(provider.start()))
        assertEquals(5, transport.requests.size)
        assertEquals("https://tieba.baidu.com/login?ticket=fixture", transport.requests.last().url)
        assertEquals("BDUSS=official", transport.requests.last().headers["Cookie"])
    }

    @Test fun unsafeLocationsNeverReceiveCookiesAndMissingStokenIsNotFailure() {
        for (location in listOf("http://tieba.baidu.com/", "https://tieba.baidu.com.evil.test/", "https://tieba.baidu.com@evil.test/",
            "https://tieba.baidu.com:444/", "https://passport.baidu.com/", "//tieba.baidu.com/", "https://tieba.baidu.com./")) {
            val transport = ScriptTransport(start, event(0, "temporary"), exchanged("BDUSS=official"), response("", code = 302, location = location))
            val provider = provider(transport)
            assertEquals(QrPoll.Confirmed("BDUSS=official"), provider.poll(provider.start()))
            assertEquals(4, transport.requests.size)
        }
    }

    @Test fun optionalStokenRequestsShareFifteenSecondBudgetAndFailurePreservesBduss() {
        var clock = 0L
        val transport = ScriptTransport(start, event(0, "temporary"), exchanged("BDUSS=official"),
            response("", code = 302, location = "https://tieba.baidu.com/login"))
        transport.onRequest = {
            if (it.url.contains("/api/auth/")) clock += 14_000
            if (it.url.startsWith("https://tieba.baidu.com/")) throw IOException("secret")
        }
        val provider = TiebaQrProvider(transport, { TEST_NOW }, { clock })
        assertEquals(QrPoll.Confirmed("BDUSS=official"), provider.poll(provider.start()))
        assertEquals(listOf(15_000L, 1_000L), transport.requests.takeLast(2).map { it.timeout })
    }

    @Test fun expiredOptionalBudgetPreventsSecondRequest() {
        var clock = 0L
        val transport = ScriptTransport(start, event(0, "temporary"), exchanged("BDUSS=official", "STOKEN=existing"),
            response("", code = 302, location = "https://tieba.baidu.com/login"))
        transport.onRequest = { if (it.url.contains("/api/auth/")) clock += 15_000 }
        val provider = TiebaQrProvider(transport, { TEST_NOW }, { clock })
        assertEquals(QrPoll.Confirmed("BDUSS=official; STOKEN=existing"), provider.poll(provider.start()))
        assertEquals(4, transport.requests.size)
    }

    @Test fun onlyHttpsBaiduSubdomainsMaySupplyImages() {
        assertEquals("https://img.baidu.com/qr", baiduImageUrl("//img.baidu.com/qr").toString())
        for (url in listOf("http://img.baidu.com/qr", "https://baidu.com/qr", "https://img.baidu.com.evil.test/qr",
            "https://img.baidu.com:444/qr", "https://name:password@img.baidu.com/qr", "https://img.baidu.com./qr")) {
            val body = JSONObject().put("errno", 0).put("sign", "secret").put("imgurl", url)
            val transport = ScriptTransport(response(body.toString()))
            assertThrows(QrLoginException::class.java) { provider(transport).start() }
            assertTrue(transport.images.isEmpty())
        }
    }

    @Test fun missingStartFieldsOrEmptyImageFailClearly() {
        for (body in listOf("""{"errno":0,"sign":"secret"}""", """{"errno":0,"imgurl":"passport.baidu.com/qr"}""")) {
            assertFalse(assertThrows(QrLoginException::class.java) { provider(ScriptTransport(response(body))).start() }.message!!.contains("secret"))
        }
        val transport = ScriptTransport(start).apply { imageBytes = byteArrayOf() }
        assertTrue(assertThrows(QrLoginException::class.java) { provider(transport).start() }.message!!.contains("图片为空"))
    }

    @Test fun missingChannelValueAndConfirmedValueDoNotStartExchange() {
        for (reply in listOf(response("""{"errno":0}"""), event(0))) {
            val transport = ScriptTransport(start, reply)
            val provider = provider(transport)
            assertTrue(provider.poll(provider.start()) is QrPoll.Failed)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun missingBdussAndExchangeBusinessFailureNeverUseTemporaryCredential() {
        for (reply in listOf(exchanged("PTOKEN=secret"), response("cb({\"errInfo\":{\"no\":\"1\"},\"msg\":\"secret\"});", "BDUSS=secret"))) {
            val transport = ScriptTransport(start, event(0, "secret-temporary"), reply)
            val provider = provider(transport)
            assertFalse((provider.poll(provider.start()) as QrPoll.Failed).message.contains("secret"))
            assertEquals(3, transport.requests.size)
        }
    }
}
