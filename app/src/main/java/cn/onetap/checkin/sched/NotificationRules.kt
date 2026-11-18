package cn.onetap.checkin.sched

internal enum class AutoNotification { NONE, RESULT, FAILURE_ONCE }

/** AutoRun uses “待处理 N 项” for incomplete records, including failed/unconfirmed outcomes. */
internal fun notificationDecision(summary: String, notifications: Boolean, failureAlreadyNotified: Boolean): AutoNotification {
    if (summary.startsWith("跳过：")) return AutoNotification.NONE
    val incomplete = Regex("(?:失败|待确认|待处理)\\s*[：:]?\\s*([0-9]+)\\s*项")
        .findAll(summary).any { match -> match.groupValues[1].any { it != '0' } }
    val failure = summary.startsWith("失败：") || incomplete || summary.contains("结果待确认")
    if (failure) return if (failureAlreadyNotified) AutoNotification.NONE else AutoNotification.FAILURE_ONCE
    return if (notifications) AutoNotification.RESULT else AutoNotification.NONE
}
