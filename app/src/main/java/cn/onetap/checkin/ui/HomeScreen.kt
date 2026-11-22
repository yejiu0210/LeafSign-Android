package cn.onetap.checkin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.onetap.checkin.core.*
import cn.onetap.checkin.theme.LocalThemePalette
import java.time.LocalDate

private val retryable = setOf(TaskStatus.FAILED, TaskStatus.UNKNOWN, TaskStatus.INTERRUPTED)

@Composable
internal fun HomeScreen(
    state: AppState,
    clockTick: Long,
    busy: Boolean,
    inspecting: Boolean,
    progress: RunProgress?,
    nextTrigger: Long?,
    onRun: () -> Unit,
    onRetry: () -> Unit,
    onGoAccounts: () -> Unit
) {
    val all = remember(state, clockTick) { state.accounts.flatMap { a -> enabledTargets(a).map { a to it } } }
    val statuses = remember(state, clockTick) { all.map { (a, t) -> todayStatus(state, a, t) } }
    val done = statuses.count { bucketOf(it) == Bucket.DONE }
    val attention = statuses.count { bucketOf(it) == Bucket.ATTENTION }
    val retryCount = remember(state, clockTick) { all.count { (a, t) -> Rules.latest(state, a, t)?.status in retryable } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val withAccounts = Platform.entries.filter { p -> state.accounts.any { it.platform == p } }
    val missing = Platform.entries - withAccounts.toSet()

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("一叶签",formatDateHeader(LocalDate.now(ShanghaiZone)).removePrefix("今天 · ")) }
        item {
            HeroCard(done, all.size, busy, inspecting, progress, nextTrigger, state.settings.auto, retryCount, onRun, onRetry)
        }
        item { StatsRow(done, statuses.size - done - attention, attention) }
        if (state.settings.offerCatchup && !busy && all.any { (a, t) -> Rules.latest(state, a, t) == null }) item {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("发现今日未完成任务，点击一键签到可补跑今天。", Modifier.fillMaxWidth().padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (state.accounts.isEmpty()) item { EmptyHome(onGoAccounts) }
        items(withAccounts, key = { it.name }) { platform ->
            PlatformCard(platform, state, clockTick, expanded)
        }
        if (missing.isNotEmpty() && state.accounts.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onGoAccounts).padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                missing.forEach { PlatformBadge(it, 26.dp); Spacer(Modifier.width(6.dp)) }
                Spacer(Modifier.width(4.dp))
                Hint("还可添加：" + missing.joinToString("、") { it.title }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroCard(
    done: Int, total: Int, busy: Boolean, inspecting: Boolean, progress: RunProgress?,
    nextTrigger: Long?, auto: Boolean, retryCount: Int, onRun: () -> Unit, onRetry: () -> Unit
) {
    val white = Color.White
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(heroBrush()).padding(22.dp).animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("今日进度", color = white.copy(alpha = 0.78f), style = MaterialTheme.typography.labelLarge)
                    Text("今天 $done / $total 项已完成", color = white, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            total == 0 -> "添加账号并勾选任务后即可签到"
                            done == total -> "全部完成，明天见 ✨"
                            else -> "还有 ${total - done} 项待处理"
                        },
                        color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium
                    )
                }
                // 环形始终表示今日完成率；本次运行的进度由下方进度条表示，避免两个数字对不上
                val fraction = if (total == 0) 0f else done.toFloat() / total
                ProgressRing(fraction, 86.dp, 9.dp, white.copy(alpha = 0.22f), white) {
                    Text("${(fraction * 100).toInt()}%", color = white, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            AnimatedVisibility(busy || inspecting) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (progress != null && progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.finished.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
                            color = white, trackColor = white.copy(alpha = 0.22f), drawStopIndicator = {}
                        )
                        Text("已完成 ${progress.finished} / ${progress.total} · 同时进行 ${progress.running.size} 项",
                            color = white.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                        progress.running.take(3).forEach {
                            Text("· $it", color = white.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small), color = white, trackColor = white.copy(alpha = 0.22f))
                        Text(if (inspecting) "正在联系官方平台校验身份…" else progress?.last?.ifBlank { null } ?: "准备执行…",
                            color = white.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(
                onClick = onRun, enabled = !busy && !inspecting && total > 0,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = white, contentColor = Color(0xFF3B47C9),
                    disabledContainerColor = white.copy(alpha = 0.28f), disabledContentColor = white.copy(alpha = 0.8f)
                )
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = white)
                else Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "正在签到 ${progress?.finished ?: 0}/${progress?.total ?: total}" else "一键签到", style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DateRange, null, Modifier.size(16.dp), tint = white.copy(alpha = 0.8f))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (auto && nextTrigger != null) "下次自动签到：${formatTrigger(nextTrigger)}" else "未开启每日自动签到",
                    color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                if (retryCount > 0) TextButton(onClick = onRetry, enabled = !busy && !inspecting,
                    colors = ButtonDefaults.textButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = 0.5f))) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("重试 $retryCount 项")
                }
            }
        }
    }
}

