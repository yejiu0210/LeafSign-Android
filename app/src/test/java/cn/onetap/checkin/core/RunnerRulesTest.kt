package cn.onetap.checkin.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunnerRulesTest {
    private class MemoryStore(var value:AppState):StateStore {
        @Synchronized override fun read()=value
        @Synchronized override fun update(change:(AppState)->AppState):AppState {value=change(value);return value}
    }
    private class FakeAdapter:PlatformAdapter {
        override val platform=Platform.SKLAND
        private val writeCount=java.util.concurrent.atomic.AtomicInteger()
        val writes get()=writeCount.get()
        var validateAction:(String)->Identity={c->Identity(c,c,listOf(target("ak",c),target("ef",c)))}
        var queryAction:(String,Target)->TaskResult?={_,_->null}
        var freshCheckRequired=false
        var order:(Target)->Int={0}
        var action:(String,Target)->TaskResult={_,_->TaskResult(TaskStatus.SUCCESS,"test fixture")}
        override fun validate(credential:String)=validateAction(credential)
        override fun query(credential:String,target:Target)=queryAction(credential,target)
        override fun requiresFreshCompletionCheck(target:Target)=freshCheckRequired
        override fun executionOrder(target:Target)=order(target)
        override fun execute(credential:String,target:Target):TaskResult {writeCount.incrementAndGet();return action(credential,target)}
    }
    companion object {private fun target(id:String,owner:String)=Target(id,id,id,metadata=mapOf("ownerUserId" to owner))}
    private fun account(uid:String)=Account(platform=Platform.SKLAND,userId=uid,displayName=uid,note=uid,credential=uid,targets=listOf(target("ak",uid),target("ef",uid)),enabled=setOf("ak","ef"))
    @Test fun twoAccountsAndGamesKeepIndependentHistory()=runBlocking {
        val a=account("main");val b=account("clone");val store=MemoryStore(AppState(accounts=listOf(a,b)));val adapter=FakeAdapter()
        assertEquals(4,Runner(store,mapOf(Platform.SKLAND to adapter)).run())
        assertEquals(2,store.read().records.count {it.accountId==a.id});assertEquals(2,store.read().records.count {it.accountId==b.id})
        assertEquals(4,store.read().records.map {it.accountId+it.targetId}.distinct().size)
    }
    @Test fun identityMismatchNeverWrites()=runBlocking {
        val adapter=FakeAdapter().apply {validateAction={Identity("wrong","wrong",emptyList())}}
        val store=MemoryStore(AppState(accounts=listOf(account("main"))))
        Runner(store,mapOf(Platform.SKLAND to adapter)).run();assertEquals(0,adapter.writes)
        assertTrue(store.value.records.all {it.status==TaskStatus.INTERRUPTED && it.message.contains("账号身份校验") && it.message.contains("登录失效，请先更新凭证")})
    }
    @Test fun refreshedSessionIsSavedAndRereadAfterQueryAndForNextTarget()=runBlocking {
        val a=account("main");val store=MemoryStore(AppState(accounts=listOf(a)))
        val adapter=FakeAdapter().apply {
            validateAction={Identity("main","main",a.targets,updatedCredential="fixture-refreshed-session")}
            queryAction={c,_->
                assertEquals("fixture-refreshed-session",c)
                store.update{s->s.copy(accounts=s.accounts.map{it.copy(credential="fixture-query-refreshed-session")})}
                TaskResult(TaskStatus.PENDING,"fixture")
            }
            action={c,_->
                assertEquals("fixture-query-refreshed-session",c)
                store.update{s->s.copy(accounts=s.accounts.map{it.copy(credential="fixture-refreshed-session")})}
                TaskResult(TaskStatus.SUCCESS,"fixture")
            }
        }
        assertEquals(2,Runner(store,mapOf(Platform.SKLAND to adapter)).run())
        assertEquals(2,adapter.writes)
        val added=Rules.addOrUpdate(AppState(),Identity("fixture","fixture",emptyList(),updatedCredential="fixture-fresh"),Platform.SKLAND,"fixture-old","",null)
        assertEquals("fixture-fresh",added.accounts.single().credential)
    }
    @Test fun removedBindingOrUnfollowedForumNeverWrites()=runBlocking {
        val adapter=FakeAdapter().apply {validateAction={c->Identity(c,c,emptyList())}}
        val store=MemoryStore(AppState(accounts=listOf(account("main"))))
        Runner(store,mapOf(Platform.SKLAND to adapter)).run()
        assertEquals(0,adapter.writes);assertTrue(store.value.records.all{it.status==TaskStatus.UNBOUND})
    }
    @Test fun failedRoleDoesNotBlockOtherRoleOrAccount()=runBlocking {
        val adapter=FakeAdapter().apply {action={c,t->if(c=="main"&&t.id=="ak")throw IOException("fixture")else TaskResult(TaskStatus.SUCCESS,"fixture")}}
        val store=MemoryStore(AppState(accounts=listOf(account("main"),account("clone"))))
        Runner(store,mapOf(Platform.SKLAND to adapter)).run();assertEquals(4,adapter.writes)
        assertEquals(1,store.value.records.count {it.status==TaskStatus.UNKNOWN});assertEquals(3,store.value.records.count{Rules.completed(it.status)})
    }
    @Test fun timeoutWithoutStateQueryNeverRepeatsWrite()=runBlocking {
        val a=account("main").copy(enabled=setOf("ak"));val adapter=FakeAdapter().apply {action={_,_->throw IOException("timeout")}}
        val store=MemoryStore(AppState(accounts=listOf(a)));Runner(store,mapOf(Platform.SKLAND to adapter)).run()
        assertEquals(1,adapter.writes);assertEquals(TaskStatus.UNKNOWN,store.value.records.single().status)
    }
    @Test fun timeoutReconcilesConfirmedSuccess()=runBlocking {
        val adapter=FakeAdapter().apply {action={_,_->throw IOException("timeout")};queryAction={_,_->if(writes>0)TaskResult(TaskStatus.ALREADY,"confirmed fixture")else TaskResult(TaskStatus.PENDING,"fixture")}}
        val store=MemoryStore(AppState(accounts=listOf(account("main").copy(enabled=setOf("ak")))));Runner(store,mapOf(Platform.SKLAND to adapter)).run()
        assertEquals(1,adapter.writes);assertEquals(TaskStatus.ALREADY,store.value.records.single().status)
    }
    @Test fun expiryStopsRemainingWritesForThatAccount()=runBlocking {
        val adapter=FakeAdapter().apply {action={_,_->TaskResult(TaskStatus.EXPIRED,"fixture")}}
        val store=MemoryStore(AppState(accounts=listOf(account("main"))));Runner(store,mapOf(Platform.SKLAND to adapter)).run()
        assertEquals(1,adapter.writes)
        assertEquals(1,store.value.records.count{it.status==TaskStatus.EXPIRED})
        assertEquals(1,store.value.records.count{it.status==TaskStatus.INTERRUPTED && it.message.contains("登录失效，请先更新凭证")})
    }
    @Test fun completedTasksAreSkippedAndUnknownIsRetriable()=runBlocking {
        val adapter=FakeAdapter();val store=MemoryStore(AppState(accounts=listOf(account("main"))));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        runner.run();runner.run();assertEquals(2,adapter.writes)
        store.update{s->s.copy(records=s.records.map{if(it.targetId=="ak")it.copy(status=TaskStatus.UNKNOWN)else it})}
        runner.run(true);assertEquals(3,adapter.writes)
    }
    @Test fun requiredFreshCheckCannotReuseLocalSuccessWhenReadIsUnavailable()=runBlocking {
        val a=account("main").copy(enabled=setOf("ak"));val adapter=FakeAdapter()
        val store=MemoryStore(AppState(accounts=listOf(a)));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        assertEquals(1,runner.run())
        adapter.freshCheckRequired=true
        assertEquals(0,runner.run())
        assertEquals(1,adapter.writes)
        assertEquals(TaskStatus.UNKNOWN,store.value.records.last().status)
        assertEquals(TaskStatus.SUCCESS,store.value.records.first().status)
        assertEquals(0,runner.run(true))
        assertEquals(1,adapter.writes)
        assertEquals(TaskStatus.UNKNOWN,store.value.records.last().status)
    }
    @Test fun requiredFreshCheckAcceptsServerCompletionWithoutAnotherWrite()=runBlocking {
        val a=account("main").copy(enabled=setOf("ak"));val adapter=FakeAdapter()
        val store=MemoryStore(AppState(accounts=listOf(a)));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        runner.run();adapter.freshCheckRequired=true
        adapter.queryAction={_,_->TaskResult(TaskStatus.ALREADY,"server checked fixture")}
        assertEquals(1,runner.run())
        assertEquals(1,adapter.writes);assertEquals(TaskStatus.ALREADY,store.value.records.last().status)
    }
    @Test fun freshUnsignedStateOverridesOldCompletionAndAllowsExecution()=runBlocking {
        val a=account("main").copy(enabled=setOf("ak"));val adapter=FakeAdapter()
        val store=MemoryStore(AppState(accounts=listOf(a)));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        runner.run();adapter.freshCheckRequired=true
        adapter.queryAction={_,_->TaskResult(TaskStatus.PENDING,"server unsigned fixture")}
        assertEquals(1,runner.run())
        assertEquals(2,adapter.writes);assertEquals(TaskStatus.SUCCESS,store.value.records.last().status)
    }
    @Test fun unfinishedRecordsRecoverAsInterrupted() {
        val a=account("main");val r=Record(accountId=a.id,platform=a.platform,accountLabel=a.note,targetId="ak",task="ak",period="x",status=TaskStatus.RUNNING,message="fixture")
        val store=MemoryStore(AppState(accounts=listOf(a),records=listOf(r)));Runner(store,emptyMap()).recover()
        assertEquals(TaskStatus.INTERRUPTED,store.value.records.single().status)
    }
    @Test fun duplicateIdentityAndCredentialSwitchAreRejected() {
        val a=account("main");val state=AppState(accounts=listOf(a));val id=Identity("main","name",a.targets)
        assertThrows(IllegalArgumentException::class.java){Rules.addOrUpdate(state,id,Platform.SKLAND,"secret","clone",null)}
        assertThrows(IllegalArgumentException::class.java){Rules.addOrUpdate(state,id.copy(userId="wrong"),Platform.SKLAND,"secret","main",a.id)}
        val next=Rules.addOrUpdate(state,id.copy(userId="clone"),Platform.SKLAND,"other","clone",null)
        assertEquals(2,next.accounts.size)
    }
    @Test fun unboundTargetsAreNotSelected() {
        val id=Identity("new","new",listOf(Target("unbound","ef","终末地",bound=false)))
        assertTrue(Rules.addOrUpdate(AppState(),id,Platform.SKLAND,"secret","",null).accounts.single().enabled.isEmpty())
    }
    @Test fun dayBoundaryAndRedaction() {
        val t=target("ak","main")
        assertEquals("2026-10-18",Rules.period(t,Instant.parse("2026-10-17T16:00:00Z")))
        assertEquals("2026-10-17",Rules.period(t,Instant.parse("2026-10-17T15:59:59Z")))
        assertEquals("code=42",Rules.safeDiagnostic("raw Cookie=secret; code=42; uid=123"))
        assertFalse(Rules.safeMessage("token=abcdefgh123456", "abcdefgh123456").contains("abcdefgh123456"))
    }
    @Test fun concurrentTriggerDoesNotDoubleWrite()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val adapter=FakeAdapter().apply {action={_,_->entered.countDown();release.await(3,TimeUnit.SECONDS);TaskResult(TaskStatus.SUCCESS,"fixture")}}
        val store=MemoryStore(AppState(accounts=listOf(account("main").copy(enabled=setOf("ak")))));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        val first=async(Dispatchers.IO){runner.run()};assertTrue(entered.await(3,TimeUnit.SECONDS))
        assertEquals(0,runner.run());release.countDown();first.await();assertEquals(1,adapter.writes)
    }
    @Test fun cancellationDuringQueryNeverStartsWrite()=runBlocking {
        val entered=CountDownLatch(1)
        val adapter=FakeAdapter().apply {queryAction={_,_->entered.countDown();CountDownLatch(1).await(3,TimeUnit.SECONDS);TaskResult(TaskStatus.PENDING,"fixture")}}
        val store=MemoryStore(AppState(accounts=listOf(account("main").copy(enabled=setOf("ak")))))
        val job=launch(Dispatchers.IO){Runner(store,mapOf(Platform.SKLAND to adapter)).run()}
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        withTimeout(5000){job.cancelAndJoin()}
        assertEquals(0,adapter.writes);assertEquals(TaskStatus.INTERRUPTED,store.value.records.single().status)
    }

    @Test fun stablePriorityRunsAllSigningBeforeLikesAndSkippedLikesAreRetriable()=runBlocking {
        val tasks = listOf(Target("like-a","daily-like","A吧点赞"), Target("sign-a","forum","A吧签到"),
            Target("like-b","daily-like","B吧点赞"), Target("sign-b","forum","B吧签到"))
        val a = account("main").copy(targets=tasks,enabled=tasks.map { it.id }.toSet())
        val store = MemoryStore(AppState(accounts=listOf(a)))
        val queries = mutableListOf<String>(); val writes = mutableListOf<String>()
        val adapter = FakeAdapter().apply {
            validateAction={Identity("main","main",tasks)}
            order={if(it.type=="daily-like")1 else 0}
            queryAction={_,t->queries+=t.id;null}
            action={_,t->writes+=t.id;if(t.id=="like-a")TaskResult(TaskStatus.MANUAL,"已停止点赞","code=123") else TaskResult(TaskStatus.SUCCESS,"已签到")}
        }
        val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        assertEquals(2,runner.run())
        assertEquals(listOf("sign-a","sign-b","like-a"),writes)
        assertEquals(writes,queries)
        val trigger=store.value.records.single { it.targetId=="like-a" }
        assertEquals(TaskStatus.MANUAL,trigger.status);assertEquals("已停止点赞",trigger.message)
        val skipped=store.value.records.single { it.targetId=="like-b" }
        assertEquals(TaskStatus.INTERRUPTED,skipped.status)
        assertEquals("未执行：同账号的「A吧点赞」触发了平台验证，为避免加重风控已暂停；处理后点「仅重试失败 / 待确认项」补签",skipped.message)
        assertEquals("",skipped.diagnostic)
        adapter.action={_,t->writes+=t.id;TaskResult(TaskStatus.SUCCESS,"fixture")}
        assertEquals(1,runner.run(true))
        assertEquals(listOf("sign-a","sign-b","like-a","like-b"),writes)
    }

    @Test fun manualStopsOnlyItsExecutionGroupWhileExpiredStopsAllGroups()=runBlocking {
        for(status in listOf(TaskStatus.MANUAL,TaskStatus.EXPIRED)) {
            val tasks=listOf(Target("like","daily-like","点赞"),Target("first","forum","签到甲"),Target("second","forum","签到乙"))
            val a=account("main").copy(targets=tasks,enabled=tasks.map { it.id }.toSet())
            val store=MemoryStore(AppState(accounts=listOf(a)))
            val calls=mutableListOf<String>()
            val adapter=FakeAdapter().apply {
                validateAction={Identity("main","main",tasks)}
                order={if(it.type=="daily-like")1 else 0}
                action={_,t->calls+=t.id;if(t.id=="first")TaskResult(status,"真实拒绝","code=42")else TaskResult(TaskStatus.SUCCESS,"完成")}
            }
            Runner(store,mapOf(Platform.SKLAND to adapter)).run()
            assertEquals(if(status==TaskStatus.MANUAL)listOf("first","like")else listOf("first"),calls)
            assertEquals(status,store.value.records.single { it.targetId=="first" }.status)
            assertEquals(TaskStatus.INTERRUPTED,store.value.records.single { it.targetId=="second" }.status)
            assertEquals(if(status==TaskStatus.MANUAL)TaskStatus.SUCCESS else TaskStatus.INTERRUPTED,
                store.value.records.single { it.targetId=="like" }.status)
            assertTrue(store.value.records.filter { it.status==TaskStatus.INTERRUPTED }.all { it.diagnostic.isEmpty() && it.message.contains("「签到甲」") })
        }
    }
}
