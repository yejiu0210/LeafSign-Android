package cn.onetap.checkin.sched

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.Record
import cn.onetap.checkin.core.Rules
import cn.onetap.checkin.core.Runner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

internal object AutoRun {
    private val running = AtomicBoolean()

    /** Shared by the service and workers. The daily claim is persisted immediately before execute(). */
    suspend fun run(app: CheckInApp, source: String): String {
        val isRetry = source == RETRY_SOURCE
        var owned = !isRetry && running.compareAndSet(false, true)
        var attempt: Int? = null
        var records: List<Record>? = null
        var summary = "跳过：正在执行"
        try {
            if (!isRetry && !owned) return summary
            summary = withTimeoutOrNull(9 * 60_000L) {
                if (app.storageError.isNotEmpty()) return@withTimeoutOrNull "失败：本地加密数据无法读取"
                val settings = app.store.read().settings
                if (!shouldAutoRun(settings, todayDate(System.currentTimeMillis()), source))
                    return@withTimeoutOrNull autoRunSkipSummary(settings, source)
                if (isRetry) {
                    attempt = app.store.withSettings { app.scheduler.claimRetry(it, System.currentTimeMillis()) }
                        ?: return@withTimeoutOrNull "跳过：重试闹钟已取消或已处理"
                    owned = running.compareAndSet(false, true)
                    if (!owned) return@withTimeoutOrNull "跳过：正在执行"
                }
                if (app.busy.value || Runner.mutex.isLocked) return@withTimeoutOrNull "跳过：正在执行"
                if (!awaitNetwork(app)) return@withTimeoutOrNull if (isRetry) "跳过：90 秒内没有可用网络"
                    else "跳过：90 秒内没有可用网络，稍后由备份任务补跑"
                currentCoroutineContext().ensureActive()
                if (app.busy.value || Runner.mutex.isLocked) return@withTimeoutOrNull "跳过：正在执行"
                // Recheck the current settings/date in the atomic update, including a midnight during network wait.
                if (!claimAutoRun(app.store, todayDate(System.currentTimeMillis()), source))
                    return@withTimeoutOrNull autoRunSkipSummary(app.store.read().settings, source)
                if (!app.execute(failedOnly = isRetry, discoverNewTargets = !isRetry) { records = it })
                    return@withTimeoutOrNull "跳过：正在执行"
                val added = records.orEmpty()
                val completed = added.count { Rules.completed(it.status) }
                "完成 $completed 项，待处理 ${added.size - completed} 项"
            } ?: "失败：执行超时"
            return summary
        } catch (e: CancellationException) {
            summary = "失败：${e.javaClass.simpleName}"
            throw e
        } catch (e: Exception) {
            summary = "失败：${e.javaClass.simpleName}"
            return summary
        } finally {
            try {
                withContext(NonCancellable) {
                    val loggedSummary = attempt?.let { "第 $it/$MAX_AUTO_RETRIES 次重试：$summary" } ?: summary
                    recordAndSchedule(app, source, loggedSummary)
                    if (records != null || attempt != null) scheduleNextRetry(app, source, records)
                    if (needsHeyboxReminder(source, records.orEmpty())) {
                        runCatching { RunNotifications.showHeyboxReminder(app) }.onFailure {
                            runCatching { app.scheduler.record(source, "通知失败：${it.javaClass.simpleName}") }
                        }
                    }
                    if (owned) {
                        runCatching { app.refresh() }
                    }
                }
            } finally { if (owned) running.set(false) }
        }
    }

    internal fun recordAndSchedule(app: CheckInApp, source: String, summary: String) {
        runCatching { app.scheduler.record(source, summary) }
        if (source == RETRY_SOURCE) return
        // This also schedules tomorrow after failure/cancellation; no Runner mutex is acquired.
        runCatching { app.scheduler.apply(app.store.read().settings) }.onFailure {
            runCatching { app.scheduler.record(source, "安排失败：${it.javaClass.simpleName}") }
        }
    }

    private fun scheduleNextRetry(app: CheckInApp, source: String, records: List<Record>?) {
        runCatching {
            val items = if (records != null) newRetryableItemCount(source, records) else {
                // A delivered retry can skip before execute() when the network is down or another run is active.
                val state = app.store.read()
                state.accounts.sumOf { account -> account.targets.count { target ->
                    target.bound && target.id in account.enabled && Rules.retryable(Rules.latest(state, account, target)?.status)
                } }
            }
            if (items > 0) app.store.withSettings { app.scheduler.scheduleRetry(it, items) }
        }.onFailure {
            runCatching { app.scheduler.record(RETRY_SOURCE, "安排失败：${it.javaClass.simpleName}") }
        }
    }

    internal fun hasValidatedNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private suspend fun awaitNetwork(context: Context): Boolean = withTimeoutOrNull(90_000L) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun validated(capabilities: NetworkCapabilities?) = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (validated(manager.getNetworkCapabilities(manager.activeNetwork))) return@withTimeoutOrNull true
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (validated(capabilities)) trySend(Unit)
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            try {
                // Register first, then recheck to avoid losing a network transition between the two.
                if (validated(manager.getNetworkCapabilities(manager.activeNetwork))) trySend(Unit)
                awaitClose { }
            } finally { manager.unregisterNetworkCallback(callback) }
        }.first()
        true
    } ?: false
}
