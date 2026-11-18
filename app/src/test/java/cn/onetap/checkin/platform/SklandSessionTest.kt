package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

class SklandSessionTest {
    private data class Call(val method: String, val path: String, val headers: Map<String,String>, val body: String)
    private class Api : SklandTransport {
        val authorizations = AtomicInteger()
        val grants = AtomicInteger()
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val owners = ConcurrentHashMap<String,String>()
        @Volatile var identity = "owner"
        var onCall: (Call) -> JSONObject? = { null }
        val attendanceWrites get() = calls.filter { it.method=="POST" && it.path.endsWith("/attendance") }
        override fun get(url: String, headers: Map<String,String>) = respond("GET",url,headers,"")
        override fun postJson(url: String, body: JSONObject, headers: Map<String,String>) = respond("POST",url,headers,body.toString())
        override fun postRawJson(url: String, body: String, headers: Map<String,String>) = respond("POST",url,headers,body)
        private fun respond(method: String, url: String, headers: Map<String,String>, body: String): JSONObject {
            val call = Call(method,url.toHttpUrl().encodedPath,headers,body)
            calls += call
            onCall(call)?.let { return it }
            return when {
                call.path.endsWith("/grant") -> { grants.incrementAndGet(); JSONObject("""{"status":0,"data":{"code":"grant-fixture"}}""") }
                call.path.endsWith("/generate_cred_by_code") -> {
                    val count=authorizations.incrementAndGet()
                    owners["cred-$count"]=identity
                    JSONObject("""{"code":0,"data":{"cred":"cred-$count","token":"sign-$count"}}""")
                }
                call.path.endsWith("/auth/refresh") -> {
                    val count=authorizations.incrementAndGet()
                    owners[headers.getValue("cred")]=identity
                    JSONObject("""{"code":0,"data":{"token":"sign-$count"}}""")
                }
                call.path.endsWith("/teenager") -> JSONObject().put("code",0).put("data",JSONObject().put("teenager",
                    JSONObject().put("userId",owners.getValue(headers.getValue("cred")))))
                call.path.endsWith("/binding") -> JSONObject().put("code",0).put("data",JSONObject().put("list",apps()))
                method=="GET" && call.path.contains("/endfield/") -> JSONObject("""{"code":0,"data":{"hasToday":false}}""")
                method=="GET" && call.path.endsWith("/attendance") -> JSONObject("""{"code":0,"data":{"calendar":[{"type":"daily","available":true,"done":false}]}}""")
                method=="POST" && call.path.contains("/endfield/") -> JSONObject("""{"code":0,"data":{"ts":"1700000000","awardIds":[{"id":"reward"}],"resourceInfoMap":{"reward":{"name":"奖励","count":1}}}}""")
                method=="POST" && call.path.endsWith("/attendance") -> JSONObject("""{"code":0,"data":{"awards":[{"resource":{"name":"奖励"},"count":1}]}}""")
                else -> error("Unexpected fixture request")
            }
        }
    }
    companion object {
        private fun apps() = JSONArray("""[
            {"appCode":"arknights","bindingList":[{"uid":"ark-id","channelMasterId":"1","nickName":"方舟角色"}]},
            {"appCode":"endfield","bindingList":[{"uid":"ef-id","roles":[{"roleId":"role-id","serverId":"1","nickname":"终末地角色"}]}]}
        ]""")
        private fun targets() = SklandAdapter.parseTargets("owner",apps())
        private fun ark() = targets().first { it.type==SklandAdapter.ARK }
        private fun http401(): Nothing = throw AdapterException(TaskStatus.EXPIRED,"登录凭证已失效","HTTP 401")
    }

    @Test fun queryExecuteAndTwoRolesShareOneAuthorization() {
        val api=Api();val adapter=SklandAdapter(api)
        assertEquals(1,adapter.maxTargetConcurrency)
        for(target in targets()) {
            assertEquals(TaskStatus.PENDING,adapter.query("token-fixture",target)?.status)
            assertEquals(TaskStatus.SUCCESS,adapter.execute("token-fixture",target).status)
        }
        assertEquals(1,api.authorizations.get());assertEquals(1,api.grants.get())
        assertEquals(2,api.attendanceWrites.size)
        assertEquals(setOf("cred-1"),api.calls.mapNotNull { it.headers["cred"] }.toSet())
    }

