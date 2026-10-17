package cn.onetap.checkin.core

import org.junit.Assert.*
import org.junit.Test

class MusicMigrationTest {
    private val ordinary=Target("netease:daily:android","daily","旧普通签到",metadata=mapOf("ownerUserId" to "fixture-user"))
    private val member=Target("netease:vip:music-sign","vip-music-sign","黑胶乐签",metadata=mapOf("ownerUserId" to "fixture-user"))
    private fun account(platform:Platform=Platform.NETEASE)=Account(platform=platform,userId="fixture-user",displayName="fixture",note="fixture-note",credential="fixture-only-token",targets=listOf(ordinary),enabled=setOf(ordinary.id))

    @Test fun retiredTaskNeverEnablesMemberTaskOrTransfersHistory() {
        val a=account()
        val record=Record(accountId=a.id,platform=a.platform,accountLabel="fixture",targetId=ordinary.id,task=ordinary.label,period=Rules.period(ordinary),status=TaskStatus.SUCCESS,message="fixture")
        val before=AppState(accounts=listOf(a),records=listOf(record))
        val after=Rules.retireOrdinaryMusicTask(before)
        assertTrue(after.accounts.single().targets.isEmpty())
        assertTrue(after.accounts.single().enabled.isEmpty())
        assertEquals(a.credential,after.accounts.single().credential)
        assertEquals(a.id,after.accounts.single().id)
        assertEquals(before.records,after.records)
        val refreshed=Rules.addOrUpdate(after,Identity(a.userId,a.displayName,listOf(member)),Platform.NETEASE,a.credential,a.note,a.id)
        val newAccount=refreshed.accounts.single()
        assertTrue(newAccount.enabled.isEmpty())
        assertNull(Rules.latest(refreshed,newAccount,member))
    }
    @Test fun otherAccountsAndNewMemberSelectionsRemainUnchanged() {
        val a=account(Platform.TIEBA)
        val b=account().copy(targets=listOf(member),enabled=setOf(member.id))
        val state=AppState(accounts=listOf(a,b))
        assertEquals(state,Rules.retireOrdinaryMusicTask(state))
    }
    @Test fun migrationIsIdempotent() {
        val state=AppState(accounts=listOf(account()))
        val once=Rules.retireOrdinaryMusicTask(state)
        assertEquals(once,Rules.retireOrdinaryMusicTask(once))
    }
}
