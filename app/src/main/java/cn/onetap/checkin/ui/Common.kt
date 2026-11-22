package cn.onetap.checkin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.theme.LocalDarkTheme
import cn.onetap.checkin.theme.LocalThemePalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val ShanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
internal val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/** 今日任务的三类汇总，用于首页统计和账号进度。 */
internal enum class Bucket { DONE, ATTENTION, PENDING }

internal fun bucketOf(status: TaskStatus): Bucket = when (status) {
    TaskStatus.SUCCESS, TaskStatus.ALREADY -> Bucket.DONE
    TaskStatus.FAILED, TaskStatus.EXPIRED, TaskStatus.MANUAL, TaskStatus.INELIGIBLE, TaskStatus.NOT_CONNECTED -> Bucket.ATTENTION
    else -> Bucket.PENDING
}

internal fun targetLabel(t: Target) = listOf(t.game, t.label, t.server).filter { it.isNotBlank() }.distinct().joinToString(" · ")
internal fun accountName(a: Account) = a.note.ifBlank { a.displayName }
internal fun enabledTargets(a: Account) = a.targets.filter { it.bound && it.id in a.enabled }
internal fun todayStatus(state: AppState, a: Account, t: Target): TaskStatus =
    if (!t.bound) TaskStatus.UNBOUND else Rules.latest(state, a, t)?.status ?: TaskStatus.PENDING

internal fun formatTime(time: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ShanghaiZone).format(Instant.ofEpochMilli(time))
internal fun formatClock(time: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ShanghaiZone).format(Instant.ofEpochMilli(time))

/** “今天 09:00” / “明天 09:00” / “11-15 09:00” */
internal fun formatTrigger(time: Long, now: Long = System.currentTimeMillis()): String {
    val target = Instant.ofEpochMilli(time).atZone(ShanghaiZone)
    val today = Instant.ofEpochMilli(now).atZone(ShanghaiZone).toLocalDate()
    val hm = DateTimeFormatter.ofPattern("HH:mm").format(target)
    return when (target.toLocalDate()) {
        today -> "今天 $hm"
        today.plusDays(1) -> "明天 $hm"
        else -> DateTimeFormatter.ofPattern("MM-dd ").format(target) + hm
    }
}

internal fun formatDateHeader(date: LocalDate, today: LocalDate = LocalDate.now(ShanghaiZone)): String {
    val week = "一二三四五六日"[date.dayOfWeek.value - 1]
    val prefix = when (date) { today -> "今天 · "; today.minusDays(1) -> "昨天 · "; else -> "" }
    return "$prefix${date.monthValue}月${date.dayOfMonth}日 周$week"
}