    @Test fun cacheExpiresAtTwoMinutesAndValidateAlwaysRefreshes() {
        var clock=0L
        val api=Api();val adapter=SklandAdapter(api) { clock }
        adapter.query("token-fixture",ark())
        clock=119_999;adapter.query("token-fixture",ark());assertEquals(1,api.authorizations.get())
        clock=120_000;adapter.query("token-fixture",ark());assertEquals(2,api.authorizations.get())
        adapter.validate("token-fixture");assertEquals(3,api.authorizations.get())
        adapter.execute("token-fixture",ark());assertEquals(3,api.authorizations.get())
        adapter.validate("token-fixture");assertEquals(4,api.authorizations.get())
    }

    @Test fun read401RefreshesOnceAndResignsOnlyRejectedRequest() {
        val api=Api();val adapter=SklandAdapter(api)
        adapter.query("token-fixture",ark())
        val reads=AtomicInteger()
        api.onCall={call->if(call.method=="GET" && call.path.endsWith("/attendance") && reads.incrementAndGet()==1)http401();null}
        assertEquals(TaskStatus.PENDING,adapter.query("token-fixture",ark())?.status)
        assertEquals(2,api.authorizations.get());assertEquals(2,reads.get())
        val retried=api.calls.takeLast(1).single()
        assertEquals("cred-2",retried.headers["cred"])
        assertEquals(0,api.attendanceWrites.size)
    }

    @Test fun write401OrExplicitTokenCodeRetriesOnlyThatWriteOnce() {
        for(http in listOf(true,false)) {
            val api=Api();val adapter=SklandAdapter(api)
            adapter.query("token-fixture",ark())
            val writes=AtomicInteger()
            api.onCall={call->
                if(call.method=="POST" && call.path.endsWith("/attendance") && writes.incrementAndGet()==1) {
                    if(http)http401() else JSONObject("""{"code":10002,"message":"token无效"}""")
                } else null
            }
            assertEquals(TaskStatus.SUCCESS,adapter.execute("token-fixture",ark()).status)
            assertEquals(2,api.authorizations.get());assertEquals(2,writes.get())
            val sent=api.attendanceWrites
            assertEquals(listOf("cred-1","cred-2"),sent.map { it.headers["cred"] })
            assertEquals(sent.first().body,sent.last().body)
            assertNotEquals(sent.first().headers["sign"],sent.last().headers["sign"])
        }
    }

    @Test fun repeated401OrTokenRejectionStopsAfterOneRefreshAndDiscardsCache() {
        for(http in listOf(true,false)) {
            val api=Api();val adapter=SklandAdapter(api)
            api.onCall={call->if(call.method=="POST" && call.path.endsWith("/attendance")) {
                if(http)http401() else JSONObject("""{"code":10002,"message":"token无效"}""")
            } else null}
            assertEquals(TaskStatus.EXPIRED,assertThrows(AdapterException::class.java) { adapter.execute("token-fixture",ark()) }.status)
            assertEquals(2,api.authorizations.get());assertEquals(2,api.attendanceWrites.size)
            api.onCall={null}
            adapter.query("token-fixture",ark())
            assertEquals(3,api.authorizations.get())
        }
    }

    @Test fun timeoutAndOtherHttpErrorsNeverRetryTheWrite() {
        for(error in listOf(IOException("timeout"),AdapterException(TaskStatus.MANUAL,"限制","HTTP 403"),
            AdapterException(TaskStatus.MANUAL,"限制","HTTP 429"),AdapterException(TaskStatus.UNKNOWN,"错误","HTTP 500"),
            AdapterException(TaskStatus.EXPIRED,"登录失效","unverified"))) {
            val api=Api();val adapter=SklandAdapter(api)
            api.onCall={call->if(call.method=="POST" && call.path.endsWith("/attendance"))throw error;null}
            assertThrows(error.javaClass) { adapter.execute("token-fixture",ark()) }
            assertEquals(1,api.authorizations.get());assertEquals(1,api.attendanceWrites.size)
        }
    }

