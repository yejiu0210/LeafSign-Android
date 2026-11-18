package cn.onetap.checkin.sched

/** Wall-clock deadlines, inspected after each poll/wake instead of sleeping for a monotonic day. */
internal class DaemonSchedule {
    var enabled = false
        private set
    var revision = 0L
        private set
    private var hour = 0
    private var minute = 0
    private var nextAt = 0L
    private var lastNow = 0L

    fun configure(enabled: Boolean, hour: Int, minute: Int, now: Long) {
        if (enabled) require(hour in 0..23 && minute in 0..59)
        // Reapplying settings after process/UI refresh must not erase an overdue deadline.
        if (this.enabled == enabled && (!enabled || (this.hour == hour && this.minute == minute))) return
        revision++
        this.enabled = enabled
        this.hour = hour
        this.minute = minute
        lastNow = now
        nextAt = if (enabled) nextTrigger(now, hour, minute) else 0
    }

    fun takeDue(now: Long): Boolean {
        if (!enabled) return false
        if (now < lastNow) nextAt = nextTrigger(now, hour, minute)
        lastNow = now
        if (now < nextAt) return false
        // A wake after several missed days produces one attempt, not a burst of historical runs.
        nextAt = nextTrigger(now, hour, minute)
        return true
    }

    fun afterAttempt(now: Long, attemptedRevision: Long) {
        // A command can straddle the next HH:mm; plan again after it finishes. Do not overwrite a new setting.
        if (enabled && revision == attemptedRevision) {
            nextAt = nextTrigger(now, hour, minute)
            lastNow = now
        }
    }
}
