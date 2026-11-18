package cn.onetap.checkin.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.*
import cn.onetap.checkin.login.LoginResultBroker
import cn.onetap.checkin.login.WebLoginActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@Composable
internal fun AccountDialog(app: CheckInApp, previous: Account?, onClose: () -> Unit, onError: (String) -> Unit) {
    var platform by rememberSaveable { mutableStateOf(previous?.platform ?: Platform.NETEASE) }
    var note by rememberSaveable { mutableStateOf(previous?.note ?: "") }
    var credential by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    /** 新增成功后的提示；非空时对话框显示“继续添加”页。 */
    var added by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun saveCredential(raw: String) {
        if (raw.isBlank()) { onError("请先完成官方登录，或在本机输入有效凭证"); return }
        saving = true
        val chosenPlatform = platform
        val chosenNote = note
        app.scope.launch {
            Runner.mutex.withLock {
                try {
                    val identity = app.adapters.getValue(chosenPlatform).validate(raw)
                    app.store.update { Rules.addOrUpdate(it, identity, chosenPlatform, raw, chosenNote, previous?.id) }
                    app.refresh(); credential = ""
                    if (previous == null) added = "${chosenPlatform.title} · ${chosenNote.ifBlank { identity.displayName }}（${identity.displayName} · ID ${identity.userId}）"
                    else onClose()
                } catch (e: Exception) { onError(validationError(e, raw)) } finally { saving = false }
            }
        }
    }
    if (qr) QrLoginDialog(platform, onCredential = { qr = false; saveCredential(it) }, onDismiss = { qr = false })
    added?.let { summary ->
        AlertDialog(
            onDismissRequest = onClose,
            icon = { Icon(Icons.Rounded.CheckCircle, null) },
            title = { Text("已添加账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Hint("还有分身或其他账号？点「继续添加」，用分身 App 扫码即可")
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
            dismissButton = { TextButton(onClick = { added = null; note = ""; credential = ""; manual = false }) { Text("继续添加") } }
        )
        return
    }
    val officialLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loggingIn = false
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(WebLoginActivity.EXTRA_TICKET)?.let { LoginResultBroker.take(it, platform) }
            if (raw == null) onError("登录结果已过期或进程中断，请重新进行官方网页登录") else saveCredential(raw)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) { credential = ""; onClose() } },
        title = { Text(if (previous == null) "添加账号" else "更新账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (previous == null) {
                    Text("选择平台", style = MaterialTheme.typography.labelLarge)
                    Platform.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { p -> PlatformChoice(p, platform == p, !saving, Modifier.weight(1f)) { if (platform != p) { platform = p; credential = "" } } }
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(previous.platform, 36.dp); Spacer(Modifier.width(10.dp))
                    Text("${previous.platform.title} · ${previous.displayName}", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(note, { note = it.take(40) }, label = { Text("备注，如主账号 / 分身账号") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { qr = true }, enabled = !saving && !loggingIn, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("用官方 App 扫码登录（推荐）")
                }
                Hint("跳转到已登录的${officialApps(platform).first().name} App 确认即可，支持分身 App。")
                OutlinedButton(onClick = {
                    if (!loggingIn) {
                        loggingIn = true
                        try { officialLogin.launch(Intent(context, WebLoginActivity::class.java).putExtra(WebLoginActivity.EXTRA_PLATFORM, platform.name)) }
                        catch (_: Exception) { loggingIn = false; onError("未能打开登录页，请重新打开应用后再试") }
                    }
                }, enabled = !saving && !loggingIn, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loggingIn) "官方登录处理中…" else "官方网页登录并自动导入")
                }
                Hint("扫码不方便时，可以在网页里登录，完成后自动保存")
                if (previous != null) Hint("请登录同一个账号（${previous.displayName}），其他账号请另外添加")
                TextButton(onClick = { manual = !manual }, contentPadding = PaddingValues(0.dp)) { Text("手动导入凭证（备用）") }
                if (manual) {
                    Hint(credentialHint(platform))
                    OutlinedTextField(credential, { credential = it }, label = { Text(if (previous != null) "新凭证（留空保留）" else "本地输入凭证") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        enabled = !saving, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Hint("凭证只保存在本机，不要发给别人")
                    TextButton(onClick = { help = !help }, contentPadding = PaddingValues(0.dp)) { Text("如何获取 / 更新凭证") }
                }
                if (help) {
                    Hint(credentialHelp(platform))
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(officialUrl(platform)))) }, contentPadding = PaddingValues(0.dp)) { Text("打开官方入口") }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { saveCredential(credential.trim().ifBlank { previous?.credential ?: "" }) }, enabled = !saving && (previous != null || manual)) {
                Text(if (previous != null) "保存备注 / 手动凭证" else "校验手动凭证并保存")
            }
        },
        dismissButton = { TextButton(onClick = { credential = ""; onClose() }, enabled = !saving) { Text("取消") } }
    )
}

@Composable
private fun PlatformChoice(p: Platform, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = platformColor(p)
    OutlinedCard(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) color.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlatformBadge(p, 28.dp); Spacer(Modifier.width(8.dp))
            Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun validationError(e: Exception, credential: String) = when (e) {
    is AdapterException -> "${e.status.title}：${Rules.safeMessage(e.message, credential)}"
    is IllegalArgumentException -> Rules.safeMessage(e.message ?: "凭证格式错误", credential)
    is java.io.IOException -> "连接平台失败，请检查网络后重试"
    else -> "未能可靠校验身份，请检查凭证格式与接入说明（${e.javaClass.simpleName}）"
}

private fun credentialHint(p: Platform) = when (p) {
    Platform.NETEASE -> "MUSIC_U 值，或含 MUSIC_U 的网易云 Cookie"
    Platform.TIEBA -> "BDUSS 值，或含 BDUSS 的百度 Cookie"
    Platform.SKLAND -> "鹰角通行证 token，或官方凭证 JSON"
    Platform.BILIBILI -> "含 SESSDATA 和 bili_jct 的 B 站 Cookie"
}

private fun officialUrl(p: Platform) = when (p) {
    Platform.NETEASE -> "https://music.163.com/"
    Platform.TIEBA -> "https://tieba.baidu.com/"
    Platform.SKLAND -> "https://user.hypergryph.com/login"
    Platform.BILIBILI -> "https://big.bilibili.com/mobile/index?location=drawExperienceModule"
}

private fun credentialHelp(p: Platform) = when (p) {
    Platform.NETEASE -> "在电脑浏览器登录网易云网页版，打开开发者工具 → 存储 → Cookies → music.163.com，复制 MUSIC_U。失效后重新登录获取。"
    Platform.TIEBA -> "在电脑浏览器登录贴吧网页版，打开开发者工具 → 存储 → Cookies，复制 BDUSS（有 STOKEN 可以一起填）。失效后重新登录获取。"
    Platform.SKLAND -> "推荐用上方的扫码或网页登录。已有鹰角通行证 token 或 {\"cred\":\"…\"} 的话，也可以在这里填入。"
    Platform.BILIBILI -> "在电脑浏览器登录 B 站网页版，打开开发者工具 → 存储 → Cookies，复制 SESSDATA 和 bili_jct（DedeUserID 可选）。等级加速包需要大会员，并且当天在官方看过 1 分钟视频。"
}
