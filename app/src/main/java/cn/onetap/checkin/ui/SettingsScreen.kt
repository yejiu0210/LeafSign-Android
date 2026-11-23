package cn.onetap.checkin.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.onetap.checkin.BuildConfig
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.*
import cn.onetap.checkin.sched.KeepAliveResult
import cn.onetap.checkin.sched.ShizukuBridge
import cn.onetap.checkin.sched.ShizukuState
import cn.onetap.checkin.theme.LocalThemePalette
import cn.onetap.checkin.theme.ThemeMode
import kotlinx.coroutines.launch

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val GITHUB_URL = "https://github.com/yejiu0210/LeafSign-Android"

private fun sourceTitle(source: String) = when (source) {
    "alarm" -> "定时闹钟"; "shizuku" -> "Shizuku 守护"; "catchup" -> "启动补跑"
    "backup" -> "备份任务"; "test" -> "测试"; "retry" -> "失败重试"; else -> source
}

private fun Context.safeStart(intent: Intent?, fallback: Intent? = null): Boolean {
    for (i in listOfNotNull(intent, fallback)) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    app: CheckInApp,
    state: AppState,
    busy: Boolean,
    clockTick: Long,
    onMutate: ((AppState) -> AppState) -> Unit,
    onRequestNotification: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    fun saveSettings(schedule: Boolean = false, change: (Settings) -> Settings) = onMutate { s ->
        val next = change(s.settings)
        if (schedule) app.schedule(next)
        s.copy(settings = next)
    }

    // 系统权限状态在回到前台（clockTick 刷新）时重新读取
    val canExact = remember(clockTick) { app.scheduler.canScheduleExact() }
    val batteryOk = remember(clockTick) { app.scheduler.isIgnoringBatteryOptimizations() }
    val notifyOk = remember(clockTick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val nextTrigger = remember(clockTick, settings) { app.scheduler.nextTriggerAt(settings) }
    val runs = remember(clockTick, busy) { app.scheduler.recentRuns() }
    val canAuto = state.records.any { Rules.completed(it.status) }

    var picking by remember { mutableStateOf(false) }
    var keepAlive by remember { mutableStateOf<List<KeepAliveResult>?>(null) }
    var applying by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("设置")

        // ───── 开源地址（小卡片，不抢眼） ─────
        Surface(
            onClick = { context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = Color(0xFFF5B83D))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("一叶签已在 GitHub 开源", style = MaterialTheme.typography.bodyMedium)
                    Hint("好用的话，去点个 Star 支持一下吧～")
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "打开 GitHub", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ───── 每日自动签到 ─────
        SectionCard("每日自动签到", icon = Icons.Rounded.DateRange,
            subtitle = if (settings.auto && nextTrigger != null) "下次：${formatTrigger(nextTrigger)}" else "已关闭") {
            SettingSwitch("开启每日自动签到", settings.auto, !busy && (canAuto || settings.auto),
                description = if (!canAuto) "先在前台完成至少一项真实签到，再启用自动签到" else null) { v ->
                if (v && !notifyOk) onRequestNotification()
                saveSettings(schedule = true) { it.copy(auto = v) }
            }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !busy) { picking = true }
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("签到时间", style = MaterialTheme.typography.bodyLarge)
                    Hint("北京时间，点击修改")
                }
                Text("%02d:%02d".format(settings.hour, settings.minute), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary, fontSize = 30.sp)
            }
            SettingSwitch("失败后自动重试", settings.autoRetry, !busy,
                description = "有失败或待确认的项时，5 分钟后只重跑这些项，每天最多 3 次") { v ->
                saveSettings(schedule = true) { it.copy(autoRetry = v) }
            }
            SettingSwitch("闹钟模式", settings.useAlarmClock, !busy,
                description = "最准时，系统休眠也会按时唤醒；状态栏会显示一个闹钟图标") { v ->
                saveSettings(schedule = true) { it.copy(useAlarmClock = v) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("准点运行检查", style = MaterialTheme.typography.labelLarge)
            CheckRow(canExact, "精确闹钟权限", if (canExact) "已允许，可在设定时间准时唤醒" else "未允许，系统可能推迟数小时执行", "去授权") {
                context.safeStart(app.scheduler.exactAlarmSettingsIntent())
            }
            CheckRow(batteryOk, "忽略电池优化", if (batteryOk) "已加入白名单" else "未加入，休眠时可能被系统拦下", "去设置") {
                context.safeStart(app.scheduler.batteryOptimizationIntent(), Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            CheckRow(notifyOk, "通知权限", if (notifyOk) "后台签到时显示进度和结果" else "未允许，后台运行时看不到进度", "允许") { onRequestNotification() }
            CheckRow(false, "厂商自启动 / 后台运行", "小米、OPPO、vivo、华为等需在系统里允许自启动并设为「无限制」；用 Shizuku 可自动处理大部分", "打开") {
                context.safeStart(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            OutlinedButton(onClick = { app.scheduler.triggerTestIn(60); onMessage("已安排 1 分钟后测试运行，可以锁屏或划掉应用等待结果通知") },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("1 分钟后测试一次")
            }
            if (runs.isNotEmpty()) {
                Text("最近触发", style = MaterialTheme.typography.labelLarge)
                runs.take(5).forEach { r ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(formatTime(r.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        Text("${sourceTitle(r.source)} · ${r.summary}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
            Hint("只补跑当天的任务")
        }

        // ───── Shizuku ─────
        ShizukuCard(settings, busy, applying, onApply = {
            applying = true
            scope.launch {
                try { keepAlive = ShizukuBridge.applyKeepAlive() }
                catch (e: Exception) { onMessage("执行失败：${e.javaClass.simpleName}") }
                finally { applying = false; app.clockTick.value = System.currentTimeMillis() }
            }
        }, onToggleDaemon = { v -> saveSettings(schedule = true) { it.copy(shizukuDaemon = v) } })

        // ───── 签到速度 ─────
        SectionCard("签到速度", icon = Icons.Rounded.Star, subtitle = "同时签到 ${settings.concurrency} 个账号") {
            var value by remember(settings.concurrency) { mutableFloatStateOf(settings.concurrency.toFloat()) }
            Slider(value = value, onValueChange = { value = it }, valueRange = 1f..8f, steps = 6, enabled = !busy,
                onValueChangeFinished = { val n = value.toInt().coerceIn(1, 8); saveSettings { it.copy(concurrency = n) } })
            Row { Hint("1（逐个）", Modifier.weight(1f)); Text("${value.toInt()}", style = MaterialTheme.typography.labelLarge); Hint("8（最快）", Modifier.weight(1f).wrapContentWidth(Alignment.End)) }
            Hint("不同账号同时签到；同一账号按顺序执行，贴吧每次签到间隔约 5 秒，避免触发风控")
        }

        // ───── 通知 ─────
        SectionCard("通知与提示", icon = Icons.Rounded.Notifications) {
            SettingSwitch("签到结果通知", settings.notifications, !busy) { v ->
                if (v) onRequestNotification()
                saveSettings { it.copy(notifications = v) }
            }
            SettingSwitch("打开 App 时提示补跑今日未完成任务", settings.offerCatchup, !busy) { v -> saveSettings { it.copy(offerCatchup = v) } }
        }

        // ───── 外观 ─────
        SectionCard("外观", icon = Icons.Rounded.ThumbUp) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(selected = settings.themeMode == mode, onClick = { saveSettings { it.copy(themeMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size), enabled = !busy) { Text(mode.title) }
                }
            }
            Hint("网页登录页的配色由官网决定")
        }

        // ───── 安全 ─────
        SectionCard("本地安全", icon = Icons.Rounded.Lock) {
            Hint("凭证经 Android Keystore 加密保存在本机，只发往对应平台的官方域名")
            Hint("版本 ${BuildConfig.VERSION_NAME} · 无服务器")
        }
    }

    if (picking) {
        val picker = rememberTimePickerState(settings.hour, settings.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("选择签到时间（北京时间）") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(picker) } },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    saveSettings(schedule = true) { it.copy(hour = picker.hour, minute = picker.minute) }
                    onMessage("已设为每天 %02d:%02d".format(picker.hour, picker.minute))
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } }
        )
    }
    keepAlive?.let { results ->
        val ok = Color(LocalThemePalette.current.success)
        AlertDialog(
            onDismissRequest = { keepAlive = null },
            title = { Text("一键保活结果") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    results.forEach { r ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(if (r.ok) "✓" else "✗", color = if (r.ok) ok else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                            Column {
                                Text(r.label, style = MaterialTheme.typography.bodyMedium)
                                if (!r.ok && r.output.isNotBlank()) Hint(r.output.take(160))
                            }
                        }
                    }
                    Hint("个别项失败通常是该系统版本不支持，可忽略。")
                }
            },
            confirmButton = { TextButton(onClick = { keepAlive = null }) { Text("好的") } }
        )
    }
}

@Composable
private fun ShizukuCard(
    settings: Settings, busy: Boolean, applying: Boolean,
    onApply: () -> Unit, onToggleDaemon: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val state by ShizukuBridge.state.collectAsStateWithLifecycle()
    val daemon by ShizukuBridge.daemonActive.collectAsStateWithLifecycle()
    val palette = LocalThemePalette.current
    val (label, color) = when (state) {
        ShizukuState.READY -> "已连接" to Color(palette.success)
        ShizukuState.NO_PERMISSION -> "未授权" to Color(palette.warning)
        ShizukuState.NOT_RUNNING -> "未运行" to Color(palette.warning)
        ShizukuState.NOT_INSTALLED -> "未安装" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard("Shizuku 增强", icon = Icons.Rounded.Build, subtitle = "解决「到点不自动启动」", trailing = { StatusPill(label, color) }) {
        Hint("借助 Shizuku 的 ADB 权限：把本应用加入电池白名单、允许后台运行和精确闹钟；并在 Shizuku 进程里常驻一个守护定时器，应用被系统杀掉后到点也能把它拉起来签到。手机重启后需要在 Shizuku 里重新启动服务（已 Root 的设备会自动启动）。")
        when (state) {
            ShizukuState.NOT_INSTALLED -> FilledTonalButton(onClick = {
                context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/download/")))
            }, modifier = Modifier.fillMaxWidth()) { Text("了解并下载 Shizuku") }
            ShizukuState.NOT_RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { context.safeStart(context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)) },
                    modifier = Modifier.weight(1f)) { Text("打开 Shizuku") }
                OutlinedButton(onClick = { ShizukuBridge.refresh() }, modifier = Modifier.weight(1f)) { Text("重新检测") }
            }
            ShizukuState.NO_PERMISSION -> Button(onClick = { ShizukuBridge.requestPermission() }, modifier = Modifier.fillMaxWidth()) { Text("授权本应用使用 Shizuku") }
            ShizukuState.READY -> {
                Button(onClick = onApply, enabled = !applying, modifier = Modifier.fillMaxWidth()) {
                    if (applying) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (applying) "正在设置…" else "一键保活")
                }
                SettingSwitch("Shizuku 守护唤醒", settings.shizukuDaemon, !busy && settings.auto,
                    description = when {
                        !settings.auto -> "先开启每日自动签到"
                        settings.shizukuDaemon && daemon -> "守护运行中，到点会自动拉起签到"
                        settings.shizukuDaemon -> "正在连接守护进程…"
                        else -> "应用被杀后仍能按时启动"
                    }, onChange = onToggleDaemon)
            }
        }
    }
}
