package cn.onetap.checkin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import cn.onetap.checkin.core.*
import cn.onetap.checkin.platform.*
import cn.onetap.checkin.sched.Scheduler
import cn.onetap.checkin.sched.ShizukuBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class CheckInApp : Application() {
    lateinit var store: Store
    lateinit var runner: Runner
    lateinit var scheduler: Scheduler
    val adapters: Map<Platform,PlatformAdapter> by lazy {
        listOf(NeteaseAdapter(), TiebaAdapter(store), SklandAdapter(), BilibiliAdapter(), HeyboxAdapter()).associateBy { it.platform }
    }
    val state = MutableStateFlow(AppState())
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val runProgress = MutableStateFlow<RunProgress?>(null)
    val clockTick = MutableStateFlow(System.currentTimeMillis())
    var storageError: String = ""
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        store=Store(this);runner=Runner(store,adapters);scheduler=Scheduler(this)
        try { runner.recover(); refresh() } catch (_: Exception) { storageError="本地加密数据无法读取。为防止覆盖，已停止操作。请保留应用数据并检查系统密钥状态。" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("results","签到结果",NotificationManager.IMPORTANCE_DEFAULT))
        ShizukuBridge.init(this)
        if (storageError.isEmpty()) {
            try { scheduler.apply(state.value.settings) }
            catch (e: Exception) { scheduler.record("catchup", "安排失败：${e.javaClass.simpleName}") }
            scope.launch {
                try { scheduler.catchUpIfDue(this@CheckInApp) }
                catch (e: Exception) { scheduler.record("catchup", "失败：${e.javaClass.simpleName}") }
            }
        }
    }
    fun refresh() { state.value=store.read() }
    suspend fun execute(failedOnly: Boolean = false): Boolean =
        execute(failedOnly, discoverNewTargets = true)

    internal suspend fun execute(
        failedOnly: Boolean,
        discoverNewTargets: Boolean,
        onNewRecords: ((List<Record>) -> Unit)? = null
    ): Boolean {
        if(storageError.isNotEmpty() || !busy.compareAndSet(false,true)) return false
        var lastRefresh: Long? = null
        var before: Set<String>? = null
        try {
            if (onNewRecords != null) before = store.read().records.mapTo(hashSetOf()) { it.id }
            runner.run(failedOnly, discoverNewTargets) {
                runProgress.value=it; progress.value=it.last
                val now=System.nanoTime()
                if(lastRefresh==null || now-lastRefresh!! >= 200_000_000L) {
                    refresh(); lastRefresh=now
                }
            }
        } finally {
            runProgress.value = null
            progress.value = ""
            try {
                refresh()
                if (before != null) onNewRecords?.invoke(state.value.records.filter { it.id !in before })
            } finally { busy.value = false }
        }
        return true
    }
    fun schedule(settings: Settings) = scheduler.apply(settings)
    fun notifyResult() {
        if(!state.value.settings.notifications)return
        val today=state.value.records.filter { r -> state.value.accounts.find { it.id==r.accountId }?.targets?.find { it.id==r.targetId }?.let { r.period==Rules.period(it) } ?: false }
            .groupBy { it.accountId+":"+it.targetId }.values.map { it.last() }
        val content="完成 ${today.count { Rules.completed(it.status) }} 项，待处理 ${today.count { !Rules.completed(it.status) }} 项。打开应用查看详情。"
        val pending=android.app.PendingIntent.getActivity(this,0,android.content.Intent(this,MainActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        try { getSystemService(NotificationManager::class.java).notify(100,NotificationCompat.Builder(this,"results").setSmallIcon(cn.onetap.checkin.R.drawable.ic_notification).setContentTitle("一叶签已执行签到").setContentText(content).setContentIntent(pending).setAutoCancel(true).build()) } catch (_: SecurityException) { /* User did not grant notification permission. */ }
    }
}
