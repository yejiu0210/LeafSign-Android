package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.platform.BilibiliAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BilibiliQrProviderTest {
    private val start = response("""{"code":0,"data":{"url":"https://bilibili.com/qr-fixture","qrcode_key":"key&=+"}}""")
    private fun poll(code: Int) = response("""{"code":0,"data":{"code":$code}}""")

    @Test fun startParsesTicketAndPollEncodesPrivateKey() {
        val transport = ScriptTransport(start, poll(86101))
        val provider = BilibiliQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals(Platform.BILIBILI, ticket.platform)
        assertEquals("https://bilibili.com/qr-fixture", ticket.content)
        assertEquals(TEST_NOW + 170_000, ticket.expiresAt)
        assertNull(ticket.image)
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals("key&=+", transport.requests.last().url.toHttpUrl().queryParameter("qrcode_key"))
        assertEquals("https://www.bilibili.com/", transport.requests.first().headers["Referer"])
        assertEquals(DESKTOP_UA, transport.requests.first().headers["User-Agent"])
    }

    @Test fun mapsWaitingScannedUnknownAndExpiredStates() {
        val transport = ScriptTransport(start, poll(86101), poll(86090), poll(99999), poll(86038))
        val provider = BilibiliQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(99999, ticket.lastStatusCode)
        assertTrue(provider.poll(ticket) is QrPoll.Expired)
    }

    @Test fun confirmedCookiesAreWhitelistedAndAcceptedByExistingAdapterParser() {
        val transport = ScriptTransport(start, response("""{"code":0,"data":{"code":0}}""",
            "SESSDATA=session; HttpOnly", "bili_jct=csrf; Path=/", "DedeUserID=123", "DedeUserID__ckMd5=md5",
            "buvid3=device", "bili_ticket=ticket", "bili_ticket_expires=1234", "sid=drop", "unknown=drop"))
        val provider = BilibiliQrProvider(transport) { TEST_NOW }
        val ticket = provider.start()
        val result = provider.poll(ticket) as QrPoll.Confirmed
        assertEquals(mapOf("SESSDATA" to "session", "bili_jct" to "csrf", "DedeUserID" to "123", "DedeUserID__ckMd5" to "md5",
            "buvid3" to "device", "bili_ticket" to "ticket", "bili_ticket_expires" to "1234"), BilibiliAdapter.parseCookies(result.credential))
        assertFalse(result.credential.contains("drop"))
        assertSame(result, provider.poll(ticket))
        assertEquals(2, transport.requests.size)
    }

    @Test fun fallbackDecodesQueryValuesWithoutOverwritingSetCookieOrAddingUnknownNames() {
        val data = JSONObject().put("code", 0).put("url", "https://passport.bilibili.com/callback?SESSDATA=url-session&bili_jct=a%2Cb%3Dc&DedeUserID=42&sid=drop")
        val transport = ScriptTransport(start, response(JSONObject().put("code", 0).put("data", data).toString(), "SESSDATA=header-session"))
        val provider = BilibiliQrProvider(transport) { TEST_NOW }
        val cookies = BilibiliAdapter.parseCookies((provider.poll(provider.start()) as QrPoll.Confirmed).credential)
        assertEquals(mapOf("SESSDATA" to "header-session", "bili_jct" to "a,b=c", "DedeUserID" to "42"), cookies)
    }

    @Test fun missingRequiredCookiesAndCookieInjectionFailWithoutLeakingValues() {
        for (cookies in listOf(listOf("SESSDATA=secret"), listOf("bili_jct=secret"), listOf("SESSDATA=secret\nHeader", "bili_jct=secret"))) {
            val transport = ScriptTransport(start, response("""{"code":0,"data":{"code":0}}""", *cookies.toTypedArray()))
            val provider = BilibiliQrProvider(transport) { TEST_NOW }
            assertFalse((provider.poll(provider.start()) as QrPoll.Failed).message.contains("secret"))
        }
    }

    @Test fun missingStartFieldsAndRejectedBusinessCodeProduceSafeErrors() {
        for (body in listOf("""{"code":0}""", """{"code":0,"data":{"url":"secret"}}""",
            """{"code":0,"data":{"qrcode_key":"secret"}}""", """{"code":-1,"message":"secret"}""")) {
            val error = assertThrows(QrLoginException::class.java) { BilibiliQrProvider(ScriptTransport(response(body))).start() }
            assertFalse(error.message!!.contains("secret"))
        }
    }

    @Test fun outerFailureAndMissingStatusCannotClaimSuccessFromCookies() {
        for (body in listOf("""{"code":-1,"message":"secret","data":{"code":0}}""", """{"code":0,"data":{}}""")) {
            val transport = ScriptTransport(start, response(body, "SESSDATA=secret", "bili_jct=secret"))
            val provider = BilibiliQrProvider(transport) { TEST_NOW }
            assertFalse((provider.poll(provider.start()) as QrPoll.Failed).message.contains("secret"))
        }
    }

    @Test fun multipleTicketsKeepTheirOwnKeysAndConfirmedCredentials() {
        val otherStart = response("""{"code":0,"data":{"url":"https://bilibili.com/other-qr","qrcode_key":"other-key"}}""")
        val success = """{"code":0,"data":{"code":0}}"""
        val transport = ScriptTransport(start, otherStart, response(success, "SESSDATA=first", "bili_jct=first-csrf"),
            response(success, "SESSDATA=second", "bili_jct=second-csrf"))
        val provider = BilibiliQrProvider(transport) { TEST_NOW }
        val first = provider.start()
        val second = provider.start()
        val firstResult = provider.poll(first) as QrPoll.Confirmed
        val secondResult = provider.poll(second) as QrPoll.Confirmed
        assertEquals("first", BilibiliAdapter.parseCookies(firstResult.credential)["SESSDATA"])
        assertEquals("second", BilibiliAdapter.parseCookies(secondResult.credential)["SESSDATA"])
        assertEquals(listOf("key&=+", "other-key"), transport.requests.takeLast(2).map { it.url.toHttpUrl().queryParameter("qrcode_key") })
        assertSame(firstResult, provider.poll(first))
        assertSame(secondResult, provider.poll(second))
        assertEquals(4, transport.requests.size)
    }
}
