package cn.onetap.checkin.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

class Runner(private val store: StateStore, private val adapters: Map<Platform,PlatformAdapter>) {
    companion object { val mutex = Mutex() }
    fun recover() {
        if (mutex.tryLock()) try { store.update { old -> val s=Rules.retireOrdinaryMusicTask(old);s.copy(records=s.records.map { if(it.status==TaskStatus.RUNNING) it.copy(status=TaskStatus.INTERRUPTED,message="上次没跑完，结果未确认") else it }) } } finally { mutex.unlock() }
    }
    suspend fun run(onlyFailed: Boolean = false, progress: (RunProgress) -> Unit = {}): Int =
        run(onlyFailed, discoverNewTargets = true, progress)

    internal suspend fun run(onlyFailed: Boolean, discoverNewTargets: Boolean, progress: (RunProgress) -> Unit = {}): Int {
        if (!mutex.tryLock()) return 0
        try {
            val snapshot = store.read()
            val accounts = Semaphore(snapshot.settings.concurrency.coerceIn(1,8))
            // Discover new default tasks even when an old account currently has no selected/retriable target.
            val work = coroutineScope { snapshot.accounts.map { account -> async(Dispatchers.IO) {
                accounts.withPermit { prepareAccount(snapshot, account, onlyFailed, discoverNewTargets) }
            } }.awaitAll() }.filter { it.targets.isNotEmpty() }
            val reporter = ProgressReporter(work.sumOf { it.targets.size }, progress)
            reporter.publish()
            coroutineScope {
                work.forEach { plan -> launch(Dispatchers.IO) {
                    accounts.withPermit { runAccount(snapshot, plan, reporter) }
                } }
            }
            return reporter.completed
        } finally { mutex.unlock() }
    }

    private data class AccountPlan(val account: Account, val targets: List<Target>, val currentTargetIds: Set<String>, val validation: TaskResult?)

    private suspend fun prepareAccount(snapshot: AppState, account: Account, onlyFailed: Boolean, discoverNewTargets: Boolean): AccountPlan {
        currentCoroutineContext().ensureActive()
        val selected = account.targets.filter { it.bound && it.id in account.enabled }.filter { target ->
            !onlyFailed || Rules.retryable(Rules.latest(snapshot,account,target)?.status)
        }
        if (!discoverNewTargets && selected.isEmpty()) return AccountPlan(account, emptyList(), emptySet(), null)
        val adapter = adapters.getValue(account.platform)
        var added: List<Target> = emptyList()
        // A refreshed credential can never silently execute against another identity.
        var currentTargetIds: Set<String> = emptySet()
        val validation = try {
            var validatedCredential = ""
            val actual = readWithRetry { validatedCredential=currentCredential(account);adapter.validate(validatedCredential) }
            currentTargetIds=actual.targets.filter { it.bound }.map { it.id }.toSet()
            if (actual.userId != account.userId) TaskResult(TaskStatus.EXPIRED,"登录的不是这个账号，请重新登录") else {
                val previousIds = account.targets.map { it.id }.toSet()
                val candidates = if (!discoverNewTargets) emptyList() else actual.targets.distinctBy { it.id }.filter {
                    it.bound && it.metadata["defaultEnabled"] != "false" && it.id !in previousIds
                }
                var accepted = false
                store.update { s -> s.copy(accounts=s.accounts.map { current ->
                    if(current.id==account.id && current.platform==account.platform && current.userId==actual.userId && current.credential==validatedCredential) {
                        accepted = true
                        val existingIds = current.targets.map { it.id }.toSet()
                        added = candidates.filter { it.id !in existingIds }
                        current.copy(credential=actual.updatedCredential ?: current.credential,
                            targets=current.targets+added, enabled=current.enabled+added.map { it.id })
                    } else current
                }) }
                if (accepted) null else TaskResult(TaskStatus.EXPIRED,"账号刚有变动，请再试一次")
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e,account.credential) }
        return AccountPlan(account, (selected + if (validation == null) added else emptyList()).distinctBy { it.id }, currentTargetIds, validation)
    }

