package cn.onetap.checkin.sched

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import cn.onetap.checkin.CheckInApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

abstract class AutoRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val app get() = applicationContext as CheckInApp
    private val notificationId = 10_000 + (id.hashCode() and 0xffff)

    override suspend fun getForegroundInfo() = ForegroundInfo(notificationId, RunNotifications.running(applicationContext),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    protected suspend fun runInForeground(source: String): Result = coroutineScope {
        try {
            val settings = app.store.read().settings
            if (!shouldAutoRun(settings, todayDate(System.currentTimeMillis()), source)) {
                app.scheduler.record(source, autoRunSkipSummary(settings, source))
                return@coroutineScope Result.success()
            }
            setForeground(getForegroundInfo())
        }
        catch (e: CancellationException) {
            withContext(NonCancellable) {
                AutoRun.recordAndSchedule(app, source, "失败：${e.javaClass.simpleName}")
                showResult(source, "失败：${e.javaClass.simpleName}")
            }
            throw e
        }
        catch (e: Exception) {
            AutoRun.recordAndSchedule(app, source, "失败：${e.javaClass.simpleName}")
            showResult(source, "失败：${e.javaClass.simpleName}")
            return@coroutineScope Result.retry()
        }
        var summary = "失败：执行中断"
        val progress = launch {
            app.runProgress.collect { RunNotifications.show(applicationContext, notificationId, RunNotifications.running(applicationContext, it)) }
        }
        try {
            summary = withContext(Dispatchers.IO) { AutoRun.run(app, source) }
            if (summary.startsWith("失败：")) Result.failure() else Result.success()
        } catch (e: CancellationException) {
            summary = "失败：${e.javaClass.simpleName}"
            throw e
        } finally {
            withContext(NonCancellable) {
                progress.cancelAndJoin()
                // WorkManager removes its own foreground ID; all allowed results share RESULT_ID with the Service.
                showResult(source, summary)
            }
        }
    }

    protected fun showResult(source: String, summary: String) {
        runCatching { RunNotifications.showResult(app, summary) }.onFailure {
            runCatching { app.scheduler.record(source, "通知失败：${it.javaClass.simpleName}") }
        }
    }
}

class CatchUpWorker(context: Context, params: WorkerParameters) : AutoRunWorker(context, params) {
    override suspend fun doWork(): Result = runInForeground(inputData.getString(Scheduler.EXTRA_SOURCE) ?: "catchup")
}

class BackupCheckWorker(context: Context, params: WorkerParameters) : AutoRunWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            if (!shouldCatchUp(app.store.read().settings, System.currentTimeMillis())) return Result.success()
            if (!AutoRun.hasValidatedNetwork(applicationContext)) {
                SchedRunLog.noNetworkOnce(applicationContext)
                return Result.success()
            }
        } catch (e: Exception) {
            AutoRun.recordAndSchedule(app, "backup", "失败：${e.javaClass.simpleName}")
            showResult("backup", "失败：${e.javaClass.simpleName}")
            return Result.failure()
        }
        return runInForeground("backup")
    }
}