@Composable
private fun StatsRow(done: Int, pending: Int, attention: Int) {
    val p = LocalThemePalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("已完成", done, Color(p.success), Modifier.weight(1f))
        StatTile("待处理", pending, Color(p.warning), Modifier.weight(1f))
        StatTile("需关注", attention, MaterialTheme.colorScheme.error, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: Color, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.5.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("$value", style = MaterialTheme.typography.headlineMedium, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyHome(onGoAccounts: () -> Unit) {
    SectionCard("还没有账号", subtitle = "支持网易云音乐、百度贴吧、森空岛、哔哩哔哩") {
        Row { Platform.entries.forEach { PlatformBadge(it, 36.dp); Spacer(Modifier.width(8.dp)) } }
        FilledTonalButton(onClick = onGoAccounts, modifier = Modifier.fillMaxWidth()) { Text("去账号页添加") }
    }
}

private const val COLLAPSED_ROWS = 4

@Composable
private fun PlatformCard(platform: Platform, state: AppState, clockTick: Long, expanded: MutableMap<String, Boolean>) {
    val accounts = state.accounts.filter { it.platform == platform }
    Card(
        Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(platform, 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(platform.title, style = MaterialTheme.typography.titleMedium)
                    if (platform == Platform.BILIBILI || platform == Platform.NETEASE) {
                        val kinds = accounts.flatMap { enabledTargets(it) }.map { it.label }.distinct()
                        if (kinds.isNotEmpty()) Hint(kinds.joinToString(" · "))
                    }
                }
            }
            accounts.forEachIndexed { index, a ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                AccountBlock(a, state, clockTick, expanded[a.id] == true) { expanded[a.id] = it }
            }
        }
    }
}

@Composable
private fun AccountBlock(a: Account, state: AppState, clockTick: Long, open: Boolean, onToggle: (Boolean) -> Unit) {
    val rows = remember(state, clockTick, a) {
        enabledTargets(a).map { t -> Triple(t, todayStatus(state, a, t), Rules.latest(state, a, t)) }
            // 需要关注的排在前面，折叠时也能一眼看到
            .sortedBy { (_, s, _) -> when (bucketOf(s)) { Bucket.ATTENTION -> 0; Bucket.PENDING -> 1; Bucket.DONE -> 2 } }
    }
    val done = rows.count { bucketOf(it.second) == Bucket.DONE }
    val disabled = a.targets.count { it.bound && it.id !in a.enabled }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(accountName(a), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (a.note.isNotBlank()) Hint(a.displayName)
            }
            if (a.credential.isBlank()) StatusPill("已退出授权", MaterialTheme.colorScheme.error)
            else Text("$done / ${rows.size}", style = MaterialTheme.typography.labelLarge,
                color = if (rows.isNotEmpty() && done == rows.size) Color(LocalThemePalette.current.success) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rows.isEmpty()) Hint(if (a.credential.isBlank()) "请到账号页更新凭证" else "未启用任何任务，可在账号页勾选")
        // 贴吧吧多，首页只放汇总，各吧详情进二级页面看
        if (a.platform == Platform.TIEBA && rows.isNotEmpty()) TiebaSummary(a, rows)
        val shown = if (a.platform == Platform.TIEBA) emptyList() else if (open || rows.size <= COLLAPSED_ROWS + 1) rows else rows.take(COLLAPSED_ROWS)
        shown.forEach { (t, status, record) -> TargetRow(targetLabel(t), t.id, status, record) }
        if (a.platform != Platform.TIEBA && rows.size > COLLAPSED_ROWS + 1) {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onToggle(!open) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if (open) "收起" else "展开全部 ${rows.size} 项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (disabled > 0) Hint("另有 $disabled 项未启用")
    }
}

@Composable
internal fun TargetRow(label: String, targetId: String, status: TaskStatus, record: Record?) {
    val pill = Rules.resultTitle(status, targetId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (record != null) {
                    // 说明和右侧状态一样时（比如都是「今日已签到」）就不重复写
                    if (record.message.isNotBlank() && record.message != pill && record.message != status.title)
                        Hint(record.message, Modifier.padding(top = 2.dp))
                    val experience = ResultPresentation.experienceState(record)
                    if (experience.isNotBlank()) Hint("经验：$experience")
                }
            }
            StatusPill(status, targetId = targetId)
        }
        if (record != null) StatsView(record.stats)
    }
}
