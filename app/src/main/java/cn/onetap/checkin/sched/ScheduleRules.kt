package cn.onetap.checkin.sched

import cn.onetap.checkin.core.Record
import cn.onetap.checkin.core.Rules
import cn.onetap.checkin.core.Settings
import cn.onetap.checkin.core.StateStore
import java.time.Instant

internal const val RETRY_SOURCE = "retry"
internal const val MAX_AUTO_RETRIES = 3
internal const val RETRY_DELAY_MILLIS = 5 * 60_000L
private val retryTriggerSources = setOf("alarm", "backup", "shizuku", "catchup", RETRY_SOURCE)

internal data class RetryBudget(val date: String, val attempts: Int) {
    fun next(nowMillis: Long): RetryBudget? {
        val today = todayDate(nowMillis)
        val used = if (date == today) attempts else 0
        return if (used < MAX_AUTO_RETRIES) RetryBudget(today, used + 1) else null
    }
}

internal fun canAutoRetry(settings: Settings) = settings.auto && settings.autoRetry

internal fun newRetryableItemCount(source: String, records: List<Record>): Int =
    if (source in retryTriggerSources) records.count { Rules.retryable(it.status) } else 0

internal fun shouldAutoRun(settings: Settings, todayDate: String, source: String): Boolean = when (source) {
    "test" -> true
    RETRY_SOURCE -> canAutoRetry(settings)
    else -> settings.auto && settings.lastAutoRunDate != todayDate
}

internal fun autoRunSkipSummary(settings: Settings, source: String) = when {
    !settings.auto -> "跳过：未开启自动签到"
    source == RETRY_SOURCE && !settings.autoRetry -> "跳过：未开启失败重试"
    else -> "跳过：今天已执行"
}

internal fun shouldCatchUp(settings: Settings, nowMillis: Long): Boolean {
    val now = Instant.ofEpochMilli(nowMillis).atZone(scheduleZone)
    val planned = now.withHour(settings.hour).withMinute(settings.minute).withSecond(0).withNano(0)
    return shouldAutoRun(settings, now.toLocalDate().toString(), "catchup") && !now.isBefore(planned)
}

/** Claim inside Store's transaction; never acquire Runner.mutex here. */
internal fun claimAutoRun(store: StateStore, todayDate: String, source: String): Boolean {
    if (source == "test") return true
    if (source == RETRY_SOURCE) return shouldAutoRun(store.read().settings, todayDate, source)
    var claimed = false
    store.update { state ->
        if (shouldAutoRun(state.settings, todayDate, source)) {
            claimed = true
            state.copy(settings = state.settings.copy(lastAutoRunDate = todayDate))
        } else state
    }
    return claimed
}
