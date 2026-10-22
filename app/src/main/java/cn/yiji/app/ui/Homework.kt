package cn.yiji.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yiji.app.core.Categories
import cn.yiji.app.data.Event
import cn.yiji.app.data.RawMessage
import cn.yiji.app.data.Suggestion
import kotlinx.coroutines.delay

internal fun homeworkEvents(events: List<Event>, status: String, query: String, now: Long): List<Event> =
    events.filter { event ->
        event.category == Categories.HOMEWORK &&
            (if (status == "OVERDUE") event.status == "ACTIVE" && event.kind == "DEADLINE" &&
                event.dueAt?.let { it < now } == true else status == "ALL" || event.status == status) &&
            "${event.title} ${event.action} ${event.note} ${event.location}".contains(query.trim(), ignoreCase = true)
    }.sortedWith(compareBy<Event> { it.anchor ?: Long.MAX_VALUE }.thenBy { it.title }.thenBy { it.id })

internal fun pendingHomework(suggestions: List<Suggestion>, query: String = ""): List<Suggestion> =
    suggestions.filter { it.state == "PENDING" && it.category == Categories.HOMEWORK &&
        "${it.title} ${it.action} ${it.note}".contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy<Suggestion> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.id })

@Composable
fun HomeworkScreen(events: List<Event>, suggestions: List<Suggestion>, raw: List<RawMessage>,
    onEvent: (String) -> Unit, onConfirm: (String) -> Unit, onDismiss: (String) -> Unit,
    onAdd: () -> Unit, onPaste: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("ACTIVE") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    val assignments = events.filter { it.category == Categories.HOMEWORK }
    val pending = pendingHomework(suggestions, query)
    val showPending = status in setOf("ACTIVE", "ALL", "PENDING")
    val filtered = if (status == "PENDING") emptyList() else homeworkEvents(events, status, query, now)
    val activeCount = assignments.count { it.status == "ACTIVE" }
    val overdueCount = homeworkEvents(assignments, "OVERDUE", "", now).size

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("待完成 $activeCount · 已逾期 $overdueCount · 待确认 ${pendingHomework(suggestions).size}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text("搜索课程、题目或提交要求") }, singleLine = true)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ACTIVE" to "待完成", "PENDING" to "待确认", "OVERDUE" to "已逾期",
                    "DONE" to "已完成", "CANCELLED" to "已取消", "ALL" to "全部").forEach { (value, label) ->
                    FilterChip(status == value, { status = value }, label = { Text(label) })
                }
            }
        }
        if (showPending && pending.isNotEmpty()) {
            item {
                Text("待确认作业", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("核对整份作业及子任务；时间未完整也可以确认保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pending, key = { "pending-${it.id}" }) { suggestion ->
                val original = raw.find { it.id == suggestion.rawId }
                Card(shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(suggestion.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(original?.body ?: suggestion.note, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(draftTime(suggestion.draft()),
                            style = MaterialTheme.typography.labelMedium)
                        Text(typeLabel(suggestion.type), style = MaterialTheme.typography.labelSmall)
                        if (suggestion.issues.isNotBlank()) Text(suggestion.issues,
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (original != null) Text(original.source, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onConfirm(suggestion.id) }) { Text("核对作业") }
                            TextButton(onClick = { onDismiss(suggestion.id) }) { Text("忽略") }
                        }
                    }
                }
            }
        }
        if (filtered.isNotEmpty()) item {
            Text(if (status == "ACTIVE") "待完成作业 · 按截止时间排序" else "作业列表",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(filtered, key = { "homework-${it.id}" }) { event ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EventCard(event) { onEvent(event.id) }
                if (event.action.isNotBlank()) Text(event.action, Modifier.padding(horizontal = 16.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (filtered.isEmpty() && (!showPending || pending.isEmpty())) item {
            EmptyState(if (assignments.isEmpty() && pending.isEmpty() && query.isBlank()) "还没有作业" else "没有匹配的作业",
                "粘贴老师布置的作业，或直接添加；确认后可在详情中标记完成。", Icons.Default.AutoStories) {
                Button(onClick = onPaste) { Text("导入作业文本") }
                TextButton(onClick = onAdd) { Text("手动添加作业") }
            }
        }
    }
}
