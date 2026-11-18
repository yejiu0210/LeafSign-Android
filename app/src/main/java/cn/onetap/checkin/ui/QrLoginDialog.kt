package cn.onetap.checkin.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.login.qr.QrLogin
import cn.onetap.checkin.login.qr.QrLoginException
import cn.onetap.checkin.login.qr.QrPoll
import cn.onetap.checkin.login.qr.QrTicket
import cn.onetap.checkin.theme.LocalThemePalette
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 能确认官方登录二维码的 App（含常见的同公司 App）。系统双开的分身与原版同包名，拉起时由系统弹出选择。 */
internal data class OfficialApp(val pkg: String, val name: String)

internal fun officialApps(p: Platform): List<OfficialApp> = when (p) {
    Platform.BILIBILI -> listOf(OfficialApp("tv.danmaku.bili", "哔哩哔哩"), OfficialApp("com.bilibili.app.blue", "哔哩哔哩概念版"),
        OfficialApp("tv.danmaku.bilibilihd", "哔哩哔哩 HD"))
    Platform.NETEASE -> listOf(OfficialApp("com.netease.cloudmusic", "网易云音乐"))
    Platform.TIEBA -> listOf(OfficialApp("com.baidu.tieba", "百度贴吧"), OfficialApp("com.baidu.searchbox", "百度"),
        OfficialApp("com.baidu.netdisk", "百度网盘"))
    Platform.SKLAND -> listOf(OfficialApp("com.hypergryph.skland", "森空岛"), OfficialApp("com.hypergryph.arknights", "明日方舟"))
}

private fun Context.installed(pkg: String) = try {
    @Suppress("DEPRECATION") packageManager.getPackageInfo(pkg, 0); true
} catch (_: PackageManager.NameNotFoundException) { false }

