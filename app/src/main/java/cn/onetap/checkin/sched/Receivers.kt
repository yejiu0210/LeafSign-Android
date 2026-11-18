package cn.onetap.checkin.sched

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.onetap.checkin.CheckInApp
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CheckInApp
        val source = ExternalTrigger.source(context, intent)
        try { app.scheduler.start(source) }
        catch (e: Exception) { AutoRun.recordAndSchedule(app, source, "失败：${e.javaClass.simpleName}") }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val app = context.applicationContext as CheckInApp
        val pending = goAsync()
        app.scope.launch {
            try {
                if (app.storageError.isNotEmpty()) {
                    app.scheduler.record("catchup", "失败：本地加密数据无法读取")
                } else {
                    app.scheduler.apply(app.store.read().settings)
                    app.scheduler.catchUpIfDue(app, fromBoot = intent.action == Intent.ACTION_BOOT_COMPLETED)
                    runCatching { app.store.withSettings(app.scheduler::restoreRetryAlarm) }.onFailure {
                        app.scheduler.record(RETRY_SOURCE, "安排失败：${it.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                app.scheduler.record("catchup", "失败：${e.javaClass.simpleName}")
            } finally { pending.finish() }
        }
    }
}
