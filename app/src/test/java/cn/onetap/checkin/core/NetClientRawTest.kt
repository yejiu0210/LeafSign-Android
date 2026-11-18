package cn.onetap.checkin.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetClientRawTest {
    private fun client(code: Int, body: ResponseBody = "fixture".toResponseBody(), cookies: List<String> = emptyList()): NetClient {
        val engine = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body).header("Location", "https://example.invalid/never-follow")
                .apply { cookies.forEach { addHeader("Set-Cookie", it) } }.build()
        }.build()
        return NetClient(setOf("music.163.com"), engine)
    }

    @Test fun redirectsReturnBodyAndStrippedCookiePairsWithoutFollowingLocation() {
        val result = client(302, "callback({})".toResponseBody(), listOf("MUSIC_U=a=b; Path=/; Secure; HttpOnly", "__csrf=c; SameSite=Lax"))
            .rawWithLocation("GET", "https://music.163.com/")
        assertEquals(302, result.response.code)
        assertEquals("callback({})", result.response.body)
        assertEquals(listOf("MUSIC_U=a=b", "__csrf=c"), result.response.setCookies)
        assertEquals("https://example.invalid/never-follow", result.location)
    }

    @Test fun rawReturnsHttpFailuresWhileExistingJsonRequestStillRejectsThem() {
        for (code in listOf(401, 403, 412, 429, 500)) {
            assertEquals(code, client(code).raw("GET", "https://music.163.com/").code)
            assertThrows(AdapterException::class.java) { client(code).get("https://music.163.com/") }
        }
    }

    @Test fun tiebaJsonRedirectsClassifyDestinationAndKeepOnlyItsHostInDiagnostics() {
        val cases = listOf(
            Triple(302, "https://wappass.baidu.com/v3/validate?token=fixture-secret", TaskStatus.MANUAL) to "wappass.baidu.com",
            Triple(303, "https://wappass.baidu.com/wp/login?ticket=fixture-secret", TaskStatus.EXPIRED) to "wappass.baidu.com",
            Triple(302, "https://passport.baidu.com/v2/?login&token=fixture-secret", TaskStatus.EXPIRED) to "passport.baidu.com",
            Triple(307, "/login?token=fixture-secret", TaskStatus.EXPIRED) to "tiebac.baidu.com",
            Triple(302, "http://tiebac.baidu.com/c/c/forum/sign?token=fixture-secret", TaskStatus.UNKNOWN) to "tiebac.baidu.com",
            Triple(302, "https://user:fixture-secret@wappass.baidu.com.example.org/verify", TaskStatus.UNKNOWN) to "wappass.baidu.com.example.org",
            Triple(308, "https://tiebac.baidu.com/new-endpoint", TaskStatus.UNKNOWN) to "tiebac.baidu.com",
            Triple(304, null, TaskStatus.UNKNOWN) to "missing"
        )
        for ((fixture, host) in cases) {
            val (code, location, status) = fixture
            var calls = 0
            val engine = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertTrue(chain.request().body!!.isOneShot())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("fixture").body("secret-response".toResponseBody())
                    .apply { if (location != null) header("Location", location) }.build()
            }.build()
            val net = NetClient(setOf("tiebac.baidu.com"), engine)
            val e = assertThrows(HttpStatusException::class.java) {
                net.postForm("https://tiebac.baidu.com/c/c/forum/sign?token=fixture-secret",
                    emptyMap(), mapOf("Cookie" to "BDUSS=fixture-cookie"))
            }
            assertEquals(1, calls)
            assertEquals(status, e.status)
            assertEquals(status == TaskStatus.UNKNOWN && code == 302, e.isTransient)
            assertEquals("HTTP $code; tieba endpoint=/c/c/forum/sign; location_host=$host", e.diagnostic)
            assertEquals(e.diagnostic, Rules.safeDiagnostic(e.diagnostic))
        }
    }

    @Test fun otherPlatformsKeepUnknownStatusForRedirectsToBaidu() {
        val engine = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                .message("fixture").body("fixture".toResponseBody())
                .header("Location", "https://wappass.baidu.com/verify").build()
        }.build()
        val error = assertThrows(HttpStatusException::class.java) {
            NetClient(setOf("music.163.com"), engine).get("https://music.163.com/")
        }
        assertEquals(TaskStatus.UNKNOWN, error.status)
        assertEquals("HTTP 302; location_host=wappass.baidu.com", error.diagnostic)
        assertEquals("HTTP 302", Rules.safeDiagnostic(error.diagnostic + "/secret-path?token=fixture-secret"))
    }

    @Test fun rawAndDownloadRejectUnsafeAddressesBeforeTransport() {
        val net = client(200)
        for (url in listOf("http://music.163.com/", "https://music.163.com:444/", "https://music.163.com.evil.test/",
            "https://music.163.com./", "https://name:secret@music.163.com/", "https://example.invalid/")) {
            assertThrows(IllegalArgumentException::class.java) { net.raw("GET", url) }
            assertThrows(IllegalArgumentException::class.java) { net.download(url) }
        }
    }

    @Test fun knownAndStreamingOversizedBodiesAreRejected() {
        assertThrows(IOException::class.java) { client(200, ByteArray(4_000_001).toResponseBody()).raw("GET", "https://music.163.com/") }
        val streamed = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = Buffer().write(ByteArray(4_000_001))
        }
        assertThrows(IOException::class.java) { client(200, streamed).raw("GET", "https://music.163.com/") }
    }

    @Test fun imageBytesAreNotDecodedAsTextAndRedirectsAreNotFollowed() {
        val bytes = byteArrayOf(0, -1, -128, 1)
        assertArrayEquals(bytes, client(200, bytes.toResponseBody()).download("https://music.163.com/"))
        assertThrows(IOException::class.java) { client(302).download("https://music.163.com/") }
    }

    @Test fun rawUsesResponseCharsetAndDiagnosticStringsHideSensitiveFields() {
        val body = "已确认".toByteArray(Charsets.UTF_16LE).toResponseBody("text/plain; charset=utf-16le".toMediaType())
        assertEquals("已确认", client(200, body).raw("GET", "https://music.163.com/").body)
        val response = RawResponse(200, "secret-body", listOf("BDUSS=secret-cookie"))
        assertFalse(response.toString().contains("secret"))
    }
}