/** 尝试让官方 App 直接打开二维码链接；成功启动返回 true。 */
private fun Context.openInApp(content: String, app: OfficialApp?): Boolean {
    val uri = Uri.parse(content)
    val candidates = buildList {
        if (uri.scheme != "http" && uri.scheme != "https") {
            // 自定义协议（如鹰角的扫码链接）：交给系统路由；有分身时系统会让用户选
            add(Intent(Intent.ACTION_VIEW, uri))
        } else if (app != null) {
            add(Intent(Intent.ACTION_VIEW, uri).setPackage(app.pkg).addCategory(Intent.CATEGORY_BROWSABLE))
            if (app.pkg.startsWith("tv.danmaku") || app.pkg.startsWith("com.bilibili"))
                add(Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://browser?url=" + Uri.encode(content))).setPackage(app.pkg))
        }
    }
    for (intent in candidates) {
        if (intent.resolveActivity(packageManager) == null) continue
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
    }
    return false
}

private fun Context.launchApp(pkg: String): Boolean = try {
    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: Exception) { false }

/** 存到相册 Pictures/一叶签；Android 10 以下没有免权限的写入方式，返回 false 让用户截图。 */
private fun Context.saveToGallery(bitmap: Bitmap, name: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/一叶签")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (_: Exception) { false }
}

/** 白底留边的二维码位图，便于官方 App 从相册识别。 */
private fun renderQr(content: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private sealed interface QrUi {
    data object Loading : QrUi
    data class Ready(val ticket: QrTicket, val bitmap: Bitmap, val poll: QrPoll) : QrUi
    data class Error(val message: String) : QrUi
}

@Composable
internal fun QrLoginDialog(platform: Platform, onCredential: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val provider = remember(platform) { QrLogin.provider(platform) }
    val apps = remember(platform) { officialApps(platform).filter { context.installed(it.pkg) } }
    var round by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<QrUi>(QrUi.Loading) }
    var hint by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val success = Color(LocalThemePalette.current.success)

    LaunchedEffect(round) {
        ui = QrUi.Loading; hint = ""
        val ticket = try { withContext(Dispatchers.IO) { provider.start() } } catch (e: Exception) {
            ui = QrUi.Error(when (e) {
                is QrLoginException -> e.message ?: "获取二维码失败，请稍后重试"
                is AdapterException -> e.message
                is java.io.IOException -> "网络连接失败，请检查网络后重试"
                else -> "获取二维码失败（${e.javaClass.simpleName}）"
            }); return@LaunchedEffect
        }
        val bitmap = ticket.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: renderQr(ticket.content)
        ui = QrUi.Ready(ticket, bitmap, QrPoll.Waiting)
        while (isActive) {
            delay(2000)
            now = System.currentTimeMillis()
            val result = try { withContext(Dispatchers.IO) { provider.poll(ticket) } } catch (e: Exception) { QrPoll.Failed("轮询失败（${e.javaClass.simpleName}）") }
            ui = QrUi.Ready(ticket, bitmap, result)
            when (result) {
                is QrPoll.Confirmed -> { onCredential(result.credential); return@LaunchedEffect }
                is QrPoll.Expired, is QrPoll.Failed -> return@LaunchedEffect
                else -> if (System.currentTimeMillis() > ticket.expiresAt) { ui = QrUi.Ready(ticket, bitmap, QrPoll.Expired("二维码已过期")); return@LaunchedEffect }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(platform, 36.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("用官方 App 扫码登录", style = MaterialTheme.typography.titleMedium)
                        Hint(platform.title)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭") }
                }

                Box(Modifier.size(232.dp).clip(RoundedCornerShape(20.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    when (val s = ui) {
                        is QrUi.Loading -> CircularProgressIndicator()
                        is QrUi.Error -> Text(s.message, Modifier.padding(16.dp), color = Color(0xFF5B6070), textAlign = TextAlign.Center)
                        is QrUi.Ready -> {
                            Image(s.bitmap.asImageBitmap(), "登录二维码", Modifier.fillMaxSize().padding(8.dp), filterQuality = FilterQuality.None)
                            val overlay = s.poll is QrPoll.Scanned || s.poll is QrPoll.Confirmed || s.poll is QrPoll.Expired || s.poll is QrPoll.Failed
                            if (overlay) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.92f))
                                .clickable(enabled = s.poll is QrPoll.Expired || s.poll is QrPoll.Failed) { round++ }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when (val p = s.poll) {
                                        is QrPoll.Scanned -> { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(44.dp), tint = success); Text("已扫码\n请在手机上点「确认登录」", color = Color(0xFF1A1C24), textAlign = TextAlign.Center) }
                                        is QrPoll.Confirmed -> { CircularProgressIndicator(); Text("已确认，正在校验账号…", color = Color(0xFF1A1C24)) }
                                        is QrPoll.Expired -> { Icon(Icons.Rounded.Refresh, null, Modifier.size(40.dp), tint = Color(0xFF4C5FD5)); Text("${p.message}\n点击刷新", color = Color(0xFF1A1C24), textAlign = TextAlign.Center) }
                                        is QrPoll.Failed -> { Icon(Icons.Rounded.Refresh, null, Modifier.size(40.dp), tint = Color(0xFFD93A3A)); Text("${p.message}\n点击重试", color = Color(0xFF1A1C24), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }

                val ready = ui as? QrUi.Ready
                if (ready != null && (ready.poll is QrPoll.Waiting || ready.poll is QrPoll.Scanned)) {
                    val left = ((ready.ticket.expiresAt - now) / 1000).coerceAtLeast(0)
                    Hint("二维码 ${left / 60}:${"%02d".format(left % 60)} 后过期，扫码后回到这里会自动完成")
                }
                if (ui is QrUi.Error) OutlinedButton(onClick = { round++ }) { Text("重新获取二维码") }

                if (ready != null && ready.poll !is QrPoll.Confirmed) {
                    if (apps.isEmpty()) {
                        Hint("没有检测到可扫码的官方 App（${officialApps(platform).joinToString("、") { it.name }}）。可以用另一台手机扫码，或改用下方网页登录。", Modifier.fillMaxWidth())
                    }
                    apps.forEach { app ->
                        Button(onClick = {
                            hint = when {
                                context.openInApp(ready.ticket.content, app) -> "已在「${app.name}」中打开，确认登录后返回这里"
                                else -> {
                                    val saved = context.saveToGallery(ready.bitmap, "${platform.name.lowercase()}-login")
                                    val launched = context.launchApp(app.pkg)
                                    when {
                                        saved && launched -> "二维码已存入相册。在「${app.name}」里点「扫一扫」→ 右上角「相册」选这张图，确认后返回这里"
                                        launched -> "请截图保存二维码，再在「${app.name}」的「扫一扫」里从相册选择"
                                        else -> "未能打开「${app.name}」，请手动打开后扫码"
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("打开「${app.name}」确认登录") }
                    }
                    TextButton(onClick = {
                        val saved = context.saveToGallery(ready.bitmap, "${platform.name.lowercase()}-login")
                        val app = apps.firstOrNull()
                        val launched = app != null && context.launchApp(app.pkg)
                        hint = when {
                            saved && launched -> "二维码已存入相册。在「${app!!.name}」里点「扫一扫」→「相册」选这张图，确认后返回这里"
                            saved -> "二维码已存入相册（Pictures/一叶签），请在官方 App 的「扫一扫」里从相册选择"
                            else -> "当前系统无法直接存图，请截图后在官方 App 的「扫一扫」里从相册选择"
                        }
                    }) { Text(if (apps.isEmpty()) "保存二维码到相册" else "没弹出确认页？改用相册扫码") }
                    if (hint.isNotBlank()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(hint, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Hint("分身账号：系统双开的手机点按钮后会弹出「原版 / 分身」选择；其他分身工具请先存图，再在分身 App 里从相册扫码。每个账号确认后会分别保存。", Modifier.fillMaxWidth())
                }
            }
        }
    }
}
