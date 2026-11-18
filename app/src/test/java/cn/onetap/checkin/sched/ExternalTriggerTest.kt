package cn.onetap.checkin.sched

import cn.onetap.checkin.core.Settings
import org.junit.Assert.*
import org.junit.Test

class ExternalTriggerTest {
    private val token = "0123456789abcdef".repeat(4)

    @Test fun privateTokenPreservesEachKnownInternalSource() {
        for (source in listOf("alarm", "test", "catchup", "backup", "shizuku", "retry")) {
            assertEquals(source, trustedTriggerSource(source, token, token))
        }
    }

    @Test fun missingWrongAndEmptyTokensCannotAuthorizeTestOrAnyOtherSource() {
        for (source in listOf("alarm", "test", "catchup", "backup", "shizuku", "retry", "unknown", null)) {
            for (supplied in listOf(null, "", "wrong", token.dropLast(1), "f" + token.drop(1))) {
                assertEquals("shizuku", trustedTriggerSource(source, supplied, token))
            }
        }
        assertEquals("shizuku", trustedTriggerSource("test", "", ""))
    }

    @Test fun unknownSourceWithValidTokenStillUsesAutomaticRules() {
        for (source in listOf(null, "", "TEST", "test ", "arbitrary")) {
            assertEquals("shizuku", trustedTriggerSource(source, token, token))
        }
    }

    @Test fun externalTestCannotBypassAutoSwitchOrDailyDeduplication() {
        val source = trustedTriggerSource("test", null, token)
        val today = "2026-11-13"
        assertFalse(shouldAutoRun(Settings(auto = false), today, source))
        assertFalse(shouldAutoRun(Settings(auto = true, lastAutoRunDate = today), today, source))
        assertTrue(shouldAutoRun(Settings(auto = true), today, source))
    }

    @Test fun receiverForwardingOnlySignsTheAlreadyValidatedSource() {
        val received = trustedTriggerSource("test", "attacker", token)
        assertEquals("shizuku", trustedTriggerSource(received, token, token))
    }
}
