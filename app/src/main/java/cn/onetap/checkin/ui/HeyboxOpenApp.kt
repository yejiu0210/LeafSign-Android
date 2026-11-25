package cn.onetap.checkin.ui

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.platform.HeyboxAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 小黑盒没法用接口签到，只能靠打开小黑盒 App（打开时它会自动签到）。
 * 这个按钮负责跳过去；用户切回一叶签时，等一会儿再单独复查这一项。
 */
@Composable
internal fun HeyboxOpenAppButton(accountId: String, targetId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as CheckInApp
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // 跳出去之前置为 true；进程被系统回收后也能记住，回来时照样复查
    var waitingReturn by rememberSaveable(accountId) { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val launch = remember { context.packageManager.getLaunchIntentForPackage(HeyboxAdapter.APP_PACKAGE) }

    DisposableEffect(lifecycleOwner, waitingReturn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingReturn) {
                waitingReturn = false
                checking = true
                scope.launch {
                    try {
                        // 小黑盒打开后要几秒才签完，稍等再查
                        delay(2_000)
                        app.recheck(accountId, targetId)
                    } finally {
                        checking = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (launch == null) {
        Hint("没有找到小黑盒 App，装好后打开一次就会自动签到")
        return
    }
    FilledTonalButton(
        onClick = {
            waitingReturn = true
            try { context.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { waitingReturn = false }
        },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  正在确认…")
        } else Text("打开小黑盒签到")
    }
}
