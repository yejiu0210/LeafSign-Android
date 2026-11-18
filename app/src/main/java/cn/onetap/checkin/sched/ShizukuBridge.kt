package cn.onetap.checkin.sched

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import cn.onetap.checkin.BuildConfig
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference

object ShizukuBridge {
    private val mutableState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    private val mutableDaemonActive = MutableStateFlow(false)
    val state: StateFlow<ShizukuState> = mutableState.asStateFlow()
    val daemonActive: StateFlow<Boolean> = mutableDaemonActive.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val requested = AtomicReference<Settings?>(null)
    private val binding = AtomicReference<DaemonConnection?>(null)
    @Volatile private var context: Context? = null
    private const val PERMISSION_REQUEST = 4103

    private val receivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        mutableState.value = ShizukuState.NOT_RUNNING
        mutableDaemonActive.value = false
        binding.getAndSet(null)?.ready?.completeExceptionally(IllegalStateException("Shizuku 未就绪"))
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST) refresh()
    }

    @Synchronized fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        ShizukuHook.onApply = { settings ->
            // This hook may be called while the UI owns Runner.mutex. Never wait or read Store here.
            requested.set(settings)
            refresh()
        }
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        if (context == null) return
        scope.launch {
            try {
                operations.withLock {
                    mutableState.value = detectState()
                    if (requested.get() == null) {
                        val app = context as? CheckInApp
                        if (app != null && app.storageError.isEmpty()) requested.compareAndSet(null, app.store.read().settings)
                    }
                    reconcile()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableDaemonActive.value = false; recordFailure(e) }
        }
    }

    fun requestPermission() {
        scope.launch {
            try {
                mutableState.value = detectState()
                if (state.value == ShizukuState.NO_PERMISSION) Shizuku.requestPermission(PERMISSION_REQUEST)
            } catch (e: Exception) { recordFailure(e) }
        }
    }

    suspend fun applyKeepAlive(): List<KeepAliveResult> = withContext(Dispatchers.IO) {
        try {
            operations.withLock {
                mutableState.value = detectState()
                if (state.value != ShizukuState.READY) throw IllegalStateException("Shizuku 未就绪")
                val service = bindDaemon()
                val commands = listOf(
                    Triple(0, "电池优化白名单", 0), Triple(1, "允许后台运行", 0),
                    Triple(2, "允许后台执行", 0), Triple(3, "精确闹钟", 31),
                    Triple(4, "应用待机分组设为活跃", 28), Triple(5, "通知权限", 33),
                    Triple(6, "MIUI / HyperOS 自启动", 0))
                commands.map { (id, label, minSdk) ->
                    currentCoroutineContext().ensureActive()
                    if (Build.VERSION.SDK_INT < minSdk) KeepAliveResult(label, true, "系统版本不需要")
                    else try {
                        val reply = service.runKeepAlive(id)
                        KeepAliveResult(label, reply.startsWith("ok\n"), reply.substringAfter('\n', reply).take(500))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { KeepAliveResult(label, false, e.javaClass.simpleName) }
                }
            }
        } finally { refresh() }
    }

    private fun detectState(): ShizukuState {
        val ctx = context ?: return ShizukuState.NOT_RUNNING
        try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        } catch (_: PackageManager.NameNotFoundException) { return ShizukuState.NOT_INSTALLED }
        return try {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> ShizukuState.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
                else -> ShizukuState.READY
            }
        } catch (_: Exception) { ShizukuState.NOT_RUNNING }
    }

    private fun serviceArgs() = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuDaemonService::class.java.name))
        .daemon(true).processNameSuffix("daemon").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    /** Called only under operations; connection callbacks must not acquire that mutex. */
    private suspend fun bindDaemon(): IShizukuDaemon {
        binding.get()?.service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val connection = DaemonConnection(serviceArgs())
        binding.set(connection)
        try {
            Shizuku.bindUserService(connection.args, connection)
            return withTimeoutOrNull(15_000L) { connection.ready.await() }
                ?: throw java.util.concurrent.TimeoutException("Shizuku 服务连接超时")
        } catch (e: Exception) {
            if (binding.compareAndSet(connection, null)) {
                runCatching { Shizuku.unbindUserService(connection.args, connection, true) }
            }
            throw e
        }
    }

    private suspend fun reconcile() {
        val desired = requested.get()
        if (desired == null || !shouldRunDaemon(desired, state.value)) {
            stopDaemon()
            return
        }
        val service = bindDaemon()
        // Settings can change during the asynchronous bind. Apply the most recent request only.
        val latest = requested.get()
        if (latest == null || !shouldRunDaemon(latest, state.value)) { stopDaemon(); return }
        service.setSchedule(true, latest.hour, latest.minute)
        mutableDaemonActive.value = service.isScheduled() && state.value == ShizukuState.READY &&
            binding.get()?.service === service && requested.get() == latest
    }

    private fun stopDaemon() {
        mutableDaemonActive.value = false
        val connection = binding.getAndSet(null)
        if (connection != null) {
            try { connection.service?.setSchedule(false, 0, 0) }
            finally { Shizuku.unbindUserService(connection.args, connection, true) }
        } else if (state.value == ShizukuState.READY) {
            // A daemon may outlive the app process. Remove that old instance even if we haven't bound yet.
            Shizuku.unbindUserService(serviceArgs(), null, true)
        }
    }

    private class DaemonConnection(val args: Shizuku.UserServiceArgs) : ServiceConnection {
        val ready = CompletableDeferred<IShizukuDaemon>()
        @Volatile var service: IShizukuDaemon? = null

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (binding.get() !== this) return
            val remote = IShizukuDaemon.Stub.asInterface(binder)
            service = remote
            ready.complete(remote)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            ready.completeExceptionally(IllegalStateException("Shizuku 未就绪"))
            if (binding.compareAndSet(this, null)) {
                mutableDaemonActive.value = false
                refresh()
            }
        }

        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    }

    private fun recordFailure(e: Exception) {
        runCatching { (context as? CheckInApp)?.scheduler?.record("shizuku", "失败：${e.javaClass.simpleName}") }
    }
}
