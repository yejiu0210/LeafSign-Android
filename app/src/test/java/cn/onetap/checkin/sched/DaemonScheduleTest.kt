package cn.onetap.checkin.sched

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class DaemonScheduleTest {
    private fun time(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun deadlineTriggersOnceAndSchedulesTheNextDay() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        assertFalse(schedule.takeDue(time("2026-11-13T08:59:59+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-13T09:00:00+08:00")))
        assertFalse(schedule.takeDue(time("2026-11-13T09:01:00+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-14T09:00:00+08:00")))
    }

    @Test fun wakeAfterSeveralDaysCatchesUpOnceWithoutHistoricalBurst() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        assertTrue(schedule.takeDue(time("2026-11-17T12:00:00+08:00")))
        assertFalse(schedule.takeDue(time("2026-11-17T12:01:00+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-18T09:00:00+08:00")))
    }

    @Test fun reapplyingSameSettingsDoesNotEraseAnOverdueWake() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        schedule.configure(true, 9, 0, time("2026-11-13T09:10:00+08:00"))
        assertTrue(schedule.takeDue(time("2026-11-13T09:10:01+08:00")))
    }

    @Test fun disablingCancelsTheDeadlineAndNewTimeReplacesIt() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        schedule.configure(false, 0, 0, time("2026-11-13T08:30:00+08:00"))
        assertFalse(schedule.takeDue(time("2026-11-13T09:10:00+08:00")))
        schedule.configure(true, 10, 0, time("2026-11-13T09:30:00+08:00"))
        assertFalse(schedule.takeDue(time("2026-11-13T09:59:59+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-13T10:00:00+08:00")))
    }

    @Test fun backwardsClockChangeRebasesTheDeadline() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        assertFalse(schedule.takeDue(time("2026-11-12T08:00:00+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-12T09:00:00+08:00")))
    }

    @Test fun enablingExactlyAtDeadlineStartsTomorrow() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-12-31T09:00:00+08:00"))
        assertFalse(schedule.takeDue(time("2026-12-31T09:00:00+08:00")))
        assertTrue(schedule.takeDue(time("2027-01-01T09:00:00+08:00")))
    }

    @Test fun completionAcrossTheNextDeadlineDoesNotStartASecondRun() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        assertTrue(schedule.takeDue(time("2026-11-14T08:59:50+08:00")))
        schedule.afterAttempt(time("2026-11-14T09:00:05+08:00"), schedule.revision)
        assertFalse(schedule.takeDue(time("2026-11-14T09:01:00+08:00")))
        assertTrue(schedule.takeDue(time("2026-11-15T09:00:00+08:00")))
    }

    @Test fun completingOldAttemptCannotEraseNewlyConfiguredDeadline() {
        val schedule = DaemonSchedule()
        schedule.configure(true, 9, 0, time("2026-11-13T08:00:00+08:00"))
        assertTrue(schedule.takeDue(time("2026-11-13T09:00:00+08:00")))
        val revision = schedule.revision
        schedule.configure(true, 9, 1, time("2026-11-13T09:00:10+08:00"))
        schedule.afterAttempt(time("2026-11-13T09:01:05+08:00"), revision)
        assertTrue(schedule.takeDue(time("2026-11-13T09:01:06+08:00")))
    }
}
