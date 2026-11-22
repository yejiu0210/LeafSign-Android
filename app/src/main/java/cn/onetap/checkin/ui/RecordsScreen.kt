package cn.onetap.checkin.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.onetap.checkin.core.*
import java.time.Instant
import java.time.LocalDate

private enum class RecordFilter(val title: String, val match: (TaskStatus) -> Boolean) {
    ALL("全部", { true }),
    DONE("成功", { bucketOf(it) == Bucket.DONE }),
    PENDING("待确认", { it == TaskStatus.UNKNOWN || it == TaskStatus.INTERRUPTED || it == TaskStatus.RUNNING || it == TaskStatus.PENDING }),
    ATTENTION("需处理", { bucketOf(it) == Bucket.ATTENTION })
}

private sealed interface RecordRow {
    data class Header(val date: LocalDate, val count: Int) : RecordRow
    data class Item(val record: Record) : RecordRow
}

@Composable
internal fun RecordsScreen(state: AppState) {
    var filter by rememberSaveable { mutableStateOf(RecordFilter.ALL) }
    val rows = remember(state.records, filter) {
        val shown = state.records.asReversed().asSequence().filter { filter.match(it.status) }.take(300).toList()
        shown.groupBy { Instant.ofEpochMilli(it.time).atZone(ShanghaiZone).toLocalDate() }
            .flatMap { (date, items) -> listOf<RecordRow>(RecordRow.Header(date, items.size)) + items.map { RecordRow.Item(it) } }
    }
    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("记录", "最近 ${state.records.size} 条 · 最多保留 3000 条加密本地记录") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                }
            }
        }
        if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Hint(if (state.records.isEmpty()) "还没有签到记录" else "没有符合筛选条件的记录")
            }
        }
        items(rows, key = { row -> when (row) { is RecordRow.Header -> "h-${row.date}"; is RecordRow.Item -> row.record.id } }) { row ->
            when (row) {
                is RecordRow.Header -> Text(
                    "${formatDateHeader(row.date)} · ${row.count} 条",
                    Modifier.padding(start = 4.dp, top = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is RecordRow.Item -> RecordCard(row.record)
            }
        }
    }
}

@Composable
private fun RecordCard(r: Record) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.5.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            PlatformBadge(r.platform, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.platform.title} · ${r.accountLabel}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatClock(r.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.task, style = MaterialTheme.typography.bodyMedium)
                StatusPill(r.status, Modifier.padding(vertical = 2.dp), r.targetId)
                ResultPresentation.message(r).let { if (it.isNotBlank()) Hint(it) }
                val experience = ResultPresentation.experienceState(r)
                if (experience.isNotBlank()) Hint("经验：$experience")
                if (r.diagnostic.isNotBlank()) Hint("诊断：${r.diagnostic}")
                Hint("周期 ${r.period}")
            }
        }
    }
}
