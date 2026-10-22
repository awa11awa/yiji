package cn.yiji.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yiji.app.core.*
import kotlinx.coroutines.launch
import cn.yiji.app.data.*
import org.json.JSONObject

@Composable
fun DetailScreen(event: Event, repository: Repository, conflicts: List<TimeConflict>, onEdit: () -> Unit,
    onConflict: () -> Unit, onStatus: (String) -> Unit, onDelete: () -> Unit) {
    var sources by remember(event.id) { mutableStateOf(emptyList<RawMessage>()) }
    var history by remember(event.id) { mutableStateOf(emptyList<History>()) }
    val scope = rememberCoroutineScope()
    var taskError by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    LaunchedEffect(event.id, event.updatedAt) { sources = repository.dao.sources(event.id); history = repository.dao.history(event.id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${event.category} · ${if (event.kind == "DEADLINE") "任务" else "日程"} · ${statusLabel(event.status)}", color = MaterialTheme.colorScheme.primary)
                    Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(eventTime(event), style = MaterialTheme.typography.titleMedium)
                    if (event.kind == "SCHEDULE" && event.endAt == null) Text("时长未知，冲突检查不完整。", style = MaterialTheme.typography.bodySmall)
                    if (event.subtasks.isNotEmpty()) {
                        Text("子任务 · ${event.subtasks.count { it.done }}/${event.subtasks.size} 已完成", fontWeight = FontWeight.Bold)
                        event.subtasks.forEach { task ->
                            Row {
                                Checkbox(task.done, onCheckedChange = { checked -> scope.launch {
                                    try { repository.setSubtaskDone(event.id, task.id, checked); taskError = null }
                                    catch (e: Exception) { taskError = e.message ?: "保存子任务失败" }
                                } }, enabled = event.status == "ACTIVE")
                                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                    Text("${if (task.optional) "选做 · " else ""}${task.title}")
                                    Text(fieldTime(task.dueAt, task.due), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    taskError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (event.location.isNotBlank()) Text("地点：${event.location}")
                    if (event.action.isNotBlank()) Text("需要完成：${event.action}")
                    Text(if (event.reminderMinutes < 0) "不提醒" else if (event.draft().reminderPoints().isEmpty()) "时间未完整，暂未安排时间提醒" else "提前 ${event.reminderMinutes} 分钟提醒", style = MaterialTheme.typography.bodySmall)
                    if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onEdit) { Text("编辑") }
                        OutlinedButton(onClick = { onStatus(if (event.status == "ACTIVE") "DONE" else "ACTIVE") }) { Text(if (event.status == "ACTIVE") "标记完成" else "恢复事项") }
                    }
                    if (event.status == "ACTIVE") TextButton(onClick = { onStatus("CANCELLED") }) { Text("取消事项") }
                }
            }
        }
        if (conflicts.isNotEmpty()) item { Card(onClick = onConflict, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("存在 ${conflicts.size} 个时间冲突 · 查看并调整", modifier = Modifier.fillMaxWidth().padding(18.dp), fontWeight = FontWeight.Bold)
        } }
        item { Text("通知来源", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (sources.isEmpty()) item { Text("手动录入，暂无关联通知。", style = MaterialTheme.typography.bodyMedium) }
        items(sources, key = { it.id }) { r -> Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${r.source} · ${formatTime(r.receivedAt)}", fontWeight = FontWeight.Bold)
            if (r.title.isNotBlank()) Text(r.title)
            Text(r.body, style = MaterialTheme.typography.bodyMedium)
        } } }
        item { Text("变更历史", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(history, key = { it.id }) { h -> Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${h.action} · ${formatTime(h.changedAt)}", fontWeight = FontWeight.Bold)
                if (h.before.isNotBlank()) Text("修改前：${snapshotText(h.before)}", style = MaterialTheme.typography.bodySmall)
                if (h.after.isNotBlank()) Text("修改后：${snapshotText(h.after)}", style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { TextButton(onClick = { delete = true }) { Text("删除事项", color = MaterialTheme.colorScheme.error) } }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这个事项？") }, text = { Text("事项及其变更历史将被删除，关联的原始通知会保留。") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("保留") } })
}

private fun snapshotText(value: String): String = runCatching {
    val o = JSONObject(value)
    val key = if (o.getString("kind") == "DEADLINE") "dueAt" else "startAt"
    val anchor = if (o.isNull(key)) null else o.getLong(key)
    val ending = if (o.getString("kind") == "SCHEDULE" && !o.isNull("endAt")) " — ${formatTime(o.getLong("endAt"))}" else " 截止"
    buildString {
        append("${o.getString("title")} · ${o.optString("category")}\n${formatTime(anchor)}$ending")
        append("\n地点：${o.optString("location").ifBlank { "未填写" }}")
        append("\n要求：${o.optString("action").ifBlank { "未填写" }}")
        append("\n${statusLabel(o.optString("status"))} · 提前 ${o.optInt("reminderMinutes")} 分钟提醒")
        if (o.optString("note").isNotBlank()) append("\n备注：${o.optString("note")}")
    }
}.getOrDefault(value)