    private suspend fun runAccount(snapshot: AppState, plan: AccountPlan, reporter: ProgressReporter) {
        currentCoroutineContext().ensureActive()
        val (account, targets, currentTargetIds, validation) = plan
        val adapter = adapters.getValue(account.platform)
        // Heybox has one daily task. Record the restore_login failure on it so re-login guidance is visible.
        val validationResult = validation?.let {
            if (account.platform != Platform.HEYBOX && it.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL))
                StopCause("账号身份校验", it.status).interrupted()
            else it
        }
        val stopAccount = AtomicReference<StopCause?>()
        val stoppedGroups = ConcurrentHashMap<Int, StopCause>()
        suspend fun runTarget(target: Target, order: Int) {
            currentCoroutineContext().ensureActive()
            // Read after acquiring the permit: queued targets have not sent any request of their own.
            // Targets that already passed this point are allowed to finish.
            val stopped = stopAccount.get() ?: stoppedGroups[order]
            val label = "${account.platform.title} · ${account.note.ifBlank { account.displayName }} · ${target.label}"
            val record = Record(accountId=account.id,platform=account.platform,accountLabel=account.note.ifBlank { account.displayName },targetId=target.id,task=listOf(target.game,target.label,target.server).filter { it.isNotBlank() }.joinToString(" · "),period=Rules.period(target),status=TaskStatus.RUNNING,message="正在执行")
            store.update { it.copy(records=(it.records+record).takeLast(3000)) }
            if (stopped == null && validation == null && target.id in currentTargetIds) reporter.start(record.id, label)
            val result = validationResult ?: stopped?.interrupted() ?: if(target.id !in currentTargetIds) TaskResult(TaskStatus.UNBOUND,"这一项已不在账号里，请在账号页刷新") else try {
                val known = readWithRetry { adapter.query(currentCredential(account),target) }
                currentCoroutineContext().ensureActive()
                val prior=Rules.latest(snapshot,account,target)
                val freshCheckRequired=adapter.requiresFreshCompletionCheck(target)
                val priorCompletion=if(freshCheckRequired) snapshot.records.any {
                    it.accountId==account.id && it.targetId==target.id && it.period==record.period && Rules.completed(it.status)
                } else Rules.completed(prior?.status?:TaskStatus.PENDING)
                if (known != null && known.status != TaskStatus.PENDING) known
                else if(known==null && priorCompletion) {
                    if(freshCheckRequired) TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试")
                    else TaskResult(prior!!.status,"今日已签到",prior.diagnostic,prior.experienceState,stats=prior.stats)
                } else executeWithReconciliation(adapter,account,target)
            } catch(e: CancellationException) {
                finish(record,TaskResult(TaskStatus.INTERRUPTED,"被中断了，结果未确认"))
                reporter.finish(record.id, "$label · ${TaskStatus.INTERRUPTED.title}", TaskStatus.INTERRUPTED)
                throw e
            }
              catch(e: Exception) { failure(e,account.credential) }
            if (result.status == TaskStatus.EXPIRED) stopAccount.compareAndSet(null, StopCause(target.label, result.status))
            if (result.status == TaskStatus.MANUAL) stoppedGroups.putIfAbsent(order, StopCause(target.label, result.status))
            // Account removal must not prevent finishing a record or redact with another identity.
            val latestCredential = store.read().accounts.singleOrNull {
                it.id==account.id && it.platform==account.platform && it.userId==account.userId
            }?.credential.orEmpty()
            fun redact(value: String) = Rules.safeMessage(Rules.safeMessage(value,account.credential),latestCredential)
            val safe = result.copy(message=redact(result.message),diagnostic=Rules.safeDiagnostic(result.diagnostic),experienceState=redact(result.experienceState))
            finish(record,safe)
            reporter.finish(record.id, "$label · ${Rules.resultTitle(safe.status,target.id)}", safe.status)
        }
        val concurrency = adapter.maxTargetConcurrency.coerceAtLeast(1)
        val permits = Semaphore(concurrency)
        // Stable sorting preserves original order within a class. Finish each class before starting the next.
        val groups = targets.sortedBy(adapter::executionOrder).groupBy(adapter::executionOrder)
        for ((order, group) in groups) {
            if (concurrency == 1) for (target in group) runTarget(target, order)
            else coroutineScope { group.forEach { target -> launch { permits.withPermit { runTarget(target, order) } } } }
        }
    }

    private data class StopCause(val label: String, val status: TaskStatus) {
        fun interrupted(): TaskResult {
            val reason = if (status == TaskStatus.EXPIRED) "重新登录" else "验证"
            return TaskResult(TaskStatus.INTERRUPTED,
                "已暂停：「$label」需要先$reason，处理好后点「仅重试失败项」")
        }
    }

    /** Serialize both snapshots and callbacks, including when run() is called on a multithreaded dispatcher. */
    private class ProgressReporter(private val total: Int, private val callback: (RunProgress) -> Unit) {
        private val running = linkedMapOf<String, String>()
        private var finished = 0
        private var last = ""
        var completed = 0
            private set
        @Synchronized fun publish() { callback(RunProgress(total, finished, running.values.toList(), last)) }
        @Synchronized fun start(id: String, label: String) { running[id] = label; publish() }
        @Synchronized fun finish(id: String, message: String, status: TaskStatus) {
            running.remove(id); finished++; last = message
            if (Rules.completed(status)) completed++
            publish()
        }
    }
    private suspend fun executeWithReconciliation(adapter: PlatformAdapter,account: Account,target: Target): TaskResult {
        currentCoroutineContext().ensureActive()
        try { return runInterruptible(Dispatchers.IO) { adapter.execute(currentCredential(account),target) } }
        catch(e: IOException) {
            // POST may have reached the server. Only retry if a read proves it did not complete.
            val status = try { readWithRetry { adapter.query(currentCredential(account),target) } } catch (_: IOException) { null }
            if (status != null && status.status != TaskStatus.PENDING) return status
            if (status?.status == TaskStatus.PENDING) {
                delay(1500)
                return try { runInterruptible(Dispatchers.IO) { adapter.execute(currentCredential(account),target) } } catch (_: IOException) { TaskResult(TaskStatus.UNKNOWN,"请求超时，结果未确认") }
            }
            return TaskResult(TaskStatus.UNKNOWN,"请求中断，结果未确认")
        }
    }
    private fun currentCredential(account: Account): String = store.read().accounts.singleOrNull {
        it.id == account.id && it.platform == account.platform && it.userId == account.userId
    }?.credential?.takeIf { it.isNotBlank() } ?: throw AdapterException(TaskStatus.EXPIRED,"账号已退出或已删除")
    private suspend fun <T> readWithRetry(action: () -> T): T {
        try { return runInterruptible(Dispatchers.IO) { action() } } catch (_: IOException) { delay(900); return runInterruptible(Dispatchers.IO) { action() } }
    }
    private fun finish(record: Record, result: TaskResult) {
        val stats = result.stats.map { it.copy(label = it.label.take(40), value = it.value.take(40)) }
        store.update { state ->
            state.copy(records = state.records.map {
                if (it.id == record.id) it.copy(
                    status = result.status,
                    message = result.message,
                    diagnostic = result.diagnostic,
                    experienceState = result.experienceState,
                    stats = stats
                ) else it
            })
        }
    }
    private fun failure(e: Exception, credential: String) = when(e) {
        is AdapterException -> TaskResult(e.status,Rules.safeMessage(e.message,credential),Rules.safeDiagnostic(e.diagnostic))
        is IOException -> TaskResult(TaskStatus.FAILED,"网络连接失败，请检查网络后重试","exception=${e.javaClass.simpleName}")
        else -> TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试","exception=${e.javaClass.simpleName}")
    }
}
