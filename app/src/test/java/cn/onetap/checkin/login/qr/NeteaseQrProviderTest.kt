package cn.onetap.checkin.login.qr

import cn.onetap.checkin.platform.NeteaseAdapter
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NeteaseQrProviderTest {
    private val start = response("""{"code":200,"unikey":"key&=+"}""")
    private fun provider(transport: QrTransport, sent: MutableList<JSONObject> = mutableListOf()) =
        NeteaseQrProvider(transport, { TEST_NOW },
            encryptWeapi = { data -> sent += JSONObject(data); mapOf("params" to "encrypted", "encSecKey" to "encrypted-key") },
            encryptEapi = { _, data -> sent += JSONObject(data); mapOf("params" to "encrypted-eapi") })

    @Test fun startDefaultsToTypeThreeAndEapiPcHeadersWithStableDeviceId() {
        val transport = ScriptTransport(start)
        val sent = mutableListOf<JSONObject>()
        val ticket = provider(transport, sent).start()
        assertEquals("key&=+", ticket.content.toHttpUrl().queryParameter("codekey"))
        assertEquals("3", ticket.state["type"])
        assertEquals("eapi", ticket.state["channel"])
        assertTrue(ticket.state.getValue("deviceId").matches(Regex("[0-9a-f]{32}")))
        assertEquals(TEST_NOW + QR_LIFETIME_MS, ticket.expiresAt)
        assertEquals(3, sent.single().getInt("type"))
        assertFalse(sent.single().getBoolean("e_r"))
        val header = sent.single().getJSONObject("header")
        assertEquals(ticket.state["deviceId"], header.getString("deviceId"))
        assertEquals("pc", header.getString("os"))
        assertEquals("3.1.17.204416", header.getString("appver"))
        assertEquals("Microsoft-Windows-10-Professional-build-19045-64bit", header.getString("osver"))
        assertEquals("netease", header.getString("channel"))
        assertEquals("140", header.getString("versioncode"))
        assertEquals("1920x1080", header.getString("resolution"))
        assertEquals((TEST_NOW / 1000).toString(), header.getString("buildver"))
        assertEquals("", header.getString("__csrf"))
        assertTrue(header.getString("requestId").startsWith("${TEST_NOW}_"))
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://interfacepc.music.163.com/eapi/login/qrcode/unikey", request.url)
        assertEquals("https://music.163.com/", request.headers["Referer"])
        assertTrue(request.headers.getValue("User-Agent").contains("Windows NT 10.0"))
        assertEquals(NeteaseAdapter.eapiCookie(header), request.headers["Cookie"])
        val body = request.body as FormBody
        assertEquals(setOf("params"), (0 until body.size).map { body.name(it) }.toSet())
    }

    @Test fun fallbackRetriesOnlyOnceWithTypeThreeAndPollKeepsThatType() {
        val transport = ScriptTransport(response("""{"code":400}""", "NMTID=eapi-session"),
            response("""{"code":400}""", "NMTID=weapi-one-session"),
            response("""{"data":{"code":200,"unikey":"key3"}}""", "NMTID=weapi-three-session"), response("""{"code":801}"""))
        val sent = mutableListOf<JSONObject>()
        val provider = provider(transport, sent)
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(listOf(3, 1, 3, 3), sent.map { it.getInt("type") })
        assertEquals("weapi", ticket.state["channel"])
        assertTrue(transport.requests[1].headers.getValue("Cookie").contains("NMTID=eapi-session"))
        assertTrue(transport.requests[2].headers.getValue("Cookie").contains("NMTID=weapi-one-session"))
        assertTrue(transport.requests[3].headers.getValue("Cookie").contains("NMTID=weapi-three-session"))
        assertTrue(transport.requests.drop(1).all { it.headers.getValue("Cookie").contains("deviceId=${ticket.state.getValue("deviceId")}") })
        assertTrue(sent.drop(1).all { it.getString("csrf_token").isEmpty() })
        assertEquals("key3", sent.last().getString("key"))
        assertTrue(transport.requests.last().url.endsWith("/client/login"))
    }

    @Test fun successfulOuterEnvelopeCanContainUnikeyInData() {
        val ticket = provider(ScriptTransport(response("""{"code":200,"data":{"unikey":"wrapped"}}"""))).start()
        assertEquals("wrapped", ticket.state["unikey"])
    }

    @Test fun mapsWaitingScannedExpiredAndUnknownStates() {
        val transport = ScriptTransport(start, response("""{"code":801}"""), response("""{"code":802}"""), response("""{"code":800}"""))
        val provider = provider(transport)
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        assertTrue(provider.poll(ticket) is QrPoll.Expired)
        val unknown = provider(ScriptTransport(start, response("""{"code":999,"message":"状态已更新"}""")))
        assertEquals(QrPoll.Failed("网易云返回状态 999：状态已更新，请重新扫码或使用备用登录"), unknown.poll(unknown.start()))
    }

    @Test fun successRequiresCookieAndFiltersEverythingExceptMusicUAndCsrf() {
        for (optional in listOf(false, true)) {
            val cookies = mutableListOf("MUSIC_U=fixture=a; HttpOnly", "MUSIC_A=drop", "os=drop")
            if (optional) cookies += "__csrf=csrf; Path=/"
            val provider = provider(ScriptTransport(start, response("""{"code":803}""", *cookies.toTypedArray())))
            val result = provider.poll(provider.start()) as QrPoll.Confirmed
            val parsed = NeteaseAdapter().credentials(result.credential)
            assertEquals("fixture=a", parsed["MUSIC_U"])
            assertEquals(if (optional) "csrf" else null, parsed["__csrf"])
            assertFalse(result.credential.contains("drop"))
        }
    }

    @Test fun bodyCookieSuppliesMusicUWhileHeaderCsrfTakesPriority() {
        val provider = provider(ScriptTransport(start, response("""{"code":803,"cookie":"MUSIC_U=body-session; __csrf=body-csrf; MUSIC_A=drop; NMTID=drop","message":"登录成功"}""", "__csrf=header-csrf")))
        val result = provider.poll(provider.start()) as QrPoll.Confirmed
        assertEquals(mapOf("MUSIC_U" to "body-session", "__csrf" to "header-csrf"), NeteaseAdapter().credentials(result.credential))
        assertFalse(result.toString().contains("body-session"))
    }

    @Test fun missingFieldsFailAndNetworkErrorDoesNotTriggerTypeFallback() {
        for (body in listOf("{}", """{"code":200,"message":"secret"}""")) {
            val transport = ScriptTransport(response(body), response(body))
            assertFalse(assertThrows(QrLoginException::class.java) { provider(transport).start() }.message!!.contains("secret"))
            assertEquals(2, transport.requests.size)
        }
        val transport = ScriptTransport().apply { failure = IOException("secret") }
        assertThrows(QrLoginException::class.java) { provider(transport).start() }
        assertEquals(1, transport.requests.size)
    }

    @Test fun channelAndBothTypeFailuresStopAfterThreeRequestsWithoutEchoingServerMessage() {
        val transport = ScriptTransport(response("""{"code":400}"""), response("""{"code":400}"""), response("""{"code":500,"message":"secret"}"""))
        assertFalse(assertThrows(QrLoginException::class.java) { provider(transport).start() }.message!!.contains("secret"))
        assertEquals(3, transport.requests.size)
    }

    @Test fun defaultProviderUsesRealEapiEncryptionAndCorrectEncryptedPaths() {
        val transport = ScriptTransport(start, response("""{"code":801}"""))
        val provider = NeteaseQrProvider(transport, { TEST_NOW })
        val ticket = provider.start()
        provider.poll(ticket)
        for ((request, path) in transport.requests.zip(listOf("unikey", "client/login"))) {
            val body = request.body as FormBody
            assertEquals(1, body.size)
            assertEquals("params", body.name(0))
            val bytes = body.value(0).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
            val parts = cipher.doFinal(bytes).toString(Charsets.UTF_8).split("-36cd479b6b5-")
            assertEquals("/api/login/qrcode/$path", parts[0])
            val data = JSONObject(parts[1])
            assertFalse(data.getBoolean("e_r"))
            assertEquals(ticket.state["deviceId"], data.getJSONObject("header").getString("deviceId"))
            assertEquals(3, data.getInt("type"))
        }
    }

    @Test fun sessionCookiesRotateAccumulateAndDeleteWithoutRetainingCredentials() {
        val transport = ScriptTransport(response(start.raw.body, "NMTID=first", "_ntes_nuid=nuid", "WNMCID=remove-me",
            "MUSIC_U=do-not-retain", "MUSIC_A=do-not-retain", "__csrf=do-not-retain", "OTHER=do-not-retain"),
            response("""{"code":801}""", "NMTID=second", "_ntes_nnid=nnid", "WNMCID=; Max-Age=0", "WEVNSM=wevnsm"),
            response("""{"code":802}"""))
        val sent = mutableListOf<JSONObject>()
        val provider = provider(transport, sent)
        val ticket = provider.start()
        assertEquals(QrPoll.Waiting, provider.poll(ticket))
        assertEquals(QrPoll.Scanned, provider.poll(ticket))
        val firstCheck = transport.requests[1].headers.getValue("Cookie")
        val secondCheck = transport.requests[2].headers.getValue("Cookie")
        assertTrue(firstCheck.contains("NMTID=first"))
        assertTrue(firstCheck.contains("_ntes_nuid=nuid"))
        assertTrue(secondCheck.contains("NMTID=second"))
        assertTrue(secondCheck.contains("_ntes_nnid=nnid"))
        assertTrue(secondCheck.contains("WEVNSM=wevnsm"))
        assertFalse(secondCheck.contains("WNMCID="))
        assertTrue(transport.requests.all { !it.headers.getValue("Cookie").contains("do-not-retain") })
        assertEquals(1, sent.map { it.getJSONObject("header").getString("deviceId") }.distinct().size)
        synchronized(ticket) {
            assertEquals(setOf("NMTID", "_ntes_nuid", "_ntes_nnid", "WEVNSM"), ticket.sessionCookies.keys)
        }
    }

    @Test fun unusableEapiResponsesFallBackToWeapiWithSessionCookies() {
        for (bad in listOf(response("not-json", "NMTID=eapi-session"), response("{}", "NMTID=eapi-session"),
            response("""{"code":200}""", "NMTID=eapi-session"), response("", "NMTID=eapi-session", code = 500))) {
            val transport = ScriptTransport(bad, response(start.raw.body, "NMTID=weapi-session"), response("""{"code":801}"""))
            val provider = provider(transport)
            val ticket = provider.start()
            assertEquals("weapi", ticket.state["channel"])
            assertEquals("1", ticket.state["type"])
            assertTrue(transport.requests[1].headers.getValue("Cookie").contains("NMTID=eapi-session"))
            assertEquals(QrPoll.Waiting, provider.poll(ticket))
            assertEquals("music.163.com", transport.requests[2].url.toHttpUrl().host)
            assertTrue(transport.requests[2].headers.getValue("Cookie").contains("NMTID=weapi-session"))
        }
    }

    @Test fun fallbackStillUsesRealWeapiEncryption() {
        val transport = ScriptTransport(response("""{"code":500}"""), start)
        NeteaseQrProvider(transport, { TEST_NOW }).start()
        val body = transport.requests.last().body as FormBody
        assertEquals("params", body.name(0))
        assertEquals("encSecKey", body.name(1))
        assertEquals(256, body.value(1).length)
        assertFalse(transport.requests.last().bodyText().contains("csrf_token"))
    }

    @Test fun behaviorVerificationHasSpecificMessageAndNeverChangesCheckChannel() {
        val transport = ScriptTransport(start, response("""{"code":8821,"message":"ignored"}"""))
        val provider = provider(transport)
        val ticket = provider.start()
        val result = provider.poll(ticket)
        assertEquals(QrPoll.Failed("网易云要求进行行为验证（8821），请稍后重试，或改用网页登录 / 手动导入"), result)
        assertEquals(8821, ticket.lastStatusCode)
        assertSame(result, provider.poll(ticket))
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.url.toHttpUrl().host == "interfacepc.music.163.com" })
    }

    @Test fun unknownStatusMessageDropsControlCharactersAndTruncatesToSixtyCharacters() {
        val message = "状态\n\r\t\u0000\u202E" + "测".repeat(80)
        val body = JSONObject().put("code", 9999).put("message", message)
        val provider = provider(ScriptTransport(start, response(body.toString(), "NMTID=never-print-this")))
        assertEquals(QrPoll.Failed("网易云返回状态 9999：状态${"测".repeat(58)}，请重新扫码或使用备用登录"), provider.poll(provider.start()))
    }

    @Test fun unknownStatusCannotEchoCookiesEvenIfServerIncludesThem() {
        val body = JSONObject().put("code", 999).put("message", "old-session new-session auth-secret csrf-secret body-secret")
            .put("cookie", "MUSIC_U=body-secret")
        val transport = ScriptTransport(response(start.raw.body, "NMTID=old-session"),
            response(body.toString(), "NMTID=new-session", "MUSIC_U=auth-secret", "__csrf=csrf-secret"))
        val provider = provider(transport)
        val result = provider.poll(provider.start()) as QrPoll.Failed
        assertFalse(result.message.contains("secret"))
        assertFalse(result.message.contains("session"))
        assertTrue(result.message.contains("999"))
    }

    @Test fun wrappedJsonCookieFallbackAndHeaderPriorityBothPreserveCredentialWhitelist() {
        val wrapped = provider(ScriptTransport(start, response("""{"data":{"code":803,"cookie":"MUSIC_U=body-session; __csrf=body-csrf; MUSIC_A=drop; Path=/"}}""")))
        assertEquals(mapOf("MUSIC_U" to "body-session", "__csrf" to "body-csrf"),
            NeteaseAdapter().credentials((wrapped.poll(wrapped.start()) as QrPoll.Confirmed).credential))
        val headers = provider(ScriptTransport(start, response("""{"code":803,"cookie":"MUSIC_U=wrong; __csrf=wrong"}""",
            "MUSIC_U=header-session", "__csrf=header-csrf")))
        assertEquals(mapOf("MUSIC_U" to "header-session", "__csrf" to "header-csrf"),
            NeteaseAdapter().credentials((headers.poll(headers.start()) as QrPoll.Confirmed).credential))
    }

    @Test fun missingMusicUInBothSourcesStillFailsWithoutExposingCookieValues() {
        val provider = provider(ScriptTransport(start, response("""{"code":803,"cookie":"MUSIC_A=secret; __csrf=secret"}""")))
        val result = provider.poll(provider.start()) as QrPoll.Failed
        assertTrue(result.message.contains("MUSIC_U"))
        assertFalse(result.message.contains("secret"))
    }

    @Test fun differentTicketsNeverShareDeviceIdsOrSessionsEvenWhenProviderIsRecreated() {
        val transport = ScriptTransport(response(start.raw.body, "NMTID=account-one"), response(start.raw.body, "NMTID=account-two"),
            response("""{"code":801}"""), response("""{"code":801}"""))
        val provider = provider(transport)
        val first = provider.start()
        val second = provider.start()
        assertNotEquals(first.state["deviceId"], second.state["deviceId"])
        assertEquals(QrPoll.Waiting, provider.poll(first))
        assertEquals(QrPoll.Waiting, provider(transport).poll(second))
        assertTrue(transport.requests[2].headers.getValue("Cookie").contains("NMTID=account-one"))
        assertFalse(transport.requests[2].headers.getValue("Cookie").contains("account-two"))
        assertTrue(transport.requests[3].headers.getValue("Cookie").contains("NMTID=account-two"))
        assertTrue(transport.requests[3].headers.getValue("Cookie").contains("deviceId=${second.state["deviceId"]}"))
        assertFalse(first.toString().contains("account-one"))
    }

    @Test fun simultaneousPollsSerializeSessionRotation() {
        val transport = ScriptTransport(response(start.raw.body, "NMTID=before"), response("""{"code":801}""", "NMTID=after"), response("""{"code":802}"""))
        val provider = provider(transport)
        val ticket = provider.start()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        transport.onRequest = {
            maximum.updateAndGet { previous -> maxOf(previous, active.incrementAndGet()) }
            try { Thread.sleep(10) } finally { active.decrementAndGet() }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { provider.poll(ticket) } }).map { it.get() }
            assertEquals(setOf(QrPoll.Waiting, QrPoll.Scanned), results.toSet())
            assertEquals(1, maximum.get())
            assertTrue(transport.requests[2].headers.getValue("Cookie").contains("NMTID=after"))
        } finally { pool.shutdownNow() }
    }
}
