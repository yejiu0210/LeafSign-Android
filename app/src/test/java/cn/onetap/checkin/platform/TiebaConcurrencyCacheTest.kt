package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TiebaConcurrencyCacheTest {
    private fun target(uid: String, forumId: String = "101") = Target("tieba:forum:$forumId", "forum", "测试${forumId}吧",
        metadata=mapOf("ownerUserId" to uid, "forumId" to forumId, "forumName" to "测试$forumId"))
    private class MemoryStore(var state: AppState = AppState()) : StateStore {
        @Synchronized override fun read() = state
        @Synchronized override fun update(change: (AppState) -> AppState): AppState { state=change(state); return state }
    }
    private class Api(private val clock: () -> Long) : TiebaTransport {
        val calls = mutableMapOf<String, Int>()
        val writes = mutableListOf<Pair<String, Long>>()
        val signed = mutableSetOf<Pair<String, String>>()
        var timeoutWrite = false
        var webOwner: String? = null
        private fun counted(path: String) { calls[path]=(calls[path] ?: 0)+1 }
        private fun cookieUid(headers: Map<String,String>) = headers.getValue("Cookie").substringAfter("BDUSS=").substringBefore(';')
        override fun get(url: String, headers: Map<String,String>): JSONObject {
            counted("identity")
            return JSONObject().put("no",0).put("data",JSONObject().put("id",webOwner ?: cookieUid(headers)))
        }
        override fun postForm(url: String, fields: Map<String,String>, headers: Map<String,String>): JSONObject = when {
            url.endsWith("/login") -> {
                counted("login")
                JSONObject("""{"error_code":0,"user":{"id":"${fields.getValue("bdusstoken")}","name":"测试账号"},"anti":{"tbs":"0123456789abcdef0123456789"}}""")
            }
            url.endsWith("/like") -> {
                counted("followed")
                JSONObject("""{"error_code":0,"forum_list":{"non-gconforum":[{"id":"101","name":"测试"}]},"has_more":0}""")
            }
            url.endsWith("/forumGuide") -> {
                counted("guide")
                JSONObject().put("error_code",0).put("like_forum",org.json.JSONArray().apply {
                    for (id in listOf("101","102","103")) put(JSONObject().put("forum_id",id)
                        .put("is_sign",if((cookieUid(headers) to id) in signed)1 else 0))
                })
            }
            url.endsWith("/sign") -> {
                val uid = fields.getValue("BDUSS")
                writes += uid to clock(); signed += uid to fields.getValue("kw").removePrefix("测试")
                if (timeoutWrite) throw IOException("synthetic timeout after server write")
                JSONObject("""{"error_code":0,"user_info":{"sign_bonus_point":8}}""")
            }
            else -> error("Unexpected fixture endpoint")
        }
    }

    @Test fun writesFromDifferentAccountsDoNotWaitAndSameAccountRespectsInterval() {
        assertEquals(5000L,TiebaAdapter.WRITE_INTERVAL_MS)
        var now=0L
        val waits=mutableListOf<Long>()
        val api=Api { now }
        val adapter=TiebaAdapter(null,api,pause={ waits+=it; now+=it },clock={ now })
        assertEquals(TaskStatus.SUCCESS,adapter.execute("42",target("42")).status)
        assertEquals(TaskStatus.SUCCESS,adapter.execute("99",target("99")).status)
        assertTrue(waits.isEmpty())
        // Another unsigned forum state for the same account; validate discards the cached guide.
        api.signed.remove("42" to "101"); adapter.validate("42")
        assertEquals(TaskStatus.SUCCESS,adapter.execute("42",target("42")).status)
        assertEquals(listOf(0L,0L,TiebaAdapter.WRITE_INTERVAL_MS),api.writes.map { it.second })
        assertEquals(listOf(TiebaAdapter.WRITE_INTERVAL_MS),waits)
    }

    @Test fun executionPrioritySeparatesForumSigningFromDailyLikes() {
        val adapter=TiebaAdapter(null,Api { 0L },pause={})
        assertEquals(0,adapter.executionOrder(target("42")))
        assertEquals(1,adapter.executionOrder(TiebaLikeTask.target("42","101","测试")))
    }

    @Test fun sleepingAccountDoesNotHoldOtherAccountsPacingLock() = runBlocking {
        val now=AtomicLong(0)
        val sleeping=CountDownLatch(1); val release=CountDownLatch(1)
        val adapter=TiebaAdapter(null,Api(now::get),pause={ ms ->
            sleeping.countDown(); check(release.await(3,TimeUnit.SECONDS)); now.addAndGet(ms)
        },clock=now::get)
        adapter.paceWrites("42")
        val first=async(Dispatchers.IO) { adapter.paceWrites("42") }
        try {
            assertTrue(sleeping.await(3,TimeUnit.SECONDS))
            withTimeout(1000) { withContext(Dispatchers.IO) { adapter.paceWrites("99") } }
        } finally { release.countDown() }
        first.await()
        assertTrue(now.get() >= TiebaAdapter.WRITE_INTERVAL_MS)
    }

    @Test fun readCachesArePerCredentialAndExpireAtTheirOwnTtl() {
        var now=0L
        val api=Api { now }
        val adapter=TiebaAdapter(null,api,pause={ now+=it },clock={ now })
        repeat(2) { assertEquals(TaskStatus.PENDING,adapter.query("42",target("42"))?.status) }
        assertEquals(mapOf("login" to 1,"identity" to 1,"guide" to 1),api.calls)
        api.signed += "99" to "101"
        assertEquals(TaskStatus.ALREADY,adapter.query("99",target("99"))?.status)
        assertEquals(mapOf("login" to 2,"identity" to 2,"guide" to 2),api.calls)
        now=20_000
        adapter.query("42",target("42"))
        assertEquals(3,api.calls["guide"]); assertEquals(2,api.calls["identity"])
        now=300_000
        adapter.query("42",target("42"))
        assertEquals(3,api.calls["login"]); assertEquals(3,api.calls["identity"])
        assertEquals(4,api.calls["guide"])
    }

    @Test fun changedStokenMustRecheckWebIdentityEvenWithSameBduss() {
        val api=Api { 0L }
        val adapter=TiebaAdapter(null,api,pause={},clock={ 0L })
        adapter.query("BDUSS=42; STOKEN=first",target("42"))
        api.webOwner="99"
        val error=assertThrows(AdapterException::class.java) {
            adapter.query("BDUSS=42; STOKEN=second",target("42"))
        }
        assertEquals(TaskStatus.EXPIRED,error.status)
        assertEquals(2,api.calls["identity"]); assertEquals(1,api.calls["guide"])
        assertTrue(api.writes.isEmpty())
    }

    @Test fun signingMultipleForumsKeepsWebIdentityButRefreshesGuideAfterEveryWrite() {
        var now=0L
        val api=Api { now }
        val adapter=TiebaAdapter(null,api,pause={ now+=it },clock={ now })
        for ((index,id) in listOf("101","102","103").withIndex()) {
            assertEquals(TaskStatus.SUCCESS,adapter.execute("42",target("42",id)).status)
            assertEquals(index+2,api.calls["guide"]) // Initial guide, then one fresh guide per write.
            assertEquals(1,api.calls["identity"])
        }
        adapter.validate("42")
        adapter.query("42",target("42"))
        assertEquals(2,api.calls["identity"])
        assertEquals(5,api.calls["guide"])
    }

    @Test fun timedOutWriteInvalidatesUnsignedCacheBeforeReconciliation() {
        val api=Api { 0L }.apply { timeoutWrite=true }
        val adapter=TiebaAdapter(null,api,pause={},clock={ 0L })
        assertEquals(TaskStatus.PENDING,adapter.query("42",target("42"))?.status)
        assertEquals(TaskStatus.ALREADY, adapter.execute("42",target("42")).status)
        assertEquals(TaskStatus.ALREADY,adapter.query("42",target("42"))?.status)
        assertEquals(1,api.writes.size); assertEquals(2,api.calls["guide"])
        adapter.query("42",target("42"))
        assertEquals(2,api.calls["guide"])
    }

    @Test fun validateAlwaysRefreshesAndLikeTasksReuseFollowedForumsForFiveMinutes() {
        var now=0L
        val api=Api { now }; val store=MemoryStore()
        val adapter=TiebaAdapter(store,api,pause={},clock={ now })
        val identity=adapter.validate("42")
        val like=identity.targets.single { it.type=="daily-like" }
        val account=Account(id="fixture",platform=Platform.TIEBA,userId="42",displayName="测试",note="",credential="42",
            targets=identity.targets,enabled=setOf(like.id))
        store.state=AppState(accounts=listOf(account),checkpoints=listOf(TaskCheckpoint(account.id,like.id,Rules.period(like),mapOf(
            "threadId" to "20","forumId" to "101","forumName" to "测试","stage" to "complete",
            "likeAttempts" to "1","unlikeAttempts" to "0","cuid" to "06C7F37D41256F25FABA97B885DB6EFB|VAPUDW7TA"))))
        repeat(2) { assertEquals(TaskStatus.ALREADY,adapter.execute("42",like).status) }
        assertEquals(1,api.calls["login"]); assertEquals(1,api.calls["followed"])
        now=300_000
        adapter.execute("42",like)
        assertEquals(2,api.calls["login"]); assertEquals(2,api.calls["followed"])
        repeat(2) { adapter.validate("42") }
        assertEquals(4,api.calls["login"]); assertEquals(4,api.calls["followed"])
        assertTrue(api.writes.isEmpty())
    }
}
