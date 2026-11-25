package cn.onetap.checkin.sched

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.onetap.checkin.MainActivity
import cn.onetap.checkin.R
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.Record
import cn.onetap.checkin.core.RunProgress
import cn.onetap.checkin.core.TaskStatus
import cn.onetap.checkin.platform.HeyboxAdapter

private val heyboxReminderSources = setOf("alarm", "backup", "shizuku", "catchup", RETRY_SOURCE)

internal fun needsHeyboxReminder(source: String, newRecords: List<Record>): Boolean =
    source in heyboxReminderSources && newRecords.any {
        it.platform == Platform.HEYBOX && it.targetId == HeyboxAdapter.TARGET_ID &&
            it.status == TaskStatus.MANUAL && it.diagnostic == HeyboxAdapter.OPEN_APP_DIAGNOSTIC
    }

internal object RunNotifications {
    const val SERVICE_ID = 210
    const val RESULT_ID = 211
    private const val HEYBOX_REMINDER_ID = 212
    private const val CHANNEL = "running"
    private const val RESULT_CHANNEL = "auto_result"

    fun running(context: Context, progress: RunProgress? = null): Notification = builder(context, CHANNEL)
        .setContentTitle(if (progress == null) "后台签到" else "正在签到 ${progress.finished}/${progress.total}")
        .setContentText(progress?.running?.firstOrNull() ?: progress?.last?.takeIf { it.isNotBlank() } ?: "正在准备，等待可用网络")
        .setProgress(progress?.total ?: 0, progress?.finished ?: 0, progress == null)
        .setOngoing(true).build()

    private fun result(context: Context, summary: String): Notification = builder(context, RESULT_CHANNEL)
        .setContentTitle("后台签到结果").setContentText(summary).setStyle(NotificationCompat.BigTextStyle().bigText(summary))
        .setCategory(NotificationCompat.CATEGORY_STATUS).setOngoing(false).setAutoCancel(true).build()

    /** One automatic-result ID, shared by Service and WorkManager; claim the failure day atomically. */
    @Synchronized fun showResult(app: CheckInApp, summary: String) {
        if (summary.startsWith("跳过：")) return
        val prefs = app.getSharedPreferences("sched_notifications", Context.MODE_PRIVATE)
        val today = todayDate(System.currentTimeMillis())
        val decision = notificationDecision(summary, notificationsEnabled(app), prefs.getString("failure_date", "") == today)
        if (decision == AutoNotification.NONE) return
        if (!app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        val notification = result(app, summary)
        // Persist before posting so a process restart cannot send a second failure reminder today.
        if (decision == AutoNotification.FAILURE_ONCE && !prefs.edit().putString("failure_date", today).commit()) return
        show(app, RESULT_ID, notification)
    }

    fun showHeyboxReminder(app: CheckInApp) {
        if (!notificationsEnabled(app) || !app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        val notification = builder(app, RESULT_CHANNEL)
            .setContentTitle("小黑盒还没签到")
            .setContentText("点一下打开小黑盒，打开就会自动签到")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
        app.packageManager.getLaunchIntentForPackage(HeyboxAdapter.APP_PACKAGE)?.let { launch ->
            notification.setContentIntent(PendingIntent.getActivity(app, HEYBOX_REMINDER_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        show(app, HEYBOX_REMINDER_ID, notification.build())
    }

    private fun notificationsEnabled(app: CheckInApp) =
        runCatching { app.store.read().settings }.getOrDefault(app.state.value.settings).notifications

    fun show(context: Context, id: Int, notification: Notification) {
        try { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
        catch (_: SecurityException) { /* Notification permission is separate from permission to run an FGS. */ }
    }

    private fun builder(context: Context, channel: String): NotificationCompat.Builder {
        val name = if (channel == CHANNEL) "后台签到" else "自动签到结果"
        val importance = if (channel == CHANNEL) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, name, importance).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(context, 4100, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }
}
