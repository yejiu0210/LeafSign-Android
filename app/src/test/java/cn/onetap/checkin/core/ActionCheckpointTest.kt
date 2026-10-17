package cn.onetap.checkin.core

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ActionCheckpointTest {
    @Test fun previousVersionsWithoutCheckpointsDecodeAsEmpty() {
        assertTrue(CheckpointsJson.decode(null).isEmpty())
        assertTrue(CheckpointsJson.decode(JSONArray()).isEmpty())
    }
    @Test fun sameTaskAndPeriodKeepDistinctAccountContinuation() {
        val checkpoints=listOf(
            TaskCheckpoint("first","tieba:like:100","2026-10-17",mapOf("tid" to "100","stage" to "like_sent")),
            TaskCheckpoint("second","tieba:like:100","2026-10-17",mapOf("tid" to "200","stage" to "complete")))
        assertEquals(checkpoints,CheckpointsJson.decode(CheckpointsJson.encode(checkpoints)))
    }
    @Test fun deletingOneAccountRemovesOnlyItsCheckpointAndHistory() {
        val a=Account(platform=Platform.TIEBA,userId="fixture-first",displayName="fixture",note="",credential="fixture-only",targets=emptyList(),enabled=emptySet())
        val b=a.copy(id="fixture-second-account",userId="fixture-second")
        val c=TaskCheckpoint(a.id,"tieba:like:100","2026-10-17",mapOf("tid" to "100"))
        val r=Record(accountId=a.id,platform=Platform.TIEBA,accountLabel="fixture",targetId=c.targetId,task="fixture",period=c.period,status=TaskStatus.UNKNOWN,message="fixture")
        val state=AppState(listOf(a,b),listOf(r,r.copy(id="second-record",accountId=b.id)),checkpoints=listOf(c,c.copy(accountId=b.id)))
        val after=Rules.removeAccount(state,a.id)
        assertEquals(listOf(b),after.accounts)
        assertEquals(listOf(b.id),after.records.map{it.accountId})
        assertEquals(listOf(b.id),after.checkpoints.map{it.accountId})
    }
    @Test fun optionalWriteTaskIsNotEnabledByDefault() {
        val targets=listOf(Target("forum","forum","fixture"),Target("tieba:like:100","daily-like","fixture",metadata=mapOf("defaultEnabled" to "false")))
        val added=Rules.addOrUpdate(AppState(),Identity("fixture","fixture",targets),Platform.TIEBA,"fixture-only","",null)
        assertEquals(setOf("forum"),added.accounts.single().enabled)
    }
    @Test fun unknownHeyboxReasonIsKeptWithoutServerText() {
        assertEquals("heybox missing task_list",Rules.safeDiagnostic("heybox missing task_list"))
        assertEquals("",Rules.safeDiagnostic("heybox synthetic-secret raw response"))
        assertEquals("",Rules.safeDiagnostic("heybox missing task_list synthetic-secret"))
    }
    @Test fun onlyFixedPublicTiebaIdentityDiagnosticsSurviveRedaction() {
        listOf("tieba field=thread_id expected=123 actual=124", "tieba field=forum_id expected=456 actual=missing",
            "tieba field=redundant_fid expected=456 actual=zero", "tieba field=forum_id expected=456 actual=invalid").forEach {
            assertEquals(it,Rules.safeDiagnostic(it))
        }
        listOf("tieba field=owner_uid expected=123 actual=124", "tieba field=forum_id expected=456 actual=private-value",
            "tieba field=forum_id expected=456 actual=457 synthetic-secret", "synthetic-secret tieba field=forum_id expected=456 actual=457").forEach {
            assertEquals("",Rules.safeDiagnostic(it))
        }
    }
    @Test fun tiebaDetailDiagnosticKeepsOnlyWhitelistedPublicContext() {
        val diagnostic="tieba endpoint=pb_page reason=thread_unavailable thread_id=123 error_code=4"
        assertEquals(diagnostic,Rules.safeDiagnostic(diagnostic))
        listOf("tieba endpoint=pb_page reason=private-value thread_id=123 error_code=4",
            diagnostic+" fixture-sensitive-value",diagnostic.replace("thread_id=123","owner_uid=123")).forEach {
            assertEquals("error_code=4",Rules.safeDiagnostic(it))
        }
    }
}
