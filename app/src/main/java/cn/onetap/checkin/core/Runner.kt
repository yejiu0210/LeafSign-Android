package cn.onetap.checkin.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

class Runner(private val store: StateStore, private val adapters: Map<Platform,PlatformAdapter>) {
    companion object { val mutex = Mutex() }
    fun recover() {
        if (mutex.tryLock()) try { store.update { old -> val s=Rules.retireOrdinaryMusicTask(old);s.copy(records=s.records.map { if(it.status==TaskStatus.RUNNING) it.copy(status=TaskStatus.INTERRUPTED,message="上次进程中断，请查询或重试确认") else it }) } } finally { mutex.unlock() }
    }
    suspend fun run(onlyFailed: Boolean = false, progress: (String) -> Unit = {}): Int {
        if (!mutex.tryLock()) return 0
        var completed = 0
        try {
            val snapshot = store.read()
            for (account in snapshot.accounts) {
                currentCoroutineContext().ensureActive()
                val targets = account.targets.filter { it.bound && it.id in account.enabled }.filter { target ->
                    val last = Rules.latest(store.read(),account,target)
                    !onlyFailed || last?.status in setOf(TaskStatus.FAILED,TaskStatus.UNKNOWN,TaskStatus.INTERRUPTED)
                }
                if (targets.isEmpty()) continue
                val adapter = adapters.getValue(account.platform)
                // A refreshed credential can never silently execute against another identity.
                var currentTargetIds: Set<String> = emptySet()
                val validation = try {
                    var validatedCredential = ""
                    val actual = readWithRetry { validatedCredential=currentCredential(account);adapter.validate(validatedCredential) }
                    currentTargetIds=actual.targets.filter { it.bound }.map { it.id }.toSet()
                    if (actual.userId != account.userId) TaskResult(TaskStatus.EXPIRED,"当前凭证身份与保存账号不一致，请更新凭证") else {
                        actual.updatedCredential?.let { fresh -> store.update { s -> s.copy(accounts=s.accounts.map {
                            if(it.id==account.id && it.platform==account.platform && it.userId==actual.userId && it.credential==validatedCredential) it.copy(credential=fresh) else it
                        }) } }
                        null
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e,account.credential) }
                var stopAccount: TaskResult? = validation
                for (target in targets) {
                    currentCoroutineContext().ensureActive()
                    progress("${account.platform.title} · ${account.note.ifBlank { account.displayName }} · ${target.label}")
                    val record = Record(accountId=account.id,platform=account.platform,accountLabel=account.note.ifBlank { account.displayName },targetId=target.id,task=listOf(target.game,target.label,target.server).filter { it.isNotBlank() }.joinToString(" · "),period=Rules.period(target),status=TaskStatus.RUNNING,message="正在执行")
                    store.update { it.copy(records=(it.records+record).takeLast(3000)) }
                    val result = stopAccount ?: if(target.id !in currentTargetIds) TaskResult(TaskStatus.UNBOUND,"此目标已不在当前账号的绑定或关注列表，未执行；请刷新账号") else try {
                        val known = readWithRetry { adapter.query(currentCredential(account),target) }
                        currentCoroutineContext().ensureActive()
                        if (known != null && known.status != TaskStatus.PENDING) known
                        else if(known==null && Rules.completed(Rules.latest(snapshot,account,target)?.status?:TaskStatus.PENDING)) {
                            val prior=Rules.latest(snapshot,account,target)!!
                            TaskResult(prior.status,"当前本地周期内已确认完成；平台无可靠只读查询，未重复发送",prior.diagnostic,prior.experienceState)
                        } else executeWithReconciliation(adapter,account,target)
                    } catch(e: CancellationException) { finish(record,TaskResult(TaskStatus.INTERRUPTED,"任务被中断，结果待确认")); throw e }
                      catch(e: Exception) { failure(e,account.credential) }
                    fun redact(value: String) = Rules.safeMessage(Rules.safeMessage(value,account.credential),currentCredential(account))
                    val safe = result.copy(message=redact(result.message),diagnostic=Rules.safeDiagnostic(result.diagnostic),experienceState=redact(result.experienceState))
                    finish(record,safe)
                    progress("${account.platform.title} · ${target.label} · ${Rules.resultTitle(safe.status,target.id)}")
                    if (Rules.completed(safe.status)) completed++
                    if (safe.status in setOf(TaskStatus.EXPIRED,TaskStatus.MANUAL)) stopAccount=safe
                }
            }
            return completed
        } finally { mutex.unlock() }
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
                return try { runInterruptible(Dispatchers.IO) { adapter.execute(currentCredential(account),target) } } catch (_: IOException) { TaskResult(TaskStatus.UNKNOWN,"重试请求超时，结果待确认；请稍后查询") }
            }
            return TaskResult(TaskStatus.UNKNOWN,"请求中断，服务端可能已执行；请稍后重试确认")
        }
    }
    private fun currentCredential(account: Account): String = store.read().accounts.singleOrNull {
        it.id == account.id && it.platform == account.platform && it.userId == account.userId
    }?.credential?.takeIf { it.isNotBlank() } ?: throw AdapterException(TaskStatus.EXPIRED,"本地授权已退出或账号已删除")
    private suspend fun <T> readWithRetry(action: () -> T): T {
        try { return runInterruptible(Dispatchers.IO) { action() } } catch (_: IOException) { delay(900); return runInterruptible(Dispatchers.IO) { action() } }
    }
    private fun finish(record: Record,result: TaskResult) { store.update { s -> s.copy(records=s.records.map { if(it.id==record.id) it.copy(status=result.status,message=result.message,diagnostic=result.diagnostic,experienceState=result.experienceState) else it }) } }
    private fun failure(e: Exception, credential: String) = when(e) {
        is AdapterException -> TaskResult(e.status,Rules.safeMessage(e.message,credential),Rules.safeDiagnostic(e.diagnostic))
        is IOException -> TaskResult(TaskStatus.FAILED,"网络连接失败，请检查网络后重试","exception=${e.javaClass.simpleName}")
        else -> TaskResult(TaskStatus.UNKNOWN,"响应无法可靠确认，请更新凭证或查看接入说明","exception=${e.javaClass.simpleName}")
    }
}
