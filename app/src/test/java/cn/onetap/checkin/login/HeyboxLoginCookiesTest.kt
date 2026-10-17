package cn.onetap.checkin.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyboxLoginCookiesTest {
    @Test fun canonicalizesOneCompletePageSessionWithoutUnrelatedCookies() {
        assertEquals("pkey=fixture-session; user_pkey=fixture-session; heybox_id=42; user_heybox_id=42",
            HeyboxLoginCookies.credential("theme=dark; heybox_id=42; pkey=fixture-session; unrelated=ignored"))
    }

    @Test fun acceptsOnlyAliasNamesAndRetainsAnOptionalRealToken() {
        assertEquals("pkey=fixture-session; user_pkey=fixture-session; heybox_id=42; user_heybox_id=42; x_xhh_tokenid=fixture-token",
            HeyboxLoginCookies.credential("user_heybox_id=42; user_pkey=fixture-session; x_xhh_tokenid=fixture-token"))
    }

    @Test fun preservesWireEscapingAndEqualsWithoutDecodingOrDoubleEncoding() {
        val result = HeyboxLoginCookies.credential("heybox_id=42; pkey=fixture+/%3D%25=; x_xhh_tokenid=fixture%2B%25")
        assertEquals("pkey=fixture+/%3D%25=; user_pkey=fixture+/%3D%25=; heybox_id=42; user_heybox_id=42; x_xhh_tokenid=fixture%2B%25", result)
    }

    @Test fun identicalDuplicateCookiesAreUnambiguous() {
        assertTrue(HeyboxLoginCookies.credential("pkey=fixture; pkey=fixture; heybox_id=42; user_heybox_id=42") != null)
    }

    @Test fun conflictingDuplicateNamesOrAliasesAreRejected() {
        listOf("pkey=one; pkey=two; heybox_id=42", "pkey=one; user_pkey=two; heybox_id=42",
            "pkey=one; heybox_id=42; user_heybox_id=43", "pkey=one; heybox_id=42; heybox_id=43",
            "pkey=one; heybox_id=42; x_xhh_tokenid=one; x_xhh_tokenid=two").forEach {
            assertNull(HeyboxLoginCookies.credential(it))
        }
    }

    @Test fun missingPiecesStayIncompleteAndAreNeverMergedBetweenCalls() {
        listOf(null, "", "theme=dark", "heybox_id=42", "pkey=fixture", "heybox_id=42; pkey",
            "heybox_id=42; pkey=").forEach { assertNull(HeyboxLoginCookies.credential(it)) }
        assertNull(HeyboxLoginCookies.credential("heybox_id=42"))
        assertNull(HeyboxLoginCookies.credential("pkey=fixture"))
    }

    @Test fun rejectsLiteralGuestAndUninitializedSentinelsEvenWithValidOtherAliases() {
        listOf("0", "null", "undefined", "false", "guest", "NULL", "Undefined", "FALSE", "Guest").forEach {
            assertNull(HeyboxLoginCookies.credential("heybox_id=42; pkey=$it"))
            assertNull(HeyboxLoginCookies.credential("heybox_id=42; pkey=fixture; user_pkey=$it"))
            assertNull(HeyboxLoginCookies.credential("heybox_id=42; pkey=fixture; x_xhh_tokenid=$it"))
        }
    }

    @Test fun acceptsOnlyPositiveCanonicalDecimalAccountIds() {
        listOf("0", "-1", "+42", "042", "4.2", "4e2", "undefined", "null", "guest", "42%20").forEach {
            assertNull(HeyboxLoginCookies.credential("heybox_id=$it; pkey=fixture"))
        }
    }

    @Test fun rejectsHeaderInjectionAndNonAsciiOrWhitespaceValues() {
        listOf("fixture\r\nInjected=value", "fixture\tvalue", "fixture value", "fixture ", "fixture\u0000value", "fixture\u007f", "fixture\u4e2d").forEach {
            assertNull(HeyboxLoginCookies.credential("heybox_id=42; pkey=$it"))
        }
    }

    @Test fun snapshotSizeIsBoundedBeforeParsing() {
        assertNull(HeyboxLoginCookies.credential("heybox_id=42; pkey=" + "x".repeat(32_768)))
    }

    @Test fun cookieNamesAreCaseSensitiveAndUnknownFieldsAreNotForwarded() {
        assertNull(HeyboxLoginCookies.credential("HEYBOX_ID=42; PKEY=fixture"))
        val result = HeyboxLoginCookies.credential("heybox_id=42; pkey=fixture; unrelated; unknown=ignored")!!
        assertFalse(result.contains("unrelated"))
        assertFalse(result.contains("unknown"))
    }

    @Test fun scriptSnapshotsOnlyWhitelistedCookiePairs() {
        val script = HeyboxLoginCookies.SNAPSHOT_SCRIPT
        listOf("pkey", "user_pkey", "heybox_id", "user_heybox_id", "x_xhh_tokenid", "document.cookie").forEach { assertTrue(script.contains(it)) }
        listOf("localStorage", "sessionStorage", "querySelector", "innerHTML", "innerText", "password", "fetch(", "XMLHttpRequest").forEach { assertFalse(script.contains(it)) }
        assertTrue(script.contains(".filter("))
    }
}
