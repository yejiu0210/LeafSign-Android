package cn.onetap.checkin.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class RunnerConcurrencyTest {
    private class MemoryStore(private var state: AppState) : StateStore {
        @Synchronized override fun read() = state
        @Synchronized override fun update(change: (AppState) -> AppState): AppState {
            state = change(state); return state
        }
    }
    private fun accounts(count: Int, targets: Int) = (1..count).map { uid ->
        val tasks = (1..targets).map { Target("target-$it", "fixture", "目标$it") }
        Account(id="account-$uid", platform=Platform.SKLAND, userId="$uid", displayName="账号$uid", note="",
            credential="$uid", targets=tasks, enabled=tasks.map { it.id }.toSet())
    }
    private class FakeAdapter(
        private val accounts: List<Account>, override val maxTargetConcurrency: Int = 1,
        private val action: (String, Target) -> TaskResult
    ) : PlatformAdapter {
        override val platform = Platform.SKLAND
        override fun validate(credential: String) = accounts.single { it.userId==credential }.let {
            Identity(it.userId, it.displayName, it.targets, updatedCredential=credential)
        }
        override fun execute(credential: String, target: Target) = action(credential, target)
    }

    @Test fun accountsRunFasterInParallelAndConcurrencyOneIsSerial() = runBlocking {
        suspend fun measured(concurrency: Int): Long {
            val accounts = accounts(6, 3)
            val store = MemoryStore(AppState(accounts=accounts, settings=Settings(concurrency=concurrency)))
            val active = AtomicInteger(); val peak = AtomicInteger()
            val calls = ConcurrentHashMap<String, AtomicInteger>()
            val adapter = FakeAdapter(accounts) { uid, target ->
                calls.computeIfAbsent("$uid/${target.id}") { AtomicInteger() }.incrementAndGet()
                val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }
                try { Thread.sleep(100); TaskResult(TaskStatus.SUCCESS, "fixture") }
                finally { active.decrementAndGet() }
            }
            val events = mutableListOf<RunProgress>()
            val inCallback = AtomicBoolean()
            val elapsed = measureTimeMillis {
                assertEquals(18, Runner(store, mapOf(Platform.SKLAND to adapter)).run { event ->
                    assertTrue("callbacks must not overlap", inCallback.compareAndSet(false, true))
                    try { events += event; Thread.sleep(1) } finally { inCallback.set(false) }
                })
            }
            assertEquals(18, calls.size); assertTrue(calls.values.all { it.get()==1 })
            assertEquals(18, store.read().records.size)
            assertTrue(store.read().records.all { it.status==TaskStatus.SUCCESS })
            assertTrue(peak.get() <= concurrency)
            if (concurrency==1) assertEquals(1, peak.get()) else assertTrue(peak.get() > 1)
            assertEquals(RunProgress(18, 0, emptyList(), ""), events.first())
            assertEquals(18, events.last().finished); assertTrue(events.last().running.isEmpty())
            assertTrue(events.zipWithNext().all { (a,b) -> b.finished >= a.finished })
            assertTrue(events.all { it.total==18 && it.running.size<=concurrency })
            assertTrue(events.last().last.matches(Regex("森空岛 · 账号[1-6] · 目标[1-3] · 签到成功")))
            return elapsed
        }
        val serial = measured(1)
        val parallel = measured(4)
        assertTrue("parallel=$parallel ms, serial=$serial ms", parallel < serial * 0.75)
    }

    @Test fun targetConcurrencyIsBoundedAndEveryTargetExecutesOnce() = runBlocking {
        val accounts = accounts(1, 9)
        val store = MemoryStore(AppState(accounts=accounts))
        val entered = CountDownLatch(3)
        val active = AtomicInteger(); val peak = AtomicInteger()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val adapter = FakeAdapter(accounts, 3) { _, target ->
            calls.computeIfAbsent(target.id) { AtomicInteger() }.incrementAndGet()
            val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }
            try {
                entered.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS))
                Thread.sleep(20); TaskResult(TaskStatus.SUCCESS, "fixture")
            } finally { active.decrementAndGet() }
        }
        assertEquals(9, Runner(store, mapOf(Platform.SKLAND to adapter)).run())
        assertEquals(3, peak.get()); assertEquals(9, calls.size)
        assertTrue(calls.values.all { it.get()==1 })
    }

    @Test fun expiredOrManualStopsQueuedTargetsButAllowsAlreadyStartedTargetToFinish() = runBlocking {
        for (status in listOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) {
            val accounts = accounts(1, 6)
            val store = MemoryStore(AppState(accounts=accounts))
            val entered = CountDownLatch(2); val skipped = CountDownLatch(1)
            val calls = AtomicInteger()
            val adapter = FakeAdapter(accounts, 2) { _, _ ->
                val index = calls.incrementAndGet()
                entered.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (index==1) TaskResult(status, "停止此账号") else {
                    assertTrue(skipped.await(3, TimeUnit.SECONDS))
                    TaskResult(TaskStatus.SUCCESS, "已开始的目标完成")
                }
            }
            assertEquals(1, Runner(store, mapOf(Platform.SKLAND to adapter)).run {
                if (it.finished==5) skipped.countDown()
            })
            assertEquals(2, calls.get())
            assertEquals(1, store.read().records.count { it.status==status && it.message=="停止此账号" })
            val skippedRecords = store.read().records.filter { it.status == TaskStatus.INTERRUPTED }
            assertEquals(4, skippedRecords.size)
            assertTrue(skippedRecords.all { it.message.startsWith("未执行：同账号的「目标") && it.diagnostic.isEmpty() })
            assertEquals(1, store.read().records.count { it.status==TaskStatus.SUCCESS })
        }
    }
}