    @Test fun messageOnlyTokenErrorAndManualBusinessCodeNeverRetry() {
        for(body in listOf("""{"code":9999,"message":"token is invalid"}""", """{"code":10003,"message":"验证码"}""",
            """{"code":10001,"message":"未知错误"}""")) {
            val api=Api();val adapter=SklandAdapter(api)
            api.onCall={call->if(call.method=="POST" && call.path.endsWith("/attendance"))JSONObject(body)else null}
            val result=adapter.execute("token-fixture",ark())
            assertTrue(result.status in setOf(TaskStatus.EXPIRED,TaskStatus.MANUAL,TaskStatus.UNKNOWN))
            assertEquals(1,api.authorizations.get());assertEquals(1,api.attendanceWrites.size)
        }
    }

    @Test fun changedIdentityAfterRefreshNeverResendsWrite() {
        val api=Api();val adapter=SklandAdapter(api)
        api.onCall={call->if(call.method=="POST" && call.path.endsWith("/attendance")) {api.identity="different-owner";http401()};null}
        val error=assertThrows(AdapterException::class.java) { adapter.execute("token-fixture",ark()) }
        assertEquals(TaskStatus.EXPIRED,error.status)
        assertTrue(error.message.contains("身份已改变"))
        assertEquals(2,api.authorizations.get());assertEquals(1,api.attendanceWrites.size)
    }

    @Test fun concurrentCallsForSameCredentialAuthorizeOnlyOnce() {
        val api=Api();val adapter=SklandAdapter(api)
        api.onCall={call->if(call.path.endsWith("/grant"))Thread.sleep(30);null}
        val pool=Executors.newFixedThreadPool(4)
        try {
            val results=pool.invokeAll(List(4) { Callable { adapter.query("token-fixture",ark()) } }).map { it.get() }
            assertTrue(results.all { it?.status==TaskStatus.PENDING })
            assertEquals(1,api.authorizations.get())
        } finally {pool.shutdownNow()}
    }

    @Test fun differentCredentialLocksDoNotSerializeAccounts() {
        val api=Api();val adapter=SklandAdapter(api)
        val entered=CountDownLatch(2)
        api.onCall={call->if(call.path.endsWith("/binding")) {entered.countDown();assertTrue(entered.await(3,TimeUnit.SECONDS))};null}
        val pool=Executors.newFixedThreadPool(2)
        try {
            val results=pool.invokeAll(listOf("token-a","token-b").map { credential->Callable {adapter.query(credential,ark())} }).map {it.get()}
            assertTrue(results.all {it?.status==TaskStatus.PENDING})
            assertEquals(2,api.authorizations.get())
        } finally {pool.shutdownNow()}
    }

    @Test fun importedCredAlsoSharesRefreshSessionAndCacheKeysAreOnlyDigests() {
        val api=Api();val adapter=SklandAdapter(api)
        val input="""{"cred":"imported-fixture","dId":"fixture-device"}"""
        adapter.query(input,ark());adapter.execute(input,ark())
        assertEquals(1,api.authorizations.get());assertEquals(0,api.grants.get())
        val field=SklandAdapter::class.java.getDeclaredField("sessions").apply { isAccessible=true }
        val keys=(field.get(adapter) as Map<*,*>).keys
        assertTrue(keys.all { it is String && Regex("[0-9a-f]{64}").matches(it) })
        assertFalse(keys.toString().contains("imported-fixture"))
    }

    @Test fun runnerValidatesThenExecutesBothRolesInOrderUsingSameSession()=runBlocking {
        val api=Api();val adapter=SklandAdapter(api)
        val tasks=targets()
        val account=Account(platform=Platform.SKLAND,userId="owner",displayName="fixture",note="",credential="token-fixture",
            targets=tasks,enabled=tasks.map {it.id}.toSet())
        val store=object:StateStore {
            var state=AppState(accounts=listOf(account))
            @Synchronized override fun read()=state
            @Synchronized override fun update(change:(AppState)->AppState):AppState {state=change(state);return state}
        }
        assertEquals(2,Runner(store,mapOf(Platform.SKLAND to adapter)).run())
        assertEquals(1,api.authorizations.get())
        assertEquals(listOf("/api/v1/game/attendance","/web/v1/game/endfield/attendance"),api.attendanceWrites.map {it.path})
        assertTrue(store.read().records.all {it.status==TaskStatus.SUCCESS})
    }
}
