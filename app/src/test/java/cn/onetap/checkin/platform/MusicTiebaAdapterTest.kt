package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Isolated synthetic fixtures. No platform traffic, credentials, or real-account verification. */
class MusicTiebaAdapterTest {
    @Test fun neteaseVipWriteCodeAloneNeverProvesMusicSign() {
        assertEquals(TaskStatus.UNKNOWN, NeteaseAdapter.parseSignResult(JSONObject("""{"code":200}""")).status)
        assertEquals(TaskStatus.UNKNOWN, NeteaseAdapter.parseSignResult(JSONObject("""{"code":-2,"msg":"重复签到"}""")).status)
        assertEquals(TaskStatus.EXPIRED, NeteaseAdapter.parseSignResult(JSONObject("""{"code":301}""")).status)
    }

    @Test fun neteaseMissingAndUnknownResponsesNeverBecomeSuccessOrAlready() {
        for (raw in listOf("{}", """{"code":0}""", """{"code":999,"msg":"ok"}""", """{"code":null}""")) {
            assertEquals(raw, TaskStatus.UNKNOWN, NeteaseAdapter.parseSignResult(JSONObject(raw)).status)
        }
    }

    @Test fun neteaseManualVerificationStopsTheTask() {
        assertEquals(TaskStatus.MANUAL,
            NeteaseAdapter.parseSignResult(JSONObject("""{"code":999,"msg":"请完成安全验证"}""")).status)
    }

    @Test fun tiebaRequiresPositiveBusinessRewardToAcceptCodeZero() {
        assertEquals(TaskStatus.SUCCESS,
            TiebaAdapter.parseSignResult(JSONObject("""{"error_code":"0","user_info":{"sign_bonus_point":"8"}}""")).status)
        for (raw in listOf(
            """{"error_code":0}""",
            """{"error_code":0,"user_info":{}}""",
            """{"error_code":0,"user_info":{"sign_bonus_point":0}}""",
            """{"error_code":0,"user_info":{"sign_bonus_point":-1}}""",
            """{"error_code":0,"user_info":{"sign_bonus_point":"not-a-number"}}"""
        )) assertEquals(raw, TaskStatus.UNKNOWN, TiebaAdapter.parseSignResult(JSONObject(raw)).status)
    }

    @Test fun tiebaOnlyKnownDuplicateCodeBecomesAlready() {
        assertEquals(TaskStatus.ALREADY, TiebaAdapter.parseSignResult(JSONObject("""{"error_code":"160002"}""")).status)
        for (raw in listOf("{}", """{"error_code":999}""", """{"error_code":null}""", """{"error_code":"invalid"}""")) {
            assertEquals(raw, TaskStatus.UNKNOWN, TiebaAdapter.parseSignResult(JSONObject(raw)).status)
        }
    }

    @Test fun unknownCodeWithPositiveRewardStillDoesNotSucceed() {
        assertEquals(TaskStatus.UNKNOWN,
            TiebaAdapter.parseSignResult(JSONObject("""{"error_code":999,"user_info":{"sign_bonus_point":8}}""")).status)
    }

    @Test fun tiebaAuthenticationAndManualErrorsAreDistinct() {
        assertEquals(TaskStatus.EXPIRED, TiebaAdapter.parseSignResult(JSONObject("""{"error_code":999,"error_msg":"请先登录"}""")).status)
        assertEquals(TaskStatus.MANUAL, TiebaAdapter.parseSignResult(JSONObject("""{"error_code":999,"error_msg":"请输入验证码"}""")).status)
        assertEquals(TaskStatus.FAILED, TiebaAdapter.parseSignResult(JSONObject("""{"error_code":999,"error_msg":"账号被封禁"}""")).status)
    }

    @Test fun tiebaSignatureMatchesIndependentUtf8VectorAndIsOrderIndependent() {
        // Expected digest computed separately with .NET MD5 over the upstream raw concatenation.
        val fields = linkedMapOf("kw" to "测试", "tbs" to "abc123", "BDUSS" to "testCredential", "_client_version" to "22.12.1.0")
        val signed = TiebaAdapter.signedFields(fields)
        assertEquals("c0983d4bf19c9a372141baa4eada05ad", signed["sign"])
        assertEquals(signed["sign"], TiebaAdapter.signedFields(fields.entries.reversed().associate { it.toPair() })["sign"])
        assertFalse(fields.containsKey("sign"))
        assertEquals(fields.size + 1, signed.size)
    }

    @Test fun weapiProducesAesBlocksAndFixedWidthRsaCiphertext() {
        val first = NeteaseAdapter.encryptWeapi("""{"csrf_token":"synthetic"}""")
        val second = NeteaseAdapter.encryptWeapi("""{"csrf_token":"synthetic"}""")
        assertEquals(setOf("params", "encSecKey"), first.keys)
        val encrypted = Base64.getDecoder().decode(first.getValue("params"))
        assertTrue(encrypted.isNotEmpty())
        assertEquals(0, encrypted.size % 16)
        assertTrue(Regex("[0-9a-f]{256}").matches(first.getValue("encSecKey")))
        assertNotEquals(first, second)
    }

    @Test fun credentialsOnlyForwardTheirOwnWhitelist() {
        val music = NeteaseAdapter().credentials("MUSIC_U=synthetic-session; __csrf=synthetic-csrf; BDUSS=foreign; foreign_secret=never-forward")
        val tieba = TiebaAdapter().credentials("BDUSS=synthetic-session; STOKEN=synthetic-stoken; MUSIC_U=foreign; foreign_secret=never-forward")
        assertEquals(setOf("MUSIC_U", "__csrf"), music.keys)
        assertEquals(setOf("BDUSS", "STOKEN"), tieba.keys)
        assertEquals("synthetic-session", NeteaseAdapter().credentials("synthetic-session")["MUSIC_U"])
        assertEquals("synthetic-session", TiebaAdapter().credentials("synthetic-session")["BDUSS"])
    }

    @Test fun credentialErrorsAndUnknownDiagnosticsNeverEchoSecrets() {
        val secret = "synthetic-sensitive-marker"
        val parsers = listOf<(String) -> Map<String, String>>(NeteaseAdapter()::credentials, TiebaAdapter()::credentials)
        for (parser in parsers) {
            try {
                parser("$secret\r\nInjected: $secret")
                fail("Control characters must be rejected")
            } catch (error: AdapterException) {
                assertFalse(error.message.contains(secret))
                assertFalse(error.diagnostic.contains(secret))
            }
        }
        val response = JSONObject().put("error_code", 998).put("error_msg", secret).put("BDUSS", secret)
        val result = TiebaAdapter.parseSignResult(response)
        assertFalse(result.message.contains(secret))
        assertFalse(result.diagnostic.contains(secret))
        val music = NeteaseAdapter.parseSignResult(JSONObject().put("code", 998).put("msg", secret))
        assertFalse(music.message.contains(secret))
        assertFalse(music.diagnostic.contains(secret))
    }
}
