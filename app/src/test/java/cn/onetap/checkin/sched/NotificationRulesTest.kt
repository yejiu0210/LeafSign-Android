package cn.onetap.checkin.sched

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class NotificationRulesTest {
    @Test fun everySkipIsSilentRegardlessOfSwitchAndFailureBudget() {
        val summaries = listOf("跳过：今天已执行", "跳过：正在执行", "跳过：未开启自动签到",
            "跳过：90 秒内没有可用网络，稍后由备份任务补跑", "跳过：暂无网络，下次整点再试")
        for (summary in summaries) for (enabled in listOf(false, true)) for (notified in listOf(false, true)) {
            assertEquals(summary, AutoNotification.NONE, notificationDecision(summary, enabled, notified))
        }
    }

    @Test fun skipTakesPriorityEvenIfTheRestOfTheSummaryMentionsFailure() {
        assertEquals(AutoNotification.NONE, notificationDecision("跳过：失败任务正在执行", true, false))
    }

    @Test fun successfulRunFollowsNotificationSwitch() {
        for (summary in listOf("完成 10 项，待处理 0 项", "完成 0 项，待处理 0 项", "完成 10 项，失败 0 项，待确认 0 项")) {
            for (notified in listOf(false, true)) {
                assertEquals(AutoNotification.RESULT, notificationDecision(summary, true, notified))
                assertEquals(AutoNotification.NONE, notificationDecision(summary, false, notified))
            }
        }
    }

    @Test fun explicitFailureBypassesDisabledSwitchButOnlyOnce() {
        for (summary in listOf("失败：执行超时", "失败：前台服务超时", "失败：SocketTimeoutException", "失败：本地加密数据无法读取")) {
            for (enabled in listOf(false, true)) {
                assertEquals(AutoNotification.FAILURE_ONCE, notificationDecision(summary, enabled, false))
                assertEquals(AutoNotification.NONE, notificationDecision(summary, enabled, true))
            }
        }
    }

    @Test fun IncompleteRecordsAlsoConsumeTheDailyFailureReminder() {
        for (summary in listOf("完成 10 项，待处理 2 项", "完成 3 项，失败1项", "完成 3 项，待确认 2 项", "签到结果待确认")) {
            for (enabled in listOf(false, true)) {
                assertEquals(AutoNotification.FAILURE_ONCE, notificationDecision(summary, enabled, false))
                assertEquals(AutoNotification.NONE, notificationDecision(summary, enabled, true))
            }
        }
    }

    @Test fun completedCountNeverLooksLikeAnIncompleteCount() {
        assertEquals(AutoNotification.NONE, notificationDecision("完成 100 项，待处理 0 项", false, false))
        assertEquals(AutoNotification.RESULT, notificationDecision("完成 100 项，待处理 0 项", true, false))
    }

    @Test fun mixedZeroAndPositiveCountsStillRecognizeTheFailure() {
        assertEquals(AutoNotification.FAILURE_ONCE, notificationDecision("完成 4 项，失败 0 项，待确认 1 项", false, false))
        assertEquals(AutoNotification.NONE, notificationDecision("完成 4 项，失败 0 项，待确认 1 项", false, true))
    }

    @Test fun numericFormattingDoesNotOverflowOrTreatZeroAsFailure() {
        assertEquals(AutoNotification.RESULT, notificationDecision("完成 2 项，失败：000项，待确认: 0项", true, false))
        assertEquals(AutoNotification.FAILURE_ONCE, notificationDecision("完成 2 项，待处理 999999999999999999999 项", false, false))
    }

    @Test fun failureReminderResetsAtShanghaiMidnight() {
        val alreadyNotified = "2026-11-13"
        val before = OffsetDateTime.parse("2026-11-13T15:59:59Z").toInstant().toEpochMilli()
        val after = OffsetDateTime.parse("2026-11-13T16:00:00Z").toInstant().toEpochMilli()
        assertEquals(AutoNotification.NONE, notificationDecision("失败：执行超时", false, todayDate(before) == alreadyNotified))
        assertEquals(AutoNotification.FAILURE_ONCE, notificationDecision("失败：执行超时", false, todayDate(after) == alreadyNotified))
    }
}
