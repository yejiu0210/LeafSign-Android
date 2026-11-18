package cn.onetap.checkin

import android.Manifest
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.onetap.checkin.core.*
import cn.onetap.checkin.sched.ShizukuBridge
import cn.onetap.checkin.theme.OneTapTheme
import cn.onetap.checkin.theme.ThemePalette
import cn.onetap.checkin.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        (application as CheckInApp).clockTick.value = System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as CheckInApp
            val state by app.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = state.settings.themeMode.isDark(systemDark)
            val colors = ThemePalette.resolve(state.settings.themeMode, systemDark)
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(colors.background))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colors.background
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colors.background
                }
            }
            OneTapTheme(state.settings.themeMode) {
                AppScreen(app) { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as CheckInApp).clockTick.value = System.currentTimeMillis()
        ShizukuBridge.refresh()
    }
}

private data class NavTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(
    NavTab("今日", Icons.Rounded.CheckCircle),
    NavTab("账号", Icons.Rounded.Person),
    NavTab("记录", Icons.AutoMirrored.Rounded.List),
    NavTab("设置", Icons.Rounded.Settings)
)

@Composable
private fun AppScreen(app: CheckInApp, onNotification: () -> Unit) {
    val state by app.state.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    val runProgress by app.runProgress.collectAsStateWithLifecycle()
    val clockTick by app.clockTick.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { delay(60_000); app.clockTick.value = System.currentTimeMillis() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = state.accounts.firstOrNull { it.id == editingId }
    var error by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Account?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    fun message(text: String) { uiScope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    fun mutate(action: (AppState) -> AppState) {
        app.scope.launch {
            Runner.mutex.withLock {
                try { app.store.update(action); app.refresh() } catch (_: Exception) { error = "保存本地数据失败，请检查设备存储和密钥状态" }
            }
        }
    }
    fun validate(a: Account) {
        inspecting = true
        app.scope.launch {
            Runner.mutex.withLock {
                try {
                    val id = app.adapters.getValue(a.platform).validate(a.credential)
                    app.store.update { Rules.addOrUpdate(it, id, a.platform, a.credential, a.note, a.id) }
                    app.refresh(); message("身份校验通过，已刷新可选角色 / 贴吧")
                } catch (e: Exception) { error = validationError(e, a.credential) } finally { inspecting = false }
            }
        }
    }

    if (app.storageError.isNotEmpty()) {
        Surface(Modifier.fillMaxSize()) { Text(app.storageError, Modifier.padding(30.dp)) }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == 1) ExtendedFloatingActionButton(
                onClick = { if (!busy && !inspecting) { editingId = null; adding = true } },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("添加账号") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                when (current) {
                    0 -> HomeScreen(
                        state, clockTick, busy, inspecting, runProgress,
                        nextTrigger = remember(state.settings, clockTick) { app.scheduler.nextTriggerAt(state.settings) },
                        onRun = { app.scope.launch { app.execute(); app.notifyResult() } },
                        onRetry = { app.scope.launch { app.execute(true) } },
                        onGoAccounts = { tab = 1 }
                    )
                    1 -> AccountsScreen(state, busy, inspecting, ::mutate, ::validate,
                        onEdit = { editingId = it.id; adding = true }, onDelete = { delete = it })
                    2 -> RecordsScreen(state)
                    else -> SettingsScreen(app, state, busy, clockTick, ::mutate, onNotification, ::message)
                }
            }
        }
    }
    if (adding) AccountDialog(app, editing, onClose = { adding = false; editingId = null }, onError = { error = it })
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("提示") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = { error = "" }) { Text("确定") } })
    delete?.let { a ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除此账号？") },
            text = { Text("删除 ${a.note.ifBlank { a.displayName }} 的本地凭证、选择、操作进度和全部历史记录。不会退出官方 App。") },
            confirmButton = { TextButton(onClick = { mutate { s -> Rules.removeAccount(s, a.id) }; delete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } })
    }
}
