package cn.onetap.checkin.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TargetDiscoveryTest {
    private val old=Target("old","fixture","旧任务")
    private val added=Target("new","fixture","新任务",metadata=mapOf("defaultEnabled" to "true"))
    private val optional=Target("optional","fixture","可选任务",metadata=mapOf("defaultEnabled" to "false"))
    private val unbound=Target("unbound","fixture","未绑定任务",bound=false)
    private fun account(targets:List<Target> = listOf(old),enabled:Set<String> = setOf(old.id)) =
        Account(id="account",platform=Platform.NETEASE,userId="123",displayName="账号",note="",credential="fixture",targets=targets,enabled=enabled)
    private class MemoryStore(private var state:AppState):StateStore {
        @Synchronized override fun read()=state
        @Synchronized override fun update(change:(AppState)->AppState):AppState {state=change(state);return state}
    }
    private class Adapter(private val identity:()->Identity):PlatformAdapter {
        override val platform=Platform.NETEASE
        val executed=mutableListOf<String>()
        override fun validate(credential:String)=identity()
        override fun execute(credential:String,target:Target):TaskResult {
            executed+=target.id
            return TaskResult(TaskStatus.SUCCESS,"签到成功")
        }
    }

    @Test fun refreshEnablesOnlyNewBoundDefaultTargetsAndPreservesExistingChoices() {
        val disabled=old.copy(id="disabled")
        val previous=account(listOf(old,disabled),setOf(old.id))
        val implicit=added.copy(id="implicit",metadata=emptyMap())
        val identity=Identity("123","账号",listOf(old,disabled,added,implicit,optional,unbound,added))
        val refreshed=Rules.addOrUpdate(AppState(accounts=listOf(previous)),identity,Platform.NETEASE,"fixture","",previous.id).accounts.single()
        assertEquals(setOf(old.id,added.id,implicit.id),refreshed.enabled)
        assertEquals(6,refreshed.targets.size)
        val deselected=refreshed.copy(enabled=refreshed.enabled-added.id)
        val again=Rules.addOrUpdate(AppState(accounts=listOf(deselected)),identity,Platform.NETEASE,"fixture","",previous.id).accounts.single()
        assertFalse(added.id in again.enabled)
    }

    @Test fun runnerAppendsAndExecutesNewDefaultsWithoutDeletingOrReplacingExistingTargets()=runBlocking {
        val removed=old.copy(id="removed",label="旧绑定保留")
        val previous=account(listOf(old,removed))
        val store=MemoryStore(AppState(accounts=listOf(previous)))
        val adapter=Adapter {Identity("123","账号",listOf(old.copy(label="不得覆盖原标签"),added,added,optional,unbound),updatedCredential="refreshed")}
        val events=mutableListOf<RunProgress>()
        val runner=Runner(store,mapOf(Platform.NETEASE to adapter))
        assertEquals(2,runner.run {events+=it})
        val saved=store.read().accounts.single()
        assertEquals(listOf(old,removed,added),saved.targets)
        assertEquals(setOf(old.id,added.id),saved.enabled)
        assertEquals("refreshed",saved.credential)
        assertEquals(listOf(old.id,added.id),adapter.executed)
        assertTrue(events.all {it.total==2});assertEquals(2,events.last().finished)
        assertEquals(2,runner.run())
        assertEquals(listOf(old.id,added.id),adapter.executed)
        assertEquals(1,store.read().accounts.single().targets.count {it.id==added.id})
    }

    @Test fun runnerDiscoversWhenNothingSelectedAndDuringRetryOnlyRun()=runBlocking {
        for(onlyFailed in listOf(false,true)) {
            val store=MemoryStore(AppState(accounts=listOf(account(enabled=emptySet()))))
            val adapter=Adapter {Identity("123","账号",listOf(old,added,optional,unbound))}
            assertEquals(1,Runner(store,mapOf(Platform.NETEASE to adapter)).run(onlyFailed))
            assertEquals(listOf(added.id),adapter.executed)
            assertEquals(listOf(old,added),store.read().accounts.single().targets)
            assertEquals(setOf(added.id),store.read().accounts.single().enabled)
        }
    }

    @Test fun automaticRetryExecutesOnlyExistingRetryableTargets() = runBlocking {
        val account = account()
        val failure = Record(accountId = account.id, platform = account.platform, accountLabel = account.displayName,
            targetId = old.id, task = old.label, period = Rules.period(old), status = TaskStatus.UNKNOWN, message = "")
        val store = MemoryStore(AppState(accounts = listOf(account), records = listOf(failure)))
        val adapter = Adapter { Identity("123", "账号", listOf(old, added)) }
        val runner = Runner(store, mapOf(Platform.NETEASE to adapter))

        assertEquals(1, runner.run(onlyFailed = true, discoverNewTargets = false))
        assertEquals(listOf(old.id), adapter.executed)
        assertEquals(listOf(old), store.read().accounts.single().targets)
        assertEquals(0, runner.run(onlyFailed = true, discoverNewTargets = false))
    }

    @Test fun mergePreservesConcurrentSelectionAndDoesNotReenableConcurrentlyAddedTarget()=runBlocking {
        val other=added.copy(id="other")
        val store=MemoryStore(AppState(accounts=listOf(account(enabled=emptySet()))))
        val adapter=Adapter {
            store.update {s->s.copy(accounts=s.accounts.map {it.copy(note="用户刚改的备注",targets=it.targets+added,enabled=emptySet())})}
            Identity("123","账号",listOf(old,added,other))
        }
        assertEquals(1,Runner(store,mapOf(Platform.NETEASE to adapter)).run())
        val saved=store.read().accounts.single()
        assertEquals(listOf(old,added,other),saved.targets)
        assertEquals(setOf(other.id),saved.enabled)
        assertEquals("用户刚改的备注",saved.note)
        assertEquals(listOf(other.id),adapter.executed)
    }

    @Test fun changedIdentityCredentialOrDeletedAccountNeverImportsOrExecutesNewTargets()=runBlocking {
        for(change in listOf("identity","credential","delete")) {
            val previous=account(enabled=emptySet())
            val store=MemoryStore(AppState(accounts=listOf(previous)))
            val adapter=Adapter {
                if(change=="credential")store.update {s->s.copy(accounts=s.accounts.map {it.copy(credential="replacement")})}
                if(change=="delete")store.update {it.copy(accounts=emptyList())}
                Identity(if(change=="identity")"456" else "123","账号",listOf(old,added))
            }
            assertEquals(0,Runner(store,mapOf(Platform.NETEASE to adapter)).run())
            assertTrue(adapter.executed.isEmpty())
            assertTrue(store.read().accounts.all {it.targets==listOf(old)})
            if(change=="delete")assertTrue(store.read().accounts.isEmpty())
            if(change=="credential")assertEquals("replacement",store.read().accounts.single().credential)
        }
    }

    @Test fun oldSelectedTargetIsPreservedEvenIfValidationNoLongerListsIt()=runBlocking {
        val store=MemoryStore(AppState(accounts=listOf(account())))
        val adapter=Adapter {Identity("123","账号",listOf(added))}
        assertEquals(1,Runner(store,mapOf(Platform.NETEASE to adapter)).run())
        assertEquals(listOf(old,added),store.read().accounts.single().targets)
        assertEquals(setOf(old.id,added.id),store.read().accounts.single().enabled)
        assertEquals(TaskStatus.UNBOUND,store.read().records.single {it.targetId==old.id}.status)
        assertEquals(listOf(added.id),adapter.executed)
    }
}