@Composable
internal fun statusColor(status: TaskStatus): Color = when (bucketOf(status)) {
    Bucket.DONE -> Color(LocalThemePalette.current.success)
    Bucket.ATTENTION -> if (status == TaskStatus.INELIGIBLE || status == TaskStatus.NOT_CONNECTED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Bucket.PENDING -> when (status) {
        TaskStatus.UNKNOWN, TaskStatus.INTERRUPTED -> Color(LocalThemePalette.current.warning)
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = 0.13f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
internal fun StatusPill(status: TaskStatus, modifier: Modifier = Modifier, targetId: String = "") =
    StatusPill(Rules.resultTitle(status, targetId), statusColor(status), modifier)

internal data class PlatformStyle(val mark: String, val light: Color, val dark: Color)

internal fun platformStyle(p: Platform) = when (p) {
    Platform.NETEASE -> PlatformStyle("云", Color(0xFFE5322D), Color(0xFFFF6B66))
    Platform.TIEBA -> PlatformStyle("贴", Color(0xFF3D7BF7), Color(0xFF7FA8FF))
    Platform.SKLAND -> PlatformStyle("森", Color(0xFF2F3A4A), Color(0xFFB8C4D6))
    Platform.BILIBILI -> PlatformStyle("B", Color(0xFFF25D8E), Color(0xFFFF8DB3))
    Platform.HEYBOX -> PlatformStyle("盒", Color(0xFF26282E), Color(0xFFC9CDD6))
}

@Composable
internal fun platformColor(p: Platform): Color = platformStyle(p).let { if (LocalDarkTheme.current) it.dark else it.light }

@Composable
internal fun PlatformBadge(p: Platform, size: Dp = 40.dp) {
    val color = platformColor(p)
    val icon = rememberPlatformAppIcon(p)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f))
            .then(if (icon == null) Modifier.background(color.copy(alpha = 0.15f)) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Image(icon, contentDescription = p.title, modifier = Modifier.fillMaxSize())
        else Text(platformStyle(p).mark, color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/**
 * 用作平台图标的官方 App 包名，按优先级排列。只列同品牌的 App：
 * 比如森空岛不用明日方舟的图标，贴吧不用百度 App 的图标，免得显示成别的 App。
 */
private fun iconPackages(p: Platform): List<String> = when (p) {
    Platform.BILIBILI -> listOf("tv.danmaku.bili", "com.bilibili.app.blue", "tv.danmaku.bilibilihd")
    Platform.NETEASE -> listOf("com.netease.cloudmusic")
    Platform.TIEBA -> listOf("com.baidu.tieba")
    Platform.SKLAND -> listOf("com.hypergryph.skland")
    Platform.HEYBOX -> listOf("com.max.xiaoheihe")
}

/** 已读取过的图标（没装的平台记为 null），整个进程共用，避免每次重组都去读安装包。 */
private val appIconCache = java.util.concurrent.ConcurrentHashMap<Platform, java.util.Optional<ImageBitmap>>()

/** 读取手机上已安装的官方 App 图标；没装或读取失败时返回 null，界面退回文字徽章。 */
@Composable
private fun rememberPlatformAppIcon(p: Platform): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val icon by produceState(appIconCache[p]?.orElse(null), p) {
        if (appIconCache.containsKey(p)) return@produceState
        val loaded = withContext(Dispatchers.IO) { loadAppIcon(context, p) }
        appIconCache[p] = java.util.Optional.ofNullable(loaded)
        value = loaded
    }
    return icon
}

private fun loadAppIcon(context: android.content.Context, p: Platform): ImageBitmap? {
    val pm = context.packageManager
    for (pkg in iconPackages(p)) {
        val drawable = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { continue }
        return try {
            // 自适应图标没有固定尺寸，统一画到 144×144 的位图上，各处显示都够清晰
            val px = 144
            val bitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        } catch (_: Exception) { null }
    }
    return null
}

/** 首页环形进度，带缓动。 */
@Composable
internal fun ProgressRing(fraction: Float, size: Dp, stroke: Dp, track: Color, bar: Color, content: @Composable BoxScope.() -> Unit) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (animated > 0f) drawArc(bar, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 渐变主卡片配色（浅色 / 深色各一套）。 */
@Composable
internal fun heroBrush(): Brush = if (LocalDarkTheme.current)
    Brush.linearGradient(listOf(Color(0xFF34409E), Color(0xFF5B3AA8)))
else Brush.linearGradient(listOf(Color(0xFF5468EE), Color(0xFF8C5BEF)))

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (LocalDarkTheme.current) 0.dp else 1.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
internal fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp, start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun SettingSwitch(label: String, value: Boolean, enabled: Boolean = true, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (description != null) Hint(description)
        }
        Switch(value, onChange, enabled = enabled)
    }
}

/** 一行状态检查：图标 + 标题 + 说明 + 可选操作按钮。 */
@Composable
internal fun CheckRow(ok: Boolean, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    val good = Color(LocalThemePalette.current.success)
    val warn = Color(LocalThemePalette.current.warning)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background((if (ok) good else warn).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Warning,
                null, Modifier.size(16.dp), tint = if (ok) good else warn)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && !ok) TextButton(onClick = onAction) { Text(action) }
    }
}
