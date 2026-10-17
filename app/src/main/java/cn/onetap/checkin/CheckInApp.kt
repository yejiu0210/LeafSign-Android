package cn.onetap.checkin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import cn.onetap.checkin.core.*
import cn.onetap.checkin.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class CheckInApp : Application() {
    lateinit var store: Store
    lateinit var runner: Runner
    val adapters: Map<Platform,PlatformAdapter> by lazy { listOf(NeteaseAdapter(),TiebaAdapter(store),SklandAdapter(),BilibiliAdapter(),HeyboxAdapter(store)).associateBy { it.platform } }
    val state = MutableStateFlow(AppState())
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val clockTick = MutableStateFlow(System.currentTimeMillis())
    var storageError: String = ""
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        store=Store(this);runner=Runner(store,adapters)
        try { runner.recover(); refresh() } catch (_: Exception) { storageError="本地加密数据无法读取。为防止覆盖，已停止操作。请保留应用数据并检查系统密钥状态。" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("results","签到结果",NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun refresh() { state.value=store.read() }
    suspend fun execute(failedOnly: Boolean = false): Boolean {
        if(storageError.isNotEmpty() || !busy.compareAndSet(false,true)) return false
        try { runner.run(failedOnly) { progress.value=it; refresh() } }
        finally { busy.value=false;progress.value="";refresh() }
        return true
    }
    fun schedule(settings: Settings) {
        val wm=WorkManager.getInstance(this)
        if(!settings.auto) { wm.cancelUniqueWork("daily-checkin");return }
        val now=ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        var next=now.withHour(settings.hour).withMinute(settings.minute).withSecond(0).withNano(0)
        if(!next.isAfter(now))next=next.plusDays(1)
        val request=PeriodicWorkRequestBuilder<DailyWorker>(24,TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now,next).toMillis(),TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("daily-checkin",ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,request)
    }
    fun notifyResult() {
        if(!state.value.settings.notifications)return
        val today=state.value.records.filter { r -> state.value.accounts.find { it.id==r.accountId }?.targets?.find { it.id==r.targetId }?.let { r.period==Rules.period(it) } ?: false }
            .groupBy { it.accountId+":"+it.targetId }.values.map { it.last() }
        val content="完成 ${today.count { Rules.completed(it.status) }} 项，待处理 ${today.count { !Rules.completed(it.status) }} 项。打开应用查看详情。"
        val pending=android.app.PendingIntent.getActivity(this,0,android.content.Intent(this,MainActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        try { getSystemService(NotificationManager::class.java).notify(100,NotificationCompat.Builder(this,"results").setSmallIcon(cn.onetap.checkin.R.drawable.ic_launcher).setContentTitle("一键签到已执行").setContentText(content).setContentIntent(pending).setAutoCancel(true).build()) } catch (_: SecurityException) { /* User did not grant notification permission. */ }
    }
}

class DailyWorker(context: Context,params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as CheckInApp
        if(app.storageError.isNotEmpty())return Result.failure()
        if(!app.store.read().settings.auto)return Result.success()
        if(app.busy.value || Runner.mutex.isLocked)return Result.retry()
        return try { val ran=withTimeout(8*60*1000L) { app.execute() };if(ran){app.notifyResult();Result.success()}else Result.retry() }
            catch(e: CancellationException){throw e}
            catch(_: Exception){Result.failure()}
    }
}
