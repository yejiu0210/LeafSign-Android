package cn.onetap.checkin.sched

import cn.onetap.checkin.core.AppState
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.Record
import cn.onetap.checkin.core.Settings
import cn.onetap.checkin.core.StateStore
import cn.onetap.checkin.core.TaskStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ScheduleRulesTest {
    private fun time(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun beforeScheduledTimeUsesTodayInShanghai() {
        assertEquals(time("2026-11-13T09:00:00+08:00"), nextTrigger(time("2026-11-13T08:59:59+08:00"),9,0))
    }
    @Test fun afterScheduledTimeUsesTomorrow() {
        assertEquals(time("2026-11-14T09:00:00+08:00"), nextTrigger(time("2026-11-13T09:00:00.001+08:00"),9,0))
    }
    @Test fun exactlyScheduledTimeAlsoUsesTomorrow() {
        assertEquals(time("2026-11-14T09:00:00+08:00"), nextTrigger(time("2026-11-13T09:00:00+08:00"),9,0))
    }
    @Test fun nextTriggerCrossesMonthBoundary() {
        assertEquals(time("2026-03-01T07:15:00+08:00"), nextTrigger(time("2026-02-28T23:59:59+08:00"),7,15))
    }
    @Test fun nextTriggerCrossesYearBoundary() {
        assertEquals(time("2027-01-01T00:00:00+08:00"), nextTrigger(time("2026-12-31T23:59:59+08:00"),0,0))
    }
    @Test fun timezoneIsShanghaiRegardlessOfInputOffset() {
        assertEquals(time("2026-11-13T09:00:00+08:00"), nextTrigger(time("2026-11-13T00:30:00Z"),9,0))
        assertEquals("2026-11-14", todayDate(time("2026-11-13T16:00:00Z")))
    }
    @Test fun everyAutomaticSourceRequiresAutoAndAnUnclaimedDate() {
        val today="2026-11-13"
        for (source in listOf("alarm","catchup","backup","shizuku")) {
            assertFalse(shouldAutoRun(Settings(auto=false),today,source))
            assertFalse(shouldAutoRun(Settings(auto=true,lastAutoRunDate=today),today,source))
            assertTrue(shouldAutoRun(Settings(auto=true,lastAutoRunDate="2026-11-12"),today,source))
            assertTrue(shouldAutoRun(Settings(auto=true),today,source))
        }
    }
    @Test fun testTriggerBypassesBothAutomaticRestrictions() {
        assertTrue(shouldAutoRun(Settings(auto=false,lastAutoRunDate="2026-11-13"),"2026-11-13","test"))
    }
    @Test fun catchupRequiresDueTimeAndUnclaimedDate() {
        val settings=Settings(auto=true,hour=9,minute=0)
        assertFalse(shouldCatchUp(settings,time("2026-11-13T08:59:59+08:00")))
        assertTrue(shouldCatchUp(settings,time("2026-11-13T09:00:00+08:00")))
        assertTrue(shouldCatchUp(settings,time("2026-11-13T23:59:59+08:00")))
        assertFalse(shouldCatchUp(settings.copy(auto=false),time("2026-11-13T12:00:00+08:00")))
        assertFalse(shouldCatchUp(settings.copy(lastAutoRunDate="2026-11-13"),time("2026-11-13T12:00:00+08:00")))
    }
    private class MemoryStore(private var state: AppState): StateStore {
        @Synchronized override fun read() = state
        @Synchronized override fun update(change: (AppState)->AppState): AppState { state=change(state); return state }
    }
    @Test fun competingSourcesCanOnlyClaimTheDateOnce() {
        val store=MemoryStore(AppState(settings=Settings(auto=true)))
        val pool=Executors.newFixedThreadPool(4)
        try {
            val claims=pool.invokeAll(listOf("alarm","catchup","backup","shizuku").map { source ->
                Callable { claimAutoRun(store,"2026-11-13",source) }
            }).map { it.get() }
            assertEquals(1,claims.count { it })
            assertEquals("2026-11-13",store.read().settings.lastAutoRunDate)
        } finally { pool.shutdownNow() }
    }
    @Test fun testTriggerNeverUpdatesAutomaticDate() {
        val state=AppState(settings=Settings(auto=false,lastAutoRunDate="2026-11-12"))
        val store=MemoryStore(state)
        assertTrue(claimAutoRun(store,"2026-11-13","test"))
        assertEquals(state,store.read())
        assertFalse(claimAutoRun(store,"2026-11-13","alarm"))
        assertEquals(state,store.read())
    }

    @Test fun onlyFailedUnknownAndInterruptedRecordsScheduleRetries() {
        val statuses = setOf(TaskStatus.FAILED, TaskStatus.UNKNOWN, TaskStatus.INTERRUPTED)
        for (status in TaskStatus.entries) {
            val record = Record(accountId = "account", platform = Platform.TIEBA, accountLabel = "账号",
                targetId = "target", task = "签到", period = "2026-11-13", status = status, message = "")
            for (source in listOf("alarm", "backup", "shizuku", "catchup", "retry")) {
                assertEquals("$source/$status", if (status in statuses) 1 else 0, newRetryableItemCount(source, listOf(record)))
            }
            assertEquals(0, newRetryableItemCount("test", listOf(record)))
        }
        assertEquals(0, newRetryableItemCount("alarm", emptyList()))
    }

    @Test fun retryBudgetAllowsThreeAttemptsAndResetsAtBeijingMidnight() {
        val beforeMidnight = time("2026-11-13T15:59:59.999Z")
        val midnight = time("2026-11-13T16:00:00Z")
        var budget = RetryBudget("", 0)
        for (attempt in 1..3) {
            budget = checkNotNull(budget.next(beforeMidnight))
            assertEquals(RetryBudget("2026-11-13", attempt), budget)
        }
        val restored = RetryBudget(budget.date, budget.attempts)
        assertNull(restored.next(beforeMidnight))
        assertEquals(RetryBudget("2026-11-14", 1), restored.next(midnight))
    }

    @Test fun retryBypassesDailyClaimButRequiresBothSwitchesAndPreservesClaimDate() {
        val today = "2026-11-13"
        for (previousClaim in listOf(today, "2026-11-12")) {
            for (auto in listOf(false, true)) {
                for (autoRetry in listOf(false, true)) {
                    val settings = Settings(auto = auto, lastAutoRunDate = previousClaim, autoRetry = autoRetry)
                    val initial = AppState(settings = settings)
                    val store = MemoryStore(initial)
                    assertEquals(auto && autoRetry, shouldAutoRun(settings, today, "retry"))
                    assertEquals(auto && autoRetry, claimAutoRun(store, today, "retry"))
                    assertEquals(initial, store.read())
                }
            }
        }
    }
}
