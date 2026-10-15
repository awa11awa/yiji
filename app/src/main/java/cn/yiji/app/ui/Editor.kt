package cn.yiji.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yiji.app.core.*
import cn.yiji.app.data.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun PasteScreen(initial: String, onImport: suspend (String, String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable(initial) { mutableStateOf(initial) }
    var source by rememberSaveable { mutableStateOf("手动粘贴") }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("复制班群、学校网站或邮件中的通知，粘贴到这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("通知标题（选填）") }, singleLine = true)
        OutlinedTextField(source, { source = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("来源名称") }, singleLine = true)
        OutlinedTextField(body, { body = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 220.dp), label = { Text("通知正文") }, supportingText = { Text("${body.length} / 16000 字") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (body.isBlank()) error = "请先粘贴通知内容"
            else if (body.length > 16000) error = "请将长通知分段导入"
            else if (!importing) {
                importing = true
                scope.launch {
                    try { onImport(title, body, source.ifBlank { "手动粘贴" }) }
                    catch (e: Exception) { error = e.message ?: "导入失败，请重试" }
                    finally { importing = false }
                }
            }
        }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text(if (importing) "正在提取…" else "提取重要事项") }
        Text("试试示例", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val examples = listOf(
            Triple("竞赛培训", "明天下午2点到3点在图书馆301召开竞赛培训。请携带电脑并准时参加。", "学院通知"),
            Triple("数学作业", "请于本周五23:59前提交数学作业，上传到课程平台。", "课程群"),
            Triple("班级会议", "明天14:30至15:30在教学楼A201召开班级会议。请准备本周总结。", "班级群"),
            Triple("竞赛培训", "竞赛培训延期至后天下午3点到4点，地点改为图书馆302。请携带电脑。", "学院通知"),
            Triple("竞赛培训", "因场地维修，竞赛培训取消，请同学们知悉。", "学院通知"),
        )
        examples.forEach { (name, text, label) -> OutlinedButton(onClick = { title = name; body = text; source = label; error = null }, modifier = Modifier.fillMaxWidth()) { Text(if (text.contains("延期")) "$name · 改期" else if (text.contains("取消")) "$name · 取消" else name) } }
        Text("每次导入一件主要事项；包含多项安排的长通知建议分段导入。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EventEditor(key: String, initial: Draft, initialReminder: Int, suggestion: Suggestion? = null,
    events: List<Event>, onSave: suspend (Draft, Int, String, String?, Long?) -> Unit, original: RawMessage? = null) {
    var type by rememberSaveable(key) { mutableStateOf(suggestion?.type ?: "NEW") }
    var targetId by rememberSaveable(key) { mutableStateOf(suggestion?.targetId) }
    val target = events.find { it.id == targetId && it.status == "ACTIVE" }
    fun filled(d: Draft, t: Event?): Draft = if (t == null || type == "NEW") d else d.copy(
        startAt = d.startAt ?: t.startAt, endAt = d.endAt ?: t.endAt, dueAt = d.dueAt ?: t.dueAt,
        location = d.location.ifBlank { t.location }, action = d.action.ifBlank { t.action }, category = d.category.let { if (it == "其他") t.category else it })
    val seed = filled(initial, target)
    var title by rememberSaveable(key) { mutableStateOf(seed.title) }
    var category by rememberSaveable(key) { mutableStateOf(seed.category) }
    var kind by rememberSaveable(key) { mutableStateOf(seed.kind) }
    var start by rememberSaveable(key) { mutableStateOf(seed.startAt) }
    var end by rememberSaveable(key) { mutableStateOf(seed.endAt) }
    var due by rememberSaveable(key) { mutableStateOf(seed.dueAt) }
    var location by rememberSaveable(key) { mutableStateOf(seed.location) }
    var action by rememberSaveable(key) { mutableStateOf(seed.action) }
    var note by rememberSaveable(key) { mutableStateOf(seed.note) }
    var reminder by rememberSaveable(key) { mutableIntStateOf(initialReminder) }
    var targetMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val targetRevision = remember(key, targetId) { target?.updatedAt }
    val draft = Draft(title.trim(), category, kind, if (kind == "SCHEDULE") start else null,
        if (kind == "SCHEDULE") end else null, if (kind == "DEADLINE") due else null, location.trim(), action.trim(), note.trim())
    val proposedConflicts = remember(draft, events, type, targetId) {
        if (type in setOf("MERGE", "CANCEL")) emptyList() else
            ConflictDetector.detect(events.filter { it.id != (if (suggestion == null && key != "new") key else if (type != "NEW") targetId else null) }.map { it.existing() } + ExistingItem("draft", draft))
                .filter { it.firstId == "draft" || it.secondId == "draft" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (suggestion != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(suggestion.reason, style = MaterialTheme.typography.bodyMedium)
                    Text("识别方式：${suggestion.engine} · 相对日期以通知接收时间为准", style = MaterialTheme.typography.bodySmall)
                    initial.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("确认后的处理方式", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NEW", "MERGE", "UPDATE", "CANCEL").forEach { value -> FilterChip(type == value, { type = value; error = null }, label = { Text(typeLabel(value)) }) }
            }
            if (type != "NEW") {
                Box {
                    OutlinedButton(onClick = { targetMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(target?.title ?: "选择关联的事项") }
                    DropdownMenu(targetMenu, { targetMenu = false }) {
                        events.filter { it.status == "ACTIVE" }.forEach { e -> DropdownMenuItem(text = { Text("${e.title}\n${eventTime(e)}") }, onClick = {
                            targetId = e.id; targetMenu = false
                            val merged = filled(initial, e)
                            start = merged.startAt; end = merged.endAt; due = merged.dueAt; kind = e.kind
                            location = merged.location; action = merged.action; category = merged.category
                        }) }
                    }
                }
                if (type == "MERGE") Text("合并只增加这条通知作为来源，保留关联事项的现有内容。", style = MaterialTheme.typography.bodyMedium)
                if (type == "CANCEL") Text("确认后将取消关联事项，并撤销它的提醒。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (type in setOf("NEW", "UPDATE")) {
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("事项标题") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(kind == "SCHEDULE", { kind = "SCHEDULE" }, label = { Text("日程 · 占用时间") })
                FilterChip(kind == "DEADLINE", { kind = "DEADLINE" }, label = { Text("任务 · 截止时间") })
            }
            if (kind == "SCHEDULE") {
                DateTimeField("开始时间", start) { start = it }
                DateTimeField("结束时间", end, start) { end = it }
            } else DateTimeField("截止时间", due) { due = it }
            Text("类别", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("作业", "报名", "会议", "课程", "活动", "考试", "材料", "其他").forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value) }) }
            }
            OutlinedTextField(location, { location = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("地点（选填）") })
            OutlinedTextField(action, { action = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("需要完成的动作") }, minLines = 2)
            OutlinedTextField(note, { note = it.take(16000) }, Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2)
            Text("提前提醒", style = MaterialTheme.typography.labelLarge)
            ReminderChips(reminder) { reminder = it }
            if (draft.anchor != null && draft.anchor!! <= System.currentTimeMillis()) Text("这个时间已过去，保存后不会安排新的提醒，请核对。", color = MaterialTheme.colorScheme.error)
            if (proposedConflicts.isNotEmpty()) Text("与 ${proposedConflicts.size} 个已有日程重叠。可先保存，再在冲突详情中调整。", color = MaterialTheme.colorScheme.error)
        }
        original?.let { r ->
            Text("通知原文 · ${r.source}", fontWeight = FontWeight.Bold)
            Text(r.body, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            error = if (type in setOf("NEW", "UPDATE")) draft.validationError() else null
            if (type != "NEW" && target == null) error = "请选择有效的关联事项"
            if (error == null && !saving) { saving = true; scope.launch {
                try { onSave(draft, reminder, type, targetId, targetRevision) }
                catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
                finally { saving = false }
            } }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在保存…" else if (suggestion != null) "确认${typeLabel(type)}" else "保存事项") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ReminderChips(value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1 to "不提醒", 0 to "准时", 10 to "10 分钟", 30 to "30 分钟", 60 to "1 小时", 1440 to "1 天").forEach { (minutes, label) ->
            FilterChip(value == minutes, { onChange(minutes) }, label = { Text(label) })
        }
    }
}

@Composable
fun DateTimeField(label: String, value: Long?, fallback: Long? = null, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val initial = Instant.ofEpochMilli(value ?: fallback ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
    OutlinedButton(onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                onChange(LocalDateTime.of(year, month + 1, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(10.dp)); Text("$label：${formatTime(value)}")
    }
}
