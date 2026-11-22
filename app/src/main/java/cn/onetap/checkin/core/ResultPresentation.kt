package cn.onetap.checkin.core

/** 只在展示时精简文案，历史记录和账号数据原样保留。 */
object ResultPresentation {
    /** 隐藏「待官方确认 / 未额外核对 / 平台显示已领取」这类重复说明，只留有新信息的经验状态。 */
    fun experienceState(record: Record): String {
        if (record.platform == Platform.TIEBA && record.targetId.startsWith("tieba:like:")) return ""
        val text = record.experienceState.substringBefore('；').trim()
        val redundant = listOf("待官方确认", "未额外核对", "已领取")
        return if (redundant.any { text.contains(it) }) "" else text
    }

    /** 旧版本存下的长说明，在界面上换成短句；新记录已经在源头改好了。 */
    fun message(record: Record): String = when {
        record.message.contains("本地周期内已确认完成") -> "今日已签到"
        record.message.contains("服务端确认今日已打卡") -> "今日已签到"
        else -> record.message
    }

    /** 账号卡片上的一句话说明：按当前数据生成，不直接显示校验时存下的长说明。 */
    fun accountDetails(account: Account): String {
        // 旧版迁移提示需要用户操作，原样保留。
        if (account.details.startsWith("已停用旧普通签到")) return account.details
        return when (account.platform) {
            Platform.TIEBA -> account.targets.count { it.type == "forum" && it.bound }
                .let { if (it > 0) "关注 $it 个吧" else "尚未关注贴吧" }
            Platform.BILIBILI -> account.targets.firstNotNullOfOrNull { it.metadata["eligibility"] }.orEmpty()
            Platform.SKLAND -> account.targets.count { it.bound }.let { if (it > 0) "$it 个角色" else "" }
            Platform.NETEASE, Platform.HEYBOX -> ""
        }
    }
}
