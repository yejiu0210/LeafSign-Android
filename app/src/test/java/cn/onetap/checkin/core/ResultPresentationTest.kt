package cn.onetap.checkin.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultPresentationTest {
    private fun record(platform: Platform, targetId: String) = Record(
        accountId="fixture-account", platform=platform, accountLabel="fixture-label", targetId=targetId,
        task="fixture-task", period="fixture-period", status=TaskStatus.ALREADY, message="fixture-message",
        experienceState="经验待官方确认")

    @Test fun hidesLegacyTiebaLikeExperienceWithoutChangingStoredHistory() {
        val old=record(Platform.TIEBA,"tieba:like:123")
        assertEquals("",ResultPresentation.experienceState(old))
        assertEquals("经验待官方确认",old.experienceState)
    }

    @Test fun keepsClearExperienceAndHidesPendingNote() {
        val bilibili=record(Platform.BILIBILI,"level-acceleration").copy(experienceState="尚未确认到账")
        assertEquals("尚未确认到账",ResultPresentation.experienceState(bilibili))
        assertEquals("",ResultPresentation.experienceState(record(Platform.TIEBA,"tieba:forum:123")))
        assertEquals("经验待官方确认",record(Platform.TIEBA,"tieba:forum:123").experienceState)
    }

    @Test fun hidesHedgingExperienceNotes() {
        val bilibili=record(Platform.BILIBILI,"level-acceleration")
        // 「已领取」和右侧状态重复，不再单独显示
        assertEquals("",ResultPresentation.experienceState(bilibili.copy(experienceState="官方确认经验已领取；未额外核对经验流水")))
        assertEquals("",ResultPresentation.experienceState(bilibili.copy(experienceState="平台显示已领取")))
        assertEquals("",ResultPresentation.experienceState(bilibili.copy(experienceState="未额外核对经验流水")))
    }

    @Test fun accountDetailsAreBuiltFromCurrentDataNotStoredLongText() {
        val forum={id:String,bound:Boolean->Target("tieba:forum:$id","forum","${id}吧",bound=bound)}
        val account=Account(platform=Platform.TIEBA,userId="fixture-user",displayName="fixture-name",note="",
            credential="fixture-credential",targets=listOf(forum("1",true),forum("2",true),forum("3",false),
                Target("tieba:like:1","daily-like","随机一赞")),
            enabled=emptySet(),details="已校验百度 UID；已分页获取 3 个关注贴吧。不额外查询经验到账。")
        assertEquals("关注 2 个吧",ResultPresentation.accountDetails(account))
        assertEquals("尚未关注贴吧",ResultPresentation.accountDetails(account.copy(targets=emptyList())))
        assertEquals("",ResultPresentation.accountDetails(account.copy(platform=Platform.NETEASE)))
        val legacy=account.copy(platform=Platform.NETEASE,details="已停用旧普通签到。请点「校验 / 刷新」载入黑胶乐签，再勾选启用。")
        assertEquals(legacy.details,ResultPresentation.accountDetails(legacy))
        assertEquals("已校验百度 UID；已分页获取 3 个关注贴吧。不额外查询经验到账。",account.details)
    }
}
