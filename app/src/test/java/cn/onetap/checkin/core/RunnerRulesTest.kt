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
        var writes=0
        var validateAction:(String)->Identity={c->Identity(c,c,listOf(target("ak",c),target("ef",c)))}
        var queryAction:(String,Target)->TaskResult?={_,_->null}
        var action:(String,Target)->TaskResult={_,_->TaskResult(TaskStatus.SUCCESS,"test fixture")}
        override fun validate(credential:String)=validateAction(credential)
        override fun query(credential:String,target:Target)=queryAction(credential,target)
        override fun execute(credential:String,target:Target):TaskResult {writes++;return action(credential,target)}
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
        assertTrue(store.value.records.all {it.status==TaskStatus.EXPIRED})
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
        assertEquals(1,adapter.writes);assertTrue(store.value.records.all{it.status==TaskStatus.EXPIRED})
    }
    @Test fun completedTasksAreSkippedAndUnknownIsRetriable()=runBlocking {
        val adapter=FakeAdapter();val store=MemoryStore(AppState(accounts=listOf(account("main"))));val runner=Runner(store,mapOf(Platform.SKLAND to adapter))
        runner.run();runner.run();assertEquals(2,adapter.writes)
        store.update{s->s.copy(records=s.records.map{if(it.targetId=="ak")it.copy(status=TaskStatus.UNKNOWN)else it})}
        runner.run(true);assertEquals(3,adapter.writes)
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
}
