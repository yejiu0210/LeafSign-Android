package cn.onetap.checkin.core

/** Keeps obsolete Tieba experience hints out of the UI without rewriting historical records. */
object ResultPresentation {
    fun experienceState(record: Record): String =
        if (record.platform == Platform.TIEBA && record.targetId.startsWith("tieba:like:")) ""
        else record.experienceState

    fun accountDetails(account: Account): String =
        if (account.platform == Platform.TIEBA) account.details.removeSuffix("经验待官方确认。").trimEnd()
        else account.details
}
