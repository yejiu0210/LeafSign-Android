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

    @Test fun preservesBilibiliExperienceAndUnrelatedTaskState() {
        val bilibili=record(Platform.BILIBILI,"level-acceleration").copy(experienceState="尚未确认到账")
        assertEquals("尚未确认到账",ResultPresentation.experienceState(bilibili))
        assertEquals("经验待官方确认",ResultPresentation.experienceState(record(Platform.TIEBA,"tieba:forum:123")))
        assertEquals("经验待官方确认",ResultPresentation.experienceState(record(Platform.BILIBILI,"tieba:like:123")))
    }

    @Test fun suppressesOnlyTheObsoleteTiebaAccountDetailSuffix() {
        val account=Account(platform=Platform.TIEBA,userId="fixture-user",displayName="fixture-name",note="",
            credential="fixture-credential",targets=emptyList(),enabled=emptySet(),details="已校验百度 UID。经验待官方确认。")
        assertEquals("已校验百度 UID。",ResultPresentation.accountDetails(account))
        assertEquals(account.details,ResultPresentation.accountDetails(account.copy(platform=Platform.BILIBILI)))
        val unrelated=account.copy(details="新的说明")
        assertEquals(unrelated.details,ResultPresentation.accountDetails(unrelated))
        assertEquals("已校验百度 UID。经验待官方确认。",account.details)
    }
}
