package cn.onetap.checkin.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target

// ───────────────────────── 签到附带的数据（等级、经验、积分等） ─────────────────────────

/** 有进度的数据画进度条并显示「当前 / 所需 · 百分比」，其余数据合并成一行「标签 数值」。 */
@Composable
internal fun StatsView(stats: List<Stat>, modifier: Modifier = Modifier) {
    if (stats.isEmpty()) return
    val (progress, plain) = stats.partition { (it.total ?: 0) > 0 && it.current != null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (plain.isNotEmpty()) Hint(plain.joinToString(" · ") { listOf(it.label, it.value).filter(String::isNotBlank).joinToString(" ") })
        progress.forEach { StatProgress(it) }
    }
}

/** 升级进度：百分比向下取整，避免还差一点时就显示 100%。 */
internal fun progressPercent(current: Long, total: Long): Int =
    if (total <= 0) 0 else ((current.coerceAtLeast(0) * 100) / total).coerceIn(0, 100).toInt()

@Composable
private fun StatProgress(s: Stat) {
    val current = s.current ?: return
    val total = s.total ?: return
    val percent = progressPercent(current, total)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(s.label, s.value).filter(String::isNotBlank).joinToString(" "),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$current / $total · $percent%", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GradientProgressBar(current.toFloat() / total)
    }
}

/** 一整根进度条：底槽和填充之间不留缝，填充从蓝渐变到紫（和图标配色一致），出现时从 0 涨到当前值。 */
@Composable
private fun GradientProgressBar(fraction: Float) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fraction) { target = fraction.coerceIn(0f, 1f) }
    val animated by animateFloatAsState(target, tween(durationMillis = 900, easing = FastOutSlowInEasing), label = "progress")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val brush = Brush.horizontalGradient(listOf(Color(0xFF5B8CFF), Color(0xFF8C5BEF), Color(0xFFC07BFF)))
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (animated > 0f) {
            // 填充宽度至少等于高度，进度很小时也是一个完整的圆点，不会变成细缝
            val width = (size.width * animated).coerceAtLeast(size.height)
            drawRoundRect(brush, size = Size(width, size.height), cornerRadius = radius)
        }
    }
}

// ───────────────────────── 贴吧：首页汇总 + 二级详情 ─────────────────────────

private fun forumIdOf(t: Target) = t.metadata["forumId"].orEmpty()
private fun isForum(t: Target) = t.type == "forum"
private fun isLike(t: Target) = t.type == "daily-like"

/** 一个吧的签到项和点赞项配成一对（同一个 forumId）。 */
private data class ForumPair(
    val name: String,
    val sign: Triple<Target, TaskStatus, Record?>?,
    val like: Triple<Target, TaskStatus, Record?>?
)

private fun pairForums(rows: List<Triple<Target, TaskStatus, Record?>>): List<ForumPair> {
    val byForum = linkedMapOf<String, ForumPair>()
    for (row in rows) {
        val t = row.first
        if (!isForum(t) && !isLike(t)) continue
        val id = forumIdOf(t).ifBlank { t.id }
        val name = t.metadata["forumName"]?.let { "${it}吧" } ?: t.label
        val old = byForum[id] ?: ForumPair(name, null, null)
        byForum[id] = if (isForum(t)) old.copy(sign = row) else old.copy(like = row)
    }
    // 有问题的吧排前面
    return byForum.values.sortedBy { p ->
        listOfNotNull(p.sign?.second, p.like?.second).minOfOrNull {
            when (bucketOf(it)) { Bucket.ATTENTION -> 0; Bucket.PENDING -> 1; Bucket.DONE -> 2 }
        } ?: 2
    }
}

/** 首页的贴吧账号：只显示签到 / 点赞的完成数和需要处理的项，各吧详情点进去看。 */
@Composable
internal fun TiebaSummary(a: Account, rows: List<Triple<Target, TaskStatus, Record?>>) {
    var open by rememberSaveable(a.id) { mutableStateOf(false) }
    val signs = rows.filter { isForum(it.first) }
    val likes = rows.filter { isLike(it.first) }
    fun done(list: List<Triple<Target, TaskStatus, Record?>>) = list.count { bucketOf(it.second) == Bucket.DONE }
    // 首页只露出需要处理的项（失败、要验证、结果未确认等），最多 3 个
    val problems = rows.filter { bucketOf(it.second) == Bucket.ATTENTION || it.second in setOf(TaskStatus.UNKNOWN, TaskStatus.INTERRUPTED) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signs.isNotEmpty()) CountChip("签到", done(signs), signs.size)
            if (likes.isNotEmpty()) CountChip("点赞", done(likes), likes.size)
        }
        problems.take(3).forEach { (t, status, record) -> TargetRow(targetLabel(t), t.id, status, record) }
        if (problems.size > 3) Hint("另有 ${problems.size - 3} 项需要处理，点下方查看")
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("查看各吧详情", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (open) TiebaDetailDialog(a, rows) { open = false }
}

@Composable
private fun CountChip(label: String, done: Int, total: Int) {
    val color = if (total > 0 && done == total) statusColor(TaskStatus.SUCCESS) else MaterialTheme.colorScheme.onSurfaceVariant
    StatusPill("$label $done / $total", color)
}

/** 二级页面：每个吧一张小卡片，显示等级经验进度、签到和点赞状态。 */
@Composable
private fun TiebaDetailDialog(a: Account, rows: List<Triple<Target, TaskStatus, Record?>>, onDismiss: () -> Unit) {
    val forums = remember(rows) { pairForums(rows) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    PlatformBadge(Platform.TIEBA, 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(accountName(a), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint("共 ${forums.size} 个吧")
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(forums, key = { it.sign?.first?.id ?: it.like?.first?.id ?: it.name }) { ForumCard(it) }
                }
            }
        }
    }
}

@Composable
private fun ForumCard(p: ForumPair) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.sign?.let { MiniStatus("签到", it.second) }
                if (p.sign != null && p.like != null) Spacer(Modifier.width(6.dp))
                p.like?.let { MiniStatus("点赞", it.second) }
            }
            p.sign?.third?.let { StatsView(it.stats) }
            // 只有出问题时才显示具体说明，正常完成的不重复写「今日已签到」
            listOfNotNull(p.sign, p.like).filter { bucketOf(it.second) != Bucket.DONE && it.third != null }
                .forEach { Hint(ResultPresentation.message(it.third!!)) }
        }
    }
}

@Composable
private fun MiniStatus(label: String, status: TaskStatus) {
    val text = when (bucketOf(status)) {
        Bucket.DONE -> "已$label"
        Bucket.ATTENTION -> status.title
        Bucket.PENDING -> if (status == TaskStatus.PENDING) "待$label" else status.title
    }
    StatusPill(text, statusColor(status))
}
