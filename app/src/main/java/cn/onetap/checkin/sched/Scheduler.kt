package cn.onetap.checkin.sched

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.work.*
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.MainActivity
import cn.onetap.checkin.core.Settings
import java.util.concurrent.TimeUnit

internal object ShizukuHook {
    @Volatile var onApply: ((Settings) -> Unit)? = null
}

class Scheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val work get() = WorkManager.getInstance(context)
    private val retryPrefs get() = context.getSharedPreferences("sched_retry", Context.MODE_PRIVATE)

    // No Store access, Runner mutex acquisition, or waiting for WorkManager operations.
    fun apply(settings: Settings) {
        try {
            if (!canAutoRetry(settings)) {
                runCatching { cancelRetryAlarm() }.onFailure {
                    runCatching { record(RETRY_SOURCE, "取消失败：${it.javaClass.simpleName}") }
                }
            }
            work.cancelUniqueWork("daily-checkin")
            if (settings.auto) {
                work.enqueueUniquePeriodicWork(BACKUP_WORK, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<BackupCheckWorker>(1, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
                scheduleAlarm(nextTriggerAt(settings)!!, "alarm", settings.useAlarmClock)
            } else {
                alarms.cancel(alarmIntent("alarm"))
                work.cancelUniqueWork(BACKUP_WORK)
                for (source in listOf("alarm", "catchup", "backup", "shizuku")) work.cancelUniqueWork("$CATCHUP_WORK-$source")
            }
        } finally {
            ShizukuHook.onApply?.invoke(settings)
        }
    }

    fun nextTriggerAt(settings: Settings): Long? = if (settings.auto)
        nextTrigger(System.currentTimeMillis(), settings.hour, settings.minute) else null

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun exactAlarmSettingsIntent(): Intent? = if (Build.VERSION.SDK_INT < 31) null else
        Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isIgnoringBatteryOptimizations(): Boolean = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun batteryOptimizationIntent(): Intent =
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun triggerTestIn(seconds: Int) {
        require(seconds > 0) { "测试延迟必须大于零" }
        scheduleAlarm(System.currentTimeMillis() + seconds.toLong() * 1000, "test", false)
    }

    fun recentRuns(): List<SchedRun> = SchedRunLog.recent(context)

    internal fun record(source: String, summary: String) = SchedRunLog.add(context, source, summary)

    @Synchronized internal fun scheduleRetry(settings: Settings, items: Int) {
        val now = System.currentTimeMillis()
        if (!canAutoRetry(settings) || items == 0 || retryAt() > now) return
        val triggerAt = now + RETRY_DELAY_MILLIS
        val attempt = retryBudget().next(triggerAt)?.attempts ?: return
        check(retryPrefs.edit().putLong(RETRY_AT, triggerAt).commit())
        try {
            scheduleAlarm(triggerAt, RETRY_SOURCE, settings.useAlarmClock)
        } catch (e: Exception) {
            retryPrefs.edit().remove(RETRY_AT).commit()
            throw e
        }
        record(RETRY_SOURCE, "5 分钟后重试 ${items} 项（第 $attempt/$MAX_AUTO_RETRIES 次）")
    }

    @Synchronized internal fun restoreRetryAlarm(settings: Settings) {
        val at = retryAt()
        if (!canAutoRetry(settings) || at == 0L) return
        scheduleAlarm(at, RETRY_SOURCE, settings.useAlarmClock)
        record(RETRY_SOURCE, "已恢复失败重试闹钟")
    }

    @Synchronized internal fun claimRetry(settings: Settings, now: Long): Int? {
        if (!canAutoRetry(settings) || retryAt() !in 1L..now) return null
        val next = retryBudget().next(now)
        val edit = retryPrefs.edit().remove(RETRY_AT)
        if (next != null) edit.putString(RETRY_DATE, next.date).putInt(RETRY_ATTEMPTS, next.attempts)
        check(edit.commit())
        alarms.cancel(alarmIntent(RETRY_SOURCE))
        return next?.attempts
    }

    @Synchronized private fun cancelRetryAlarm() {
        alarms.cancel(alarmIntent(RETRY_SOURCE))
        if (retryAt() != 0L) check(retryPrefs.edit().remove(RETRY_AT).commit())
    }

    private fun retryAt() = retryPrefs.getLong(RETRY_AT, 0)
    private fun retryBudget() = RetryBudget(retryPrefs.getString(RETRY_DATE, "").orEmpty(), retryPrefs.getInt(RETRY_ATTEMPTS, 0))

    internal fun catchUpIfDue(app: CheckInApp, fromBoot: Boolean = false) {
        if (app.storageError.isNotEmpty()) return
        if (shouldCatchUp(app.store.read().settings, System.currentTimeMillis())) {
            // Android 15 forbids BOOT_COMPLETED -> dataSync FGS, despite the general boot exemption.
            if (fromBoot && Build.VERSION.SDK_INT >= 35) enqueueCatchUp("catchup") else start("catchup")
        }
    }

    internal fun start(source: String) {
        try {
            ContextCompat.startForegroundService(context, ExternalTrigger.internalIntent(context, Intent(context, CheckInService::class.java), source))
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException on API 31+.
            record(source, "转交后台任务：${e.javaClass.simpleName}")
            enqueueCatchUp(source)
        } catch (e: SecurityException) {
            record(source, "转交后台任务：${e.javaClass.simpleName}")
            enqueueCatchUp(source)
        }
    }

    internal fun enqueueCatchUp(source: String) {
        work.enqueueUniqueWork("$CATCHUP_WORK-$source", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CatchUpWorker>().setInputData(workDataOf(EXTRA_SOURCE to source))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }

    private fun scheduleAlarm(at: Long, source: String, alarmClock: Boolean) {
        val pending = alarmIntent(source)
        if (canScheduleExact()) {
            try {
                if (alarmClock) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), pending)
                else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) { /* Exact-alarm access can be revoked between the check and call. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun showIntent() = PendingIntent.getActivity(context, 4100, Intent(context, MainActivity::class.java), FLAGS)

    private fun alarmIntent(source: String): PendingIntent {
        val requestCode = when (source) {
            "test" -> 4102
            RETRY_SOURCE -> 4103
            else -> 4101
        }
        return PendingIntent.getBroadcast(context, requestCode,
            ExternalTrigger.internalIntent(context, Intent(context, AlarmReceiver::class.java)
                .setAction("${context.packageName}.CHECK_IN.$source"), source), FLAGS)
    }

    internal companion object {
        const val EXTRA_SOURCE = "source"
        private const val BACKUP_WORK = "checkin-hourly-backup"
        private const val CATCHUP_WORK = "checkin-catchup"
        private const val RETRY_AT = "at"
        private const val RETRY_DATE = "date"
        private const val RETRY_ATTEMPTS = "attempts"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
