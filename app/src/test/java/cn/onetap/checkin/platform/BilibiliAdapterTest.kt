package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic fixtures only. These tests never contact Bilibili or stand for account verification. */
class BilibiliAdapterTest {
    @Test fun unknownOrMissingStatusDoesNotBecomeAlready() {
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseExperienceState(JSONObject()).status)
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseExperienceState(JSONObject("{\"state\":99}")).status)
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseExperienceState(JSONObject("{\"state\":\"1\"}")).status)
    }

    @Test fun watchingIsRequiredAndNotAutomated() {
        val result = BilibiliAdapter.parseExperienceState(JSONObject("{\"state\":2}"))
        assertEquals(TaskStatus.INELIGIBLE, result.status)
        assertTrue(result.message.contains("手动观看"))
    }

    @Test fun codeZeroAloneNeverMeansExperienceGranted() {
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":0}")).status)
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":0,\"data\":{\"type\":0,\"is_grant\":\"true\"}}")).status)
        assertEquals(TaskStatus.FAILED, BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":0,\"data\":{\"type\":0,\"is_grant\":false}}")).status)
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":999,\"data\":{\"type\":0,\"is_grant\":true}}")).status)
    }

    @Test fun deferredExperienceIsDistinctFromReceived() {
        val grant = BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":0,\"data\":{\"type\":0,\"is_grant\":true}}"))
        val delayed = BilibiliAdapter.parseClaimResponse(JSONObject("{\"code\":0,\"data\":{\"type\":1,\"is_grant\":true}}"))
        assertEquals(TaskStatus.SUCCESS, grant.status)
        assertEquals(TaskStatus.SUCCESS, delayed.status)
        assertTrue(grant.experienceState.contains("官方确认"))
        assertTrue(delayed.experienceState.contains("尚未确认到账"))
    }

    @Test fun fractionalOrCoercedStatusNeverBecomesClaimable() {
        for (literal in listOf("0.5", "1.5", "0.0", "\"0\"", "true", "9223372036854775808")) {
            val result = BilibiliAdapter.parseExperienceState(JSONObject("{\"state\":$literal}"))
            assertEquals(literal, TaskStatus.UNKNOWN, result.status)
        }
    }

    @Test fun fractionalBusinessCodeAndGrantTypeNeverBecomeSuccess() {
        for (literal in listOf("0.5", "0.0", "\"0\"", "false", "2147483648")) {
            assertEquals(literal, TaskStatus.UNKNOWN, BilibiliAdapter.parseClaimResponse(
                JSONObject("{\"code\":$literal,\"data\":{\"type\":0,\"is_grant\":true}}")
            ).status)
            assertEquals(literal, TaskStatus.UNKNOWN, BilibiliAdapter.parseClaimResponse(
                JSONObject("{\"code\":0,\"data\":{\"type\":$literal,\"is_grant\":true}}")
            ).status)
        }
    }

    @Test fun navUidRequiresPositiveIntegerLongWithoutCoercion() {
        for (literal in listOf("1.5", "1.0", "\"1\"", "true", "0", "-1", "9223372036854775808")) {
            assertNull(literal, BilibiliAdapter.positiveUid(JSONObject("{\"mid\":$literal}").opt("mid")))
        }
        assertEquals("9223372036854775807", BilibiliAdapter.positiveUid(
            JSONObject("{\"mid\":9223372036854775807}").opt("mid")))
    }

    @Test fun centerUidAndVipQualificationCannotBeTruncatedOrAssumed() {
        for (literal in listOf("1.5", "1.0", "\"1\"", "true", "9223372036854775808")) {
            assertEquals(literal, TaskStatus.UNKNOWN, BilibiliAdapter.membershipProblem(
                JSONObject("{\"mid\":$literal,\"vip_status\":1}"), "1")?.status)
        }
        for (literal in listOf("0.5", "1.5", "1.0", "\"1\"", "true", "99", "2147483648")) {
            assertEquals(literal, TaskStatus.UNKNOWN, BilibiliAdapter.membershipProblem(
                JSONObject("{\"mid\":1,\"vip_status\":$literal}"), "1")?.status)
        }
        assertEquals(TaskStatus.UNKNOWN, BilibiliAdapter.membershipProblem(JSONObject("{\"mid\":1}"), "1")?.status)
        assertEquals(TaskStatus.INELIGIBLE, BilibiliAdapter.membershipProblem(JSONObject("{\"mid\":1,\"vip_status\":0}"), "1")?.status)
        assertNull(BilibiliAdapter.membershipProblem(JSONObject("{\"mid\":1,\"vip_status\":1}"), "1"))
        assertEquals(TaskStatus.MANUAL, BilibiliAdapter.membershipProblem(JSONObject("{\"mid\":2,\"vip_status\":1}"), "1")?.status)
    }

    @Test fun untrustedCookieFieldsAreNotForwarded() {
        val parsed = BilibiliAdapter.parseCookies("SESSDATA=synthetic; bili_jct=synthetic; foreign_secret=do-not-forward")
        assertEquals(setOf("SESSDATA", "bili_jct"), parsed.keys)
        assertFalse(parsed.containsKey("foreign_secret"))
    }

    @Test fun cookieErrorsNeverEchoInputSecrets() {
        val secret = "synthetic-sensitive-marker"
        try {
            BilibiliAdapter.parseCookies("SESSDATA=$secret\r\nInjected: $secret")
            throw AssertionError("Control characters must be rejected")
        } catch (error: AdapterException) {
            assertFalse(error.message.contains(secret))
            assertFalse(error.diagnostic.contains(secret))
        }
    }
}
