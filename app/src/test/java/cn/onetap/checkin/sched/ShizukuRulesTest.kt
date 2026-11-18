package cn.onetap.checkin.sched

import cn.onetap.checkin.core.Settings
import org.junit.Assert.*
import org.junit.Test

class ShizukuRulesTest {
    @Test fun daemonRequiresBothOptInsAndReadyShizuku() {
        for (auto in listOf(false, true)) for (daemon in listOf(false, true)) for (state in ShizukuState.entries) {
            assertEquals("auto=$auto daemon=$daemon state=$state",
                auto && daemon && state == ShizukuState.READY,
                shouldRunDaemon(Settings(auto = auto, shizukuDaemon = daemon), state))
        }
    }

    @Test fun defaultsDoNotEnableTheDaemon() {
        assertFalse(shouldRunDaemon(Settings(auto = true), ShizukuState.READY))
    }

    @Test fun daemonKeepsTomorrowScheduledEvenIfTodayWasAlreadyClaimed() {
        assertTrue(shouldRunDaemon(Settings(auto = true, shizukuDaemon = true, lastAutoRunDate = "2026-11-13"), ShizukuState.READY))
    }

    @Test fun unsupportedCommandIdsCannotCreateAnyProcess() {
        for (id in listOf(Int.MIN_VALUE, -1, 7, 10008, Int.MAX_VALUE)) {
            assertNull(keepAliveCommand(id, "cn.onetap.checkin"))
        }
    }
}
