package cn.onetap.checkin.sched

import cn.onetap.checkin.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Created reflectively by Shizuku in the shell process; deliberately uses no application Context. */
class ShizukuDaemonService() : IShizukuDaemon.Stub() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private val schedule = DaemonSchedule()
    private var poll: ScheduledFuture<*>? = null
    private val pkg = BuildConfig.APPLICATION_ID

    override fun runKeepAlive(commandId: Int): String {
        val command = keepAliveCommand(commandId, pkg) ?: return "fail\n未知命令 ID"
        return try { runDaemonCommand(command).reply() }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); "fail\nInterruptedException" }
        catch (e: Exception) { "fail\n${e.javaClass.simpleName}" }
    }

    override fun setSchedule(enabled: Boolean, hour: Int, minute: Int) = synchronized(lock) {
        schedule.configure(enabled, hour, minute, System.currentTimeMillis())
        if (!enabled) {
            poll?.cancel(false)
            poll = null
        } else if (poll == null || poll!!.isDone) {
            poll = executor.scheduleWithFixedDelay({ checkSchedule() }, 0, 60, TimeUnit.SECONDS)
        }
    }

    override fun isScheduled(): Boolean = synchronized(lock) {
        schedule.enabled && poll?.let { !it.isCancelled && !it.isDone } == true && !executor.isShutdown
    }

    private fun checkSchedule() {
        val revision = synchronized(lock) {
            if (!schedule.takeDue(System.currentTimeMillis())) return
            schedule.revision
        }
        try {
            val service = runDaemonCommand(listOf("/system/bin/am", "start-foreground-service", "-n",
                "$pkg/cn.onetap.checkin.sched.CheckInService", "--es", "source", "shizuku"))
            if (!service.ok && !Thread.currentThread().isInterrupted && synchronized(lock) { schedule.enabled }) {
                // 前台优先投递（FLAG_RECEIVER_FOREGROUND）｜包含后台接收器（FLAG_RECEIVER_INCLUDE_BACKGROUND）｜包含已强行停止的应用（FLAG_INCLUDE_STOPPED_PACKAGES）。
                runDaemonCommand(listOf("/system/bin/am", "broadcast", "-n",
                    "$pkg/cn.onetap.checkin.sched.AlarmReceiver", "-f", "0x11000020", "--es", "source", "shizuku"))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) { /* Keep the next day's poll alive if a shell command fails. */ }
        finally { synchronized(lock) { schedule.afterAttempt(System.currentTimeMillis(), revision) } }
    }

    override fun destroy() {
        setSchedule(false, 0, 0)
        executor.shutdownNow()
        System.exit(0)
    }
}
