package cn.onetap.checkin.core

/** 只在展示时精简文案，历史记录和账号数据原样保留。 */
object ResultPresentation {
    /** 隐藏「待官方确认 / 未额外核对」这类说明，只留明确的经验状态。 */
    fun experienceState(record: Record): String {
        if (record.platform == Platform.TIEBA && record.targetId.startsWith("tieba:like:")) return ""
        val text = record.experienceState.substringBefore('；').trim()
        return if (text.contains("待官方确认") || text.contains("未额外核对")) "" else text
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
