package cn.onetap.checkin.sched

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import cn.onetap.checkin.CheckInApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class CheckInService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutSummary: String? = null
    private var finished = false
    private var currentSource = "alarm"
    private val app get() = application as CheckInApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = ExternalTrigger.source(this, intent)
        if (job?.isCompleted == false) {
            if (source == RETRY_SOURCE) {
                runCatching { app.scheduler.enqueueCatchUp(source) }.onFailure {
                    runCatching { app.scheduler.record(source, "转交失败：${it.javaClass.simpleName}") }
                }
                return START_NOT_STICKY
            }
            runCatching { app.scheduler.record(source, "跳过：正在执行") }
            return START_NOT_STICKY
        }
        currentSource = source
        finished = false
        // Satisfy the foreground deadline before any coroutine, storage read, or network wait.
        try {
            val notification = RunNotifications.running(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(RunNotifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(RunNotifications.SERVICE_ID, notification)
            // Always enter the foreground first, even when this duplicate start will stop immediately.
            val settings = app.store.read().settings
            if (!shouldAutoRun(settings, todayDate(System.currentTimeMillis()), source)) {
                val summary = autoRunSkipSummary(settings, source)
                app.scheduler.record(source, summary)
                finishService(summary)
                return START_NOT_STICKY
            }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:checkin")
                .apply { setReferenceCounted(false); acquire(10 * 60_000L) }
        } catch (e: Exception) {
            AutoRun.recordAndSchedule(app, source, "失败：${e.javaClass.simpleName}")
            runCatching { app.scheduler.enqueueCatchUp(source) }
            finishService("失败：${e.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        timeoutSummary = null
        job = scope.launch {
            var summary = "失败：执行中断"
            val progress = launch {
                app.runProgress.collect { if (!finished) RunNotifications.show(this@CheckInService, RunNotifications.SERVICE_ID,
                    RunNotifications.running(this@CheckInService, it)) }
            }
            try {
                summary = withContext(Dispatchers.IO) { AutoRun.run(app, source) }
            } catch (e: CancellationException) {
                summary = timeoutSummary ?: "失败：${e.javaClass.simpleName}"
                throw e
            } catch (e: Exception) {
                summary = "失败：${e.javaClass.simpleName}"
                AutoRun.recordAndSchedule(app, source, summary)
            } finally {
                withContext(NonCancellable) {
                    progress.cancelAndJoin()
                    finishService(timeoutSummary ?: summary)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finishService(summary: String) {
        // onTimeout and coroutine finally can both arrive; publish/stop only once.
        if (finished) return
        finished = true
        try {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            RunNotifications.showResult(app, summary)
        } catch (e: Exception) {
            runCatching { app.scheduler.record(currentSource, "通知失败：${e.javaClass.simpleName}") }
        } finally {
            try {
                releaseWakeLock()
            } finally { stopSelf() }
        }
    }

    private fun releaseWakeLock() {
        val held = wakeLock
        wakeLock = null
        // The timed lock may have expired between isHeld and release.
        runCatching { if (held?.isHeld == true) held.release() }
    }

    // Android 15 dataSync time budget can expire even before our own nine-minute deadline.
    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutSummary = "失败：前台服务超时"
        job?.cancel()
        finishService(timeoutSummary!!)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
