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
    @Test fun signSelfCheckDiagnosticAcceptsOnlyExactFixedState() {
        listOf("tieba forumGuide is_sign=1","tieba forumGuide is_sign=0").forEach {
            assertEquals(it,Rules.safeDiagnostic(it))
        }
        listOf("tieba forumGuide is_sign=1 fixture-sensitive-value","tieba forumGuide is_sign=2",
            "fixture-sensitive-value tieba forumGuide is_sign=0","tieba forumGuide owner_uid=123 is_sign=1").forEach {
            assertEquals("",Rules.safeDiagnostic(it))
        }
    }
    @Test fun statsDiagnosticPreservesOnlyTheWhitelistedFieldListAlongsideExistingDiagnostics() {
        val stats = "stats 未识别：字段 data.userLevel,growthPoint_2,${"a".repeat(40)}"
        assertEquals(stats, Rules.safeDiagnostic(stats))
        assertEquals("code=200; $stats", Rules.safeDiagnostic("private-value; code=200; $stats"))
        assertEquals("tieba forumGuide is_sign=1; $stats",
            Rules.safeDiagnostic("tieba forumGuide is_sign=1; $stats"))
        val twentyFields = "stats 未识别：字段 " + (1..20).joinToString(",") { "f$it" }
        assertEquals(twentyFields, Rules.safeDiagnostic(twentyFields))
    }

    @Test fun malformedStatsFieldListsAreRejectedInFull() {
        for (fields in listOf("a,字段", "a,b=private-value", "a,\"b\"", "a,'b'", "a, b", "a,b ",
            "a,b\n", "a,ｂ", "a,,b", "a,b,", "a,b-c", "a,${"b".repeat(41)}", List(21) { "a" }.joinToString(","))) {
            val stats = "stats 未识别：字段 $fields"
            assertEquals(fields, "", Rules.safeDiagnostic(stats))
            assertEquals(fields, "code=200", Rules.safeDiagnostic("code=200; $stats"))
        }
    }

    @Test fun combinedDiagnosticsFitTheLimitWithoutTruncatingAFieldName() {
        val fields = (1..20).map { "field${it}_${"x".repeat(32)}" }
        val diagnostic = "code=200; status=123; stats 未识别：字段 " + fields.joinToString(",")
        val safe = Rules.safeDiagnostic(diagnostic)
        assertTrue(safe.length <= 240)
        val prefix = "code=200; status=123; stats 未识别：字段 "
        assertTrue(safe.startsWith(prefix))
        val kept = safe.removePrefix(prefix).split(",")
        assertTrue(kept.size in 1..19)
        assertEquals(fields.take(kept.size), kept)
    }
}
