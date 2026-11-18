package cn.onetap.checkin.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.onetap.checkin.core.*

@Composable
internal fun AccountsScreen(
    state: AppState,
    busy: Boolean,
    inspecting: Boolean,
    onMutate: ((AppState) -> AppState) -> Unit,
    onValidate: (Account) -> Unit,
    onEdit: (Account) -> Unit,
    onDelete: (Account) -> Unit
) {
    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("账号", "共 ${state.accounts.size} 个账号") }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text("森空岛主账号和分身账号在此分别授权。相同平台身份不能重复添加。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (state.accounts.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row { Platform.entries.forEach { PlatformBadge(it, 44.dp); Spacer(Modifier.width(10.dp)) } }
                Text("还没有账号", style = MaterialTheme.typography.titleMedium)
                Hint("点右下角按钮，通过官方网页登录添加")
            }
        }
        items(state.accounts.sortedBy { it.platform.ordinal }, key = { it.id }) { a ->
            AccountCard(a, busy, inspecting, onMutate, onValidate, onEdit, onDelete)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(
    a: Account, busy: Boolean, inspecting: Boolean,
    onMutate: ((AppState) -> AppState) -> Unit,
    onValidate: (Account) -> Unit, onEdit: (Account) -> Unit, onDelete: (Account) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var open by rememberSaveable(a.id) { mutableStateOf(a.targets.size <= 6) }
    val locked = busy || inspecting
    fun change(update: (Account) -> Account) = onMutate { s -> s.copy(accounts = s.accounts.map { if (it.id == a.id) update(it) else it }) }

    Card(
        Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(a.platform, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.platform.title} · ${accountName(a)}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(if (a.note.isNotBlank()) "${a.displayName} · ID ${a.userId}" else "ID ${a.userId}")
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !locked) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("备注 / 更新凭证") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onEdit(a) })
                        DropdownMenuItem(text = { Text("退出本地授权") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null) },
                            onClick = { menu = false; change { it.copy(credential = "", enabled = emptySet()) } })
                        DropdownMenuItem(text = { Text("删除账号和记录", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete(a) })
                    }
                }
            }
            if (a.credential.isBlank()) StatusPill("已退出本地授权，请「备注 / 更新凭证」重新登录", MaterialTheme.colorScheme.error)
            val details = ResultPresentation.accountDetails(a)
            Hint(listOf("上次校验 ${formatTime(a.checkedAt).dropLast(3)}", details).filter { it.isNotBlank() }.joinToString(" · "))

            if (a.platform == Platform.TIEBA) {
                Hint("随机一赞：每天在该吧随机点赞一帖，按吧单独勾选")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val forums = a.targets.filter { it.type == "forum" }
                    val likes = a.targets.filter { it.type == "daily-like" }
                    AssistChip(onClick = { change { it.copy(enabled = it.enabled + forums.filter { t -> t.bound }.map { t -> t.id }) } },
                        label = { Text("全选贴吧") }, enabled = !locked)
                    AssistChip(onClick = { change { it.copy(enabled = it.enabled - forums.map { t -> t.id }.toSet()) } },
                        label = { Text("取消全部贴吧") }, enabled = !locked)
                    AssistChip(onClick = { change { it.copy(enabled = it.enabled + likes.filter { t -> t.bound }.map { t -> t.id }) } },
                        label = { Text("全选随机一赞") }, enabled = !locked && a.credential.isNotBlank())
                    AssistChip(onClick = { change { it.copy(enabled = it.enabled - likes.map { t -> t.id }.toSet()) } },
                        label = { Text("取消全部随机一赞") }, enabled = !locked)
                }
            }

            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val selected = a.targets.count { it.bound && it.id in a.enabled }
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("签到目标", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("已选 $selected / ${a.targets.count { it.bound }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) a.targets.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = t.id in a.enabled,
                                onCheckedChange = { v -> change { it.copy(enabled = if (v) it.enabled + t.id else it.enabled - t.id) } },
                                enabled = !locked && t.bound && a.credential.isNotBlank()
                            )
                            Text(targetLabel(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                color = if (t.bound) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!t.bound) StatusPill(TaskStatus.UNBOUND)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onValidate(a) }, enabled = !locked && a.credential.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("校验 / 刷新")
                }
                FilledTonalButton(onClick = { onEdit(a) }, enabled = !locked, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("更新凭证")
                }
            }
        }
    }
}
