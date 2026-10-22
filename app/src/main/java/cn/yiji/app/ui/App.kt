package cn.yiji.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiji.app.core.*
import cn.yiji.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

fun formatTime(time: Long?): String = time?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "待补充时间"
fun typeLabel(type: String): String = when (type) { "MERGE" -> "合并来源"; "UPDATE" -> "更新事项"; "CANCEL" -> "取消事项"; else -> "新建事项" }
fun statusLabel(status: String): String = when (status) { "DONE" -> "已完成"; "CANCELLED" -> "已取消"; else -> "进行中" }
fun fieldTime(at: Long?, info: TimeField): String = when {
    at != null -> formatTime(at)
    info.precision == "DATE" -> "${info.date}（未给时刻）"
    info.precision == "TBD" -> "时间待定"
    info.text.isNotBlank() -> "${info.text}（待核对）"
    else -> "未提供"
}
fun draftTime(d: Draft): String = if (d.kind == "DEADLINE") buildList {
    if (d.opensAt != null || d.timing.open.precision != "NONE") add("${fieldTime(d.opensAt, d.timing.open)} 开放报名")
    if (d.dueAt != null || d.timing.due.precision != "NONE" || d.timing.due.text.isNotBlank()) add("${fieldTime(d.dueAt, d.timing.due)} 截止")
    if (d.subtasks.isNotEmpty()) add("${d.subtasks.count { it.done }}/${d.subtasks.size} 子任务完成 · 各项截止见详情")
    if (isEmpty()) add("未提供截止时间")
}.joinToString("\n") else "${fieldTime(d.startAt, d.timing.start)} 开始" +
    if (d.endAt != null || d.timing.end.precision != "NONE") " — ${fieldTime(d.endAt, d.timing.end)} 结束" else " · 结束时间未提供"
fun eventTime(e: Event): String = draftTime(e.draft())

enum class AppTab(val title: String, val label: String) {
    HOME("忆迹", "首页"), EVENTS("我的事项", "事项"), HOMEWORK("作业", "作业"),
    INBOX("通知箱", "通知箱"), SETTINGS("设置", "设置")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YijiApp(repository: Repository, requestedEvent: String?, sharedText: String?, consumeIntent: () -> Unit) = YijiTheme {
    val events by repository.events.collectAsStateWithLifecycle(initialValue = emptyList())
    val raw by repository.rawMessages.collectAsStateWithLifecycle(initialValue = emptyList())
    val suggestions by repository.suggestions.collectAsStateWithLifecycle(initialValue = emptyList())
    val jobs by repository.jobs.collectAsStateWithLifecycle(initialValue = emptyList())
    val readStore = remember(repository) { StatusReadStore(repository.preferences.context) }
    val seen by readStore.state.collectAsStateWithLifecycle()
    var unreadNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { unreadNow = System.currentTimeMillis(); delay(30_000) } }
    val inboxMarkers = remember(raw, suggestions) { raw.map { rawReadMarker(it, suggestions) } }
    val pendingMarkers = remember(suggestions) { pendingHomework(suggestions).map(::suggestionReadMarker) }
    var tabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    val tab = AppTab.valueOf(tabName)
    var route by rememberSaveable { mutableStateOf("") }
    var pasteText by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val conflicts = remember(events) { ConflictDetector.detect(events.map { it.existing() }) }
    LaunchedEffect(requestedEvent, sharedText) {
        if (requestedEvent != null) route = "detail:$requestedEvent"
        if (sharedText != null) { pasteText = sharedText; route = "paste" }
        consumeIntent()
    }
    BackHandler(route.isNotBlank()) { route = if (route == "apiGuide") "api" else "" }
    fun perform(action: suspend () -> Unit) { scope.launch { try { action() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作未完成，请重试") } } }
    val icons = mapOf(AppTab.HOME to Icons.Default.Home, AppTab.EVENTS to Icons.AutoMirrored.Filled.EventNote,
        AppTab.HOMEWORK to Icons.Default.AutoStories, AppTab.INBOX to Icons.Default.Inbox, AppTab.SETTINGS to Icons.Default.Settings)
    val routeTitle = when {
        route.startsWith("detail:") -> "事项详情"
        route.startsWith("edit:") -> "编辑事项"
        route.startsWith("confirm:") -> "识别确认"
        route.startsWith("complete:") -> "补全通知正文"
        route == "new" -> "添加事项"
        route == "newHomework" -> "添加作业"
        route == "paste" -> "导入通知文本"
        route == "conflicts" -> "时间冲突"
        route == "api" -> "API 与模型"
        route == "apiGuide" -> "获取 API 教程"
        else -> tab.title
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(routeTitle, fontWeight = FontWeight.Bold) }, navigationIcon = {
            if (route.isNotBlank()) IconButton(onClick = { route = if (route == "apiGuide") "api" else "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            if (route.isBlank()) IconButton(onClick = { pasteText = ""; route = "paste" }) { Icon(Icons.Default.ContentPaste, "粘贴通知") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { if (route.isBlank()) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            AppTab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tabName = item.name }, icon = {
                val count = when (item) {
                    AppTab.INBOX -> inboxMarkers.count { it.unread(seen) }
                    AppTab.HOMEWORK -> pendingMarkers.count { it.unread(seen) } +
                        events.filter { it.category == Categories.HOMEWORK }.count { eventReadMarker(it, unreadNow).unread(seen) }
                    AppTab.EVENTS -> events.count { eventReadMarker(it, unreadNow).unread(seen) }
                    else -> 0
                }
                BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) { Icon(icons.getValue(item), item.label) }
            }, label = { Text(item.label) }) }
        } },
        floatingActionButton = { if (route.isBlank() && tab in setOf(AppTab.HOME, AppTab.EVENTS, AppTab.HOMEWORK)) FloatingActionButton(
            onClick = { route = if (tab == AppTab.HOMEWORK) "newHomework" else "new" }) {
            Icon(Icons.Default.Add, if (tab == AppTab.HOMEWORK) "添加作业" else "添加事项")
        } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                route == "api" -> ApiProfilesScreen(repository, snackbar) { route = "apiGuide" }
                route == "apiGuide" -> ApiGuideScreen()
                route.startsWith("complete:") -> {
                    val original = raw.find { it.id == route.removePrefix("complete:") }
                    if (original != null) PasteScreen(original.body, initialTitle = original.title,
                        submitLabel = "保存完整正文并重新识别", onImport = { title, body, _ ->
                            repository.completeBody(original.id, title, body)
                            route = ""; tabName = AppTab.INBOX.name
                            snackbar.showSnackbar("正文已补全，已加入识别队列")
                        })
                    else EmptyState("通知已不存在", "请返回通知箱。", Icons.Default.Inbox)
                }
                route == "paste" -> PasteScreen(pasteText, onImport = { title, text, source ->
                    val result = repository.enqueueWithResult(title, text, source)
                    val rawId = result.id
                    val homework = repository.dao.allSuggestions().any { it.rawId == rawId && it.category == Categories.HOMEWORK }
                    route = ""; tabName = if (homework) AppTab.HOMEWORK.name else AppTab.INBOX.name
                    snackbar.showSnackbar(if (result.disposition == IngestDisposition.IGNORED) "模型判断为非待办，原文已保存在通知箱的已忽略列表" else "原文已保存，正在排队筛选与提取")
                })
                route == "new" || route == "newHomework" -> EventEditor(route,
                    if (route == "newHomework") Draft(category = Categories.HOMEWORK, kind = "DEADLINE") else Draft(),
                    repository.preferences.defaultReminder, events = events, onSave = { d, r, _, _, _ ->
                        repository.save(d, r); route = ""
                        tabName = if (d.category == Categories.HOMEWORK) AppTab.HOMEWORK.name else AppTab.EVENTS.name
                    })
                route.startsWith("edit:") -> {
                    val e = events.find { it.id == route.removePrefix("edit:") }
                    if (e != null) EventEditor(e.id, e.draft(), e.reminderMinutes, events = events, onSave = { d, r, _, _, _ ->
                        repository.save(d, r, e.id, e.updatedAt); route = "detail:${e.id}"
                    }) else EmptyState("正在读取事项", "如果事项已被删除，请返回列表。", Icons.AutoMirrored.Filled.EventNote)
                }
                route.startsWith("confirm:") -> {
                    val s = suggestions.find { it.id == route.removePrefix("confirm:") }
                    if (s != null) EventEditor(s.id, s.draft(), repository.preferences.defaultReminder, s, events, onSave = { d, r, type, target, revision ->
                        val id = repository.confirm(s.id, d, type, target, r, revision); route = "detail:$id"
                    }, original = raw.find { it.id == s.rawId })
                    else EmptyState("建议已处理", "返回通知箱查看其他通知。", Icons.Default.Done)
                }
                route.startsWith("detail:") -> {
                    val e = events.find { it.id == route.removePrefix("detail:") }
                    if (e != null) DetailScreen(e, repository, conflicts.filter { it.firstId == e.id || it.secondId == e.id },
                        onEdit = { route = "edit:${e.id}" }, onConflict = { route = "conflicts" },
                        onStatus = { status -> perform { repository.setStatus(e.id, status) } },
                        onDelete = { perform { repository.delete(e.id); route = "" } })
                    else EmptyState("正在读取事项", "如果事项已被删除，请返回列表。", Icons.AutoMirrored.Filled.EventNote)
                }
                route == "conflicts" -> ConflictScreen(conflicts, events) { route = "detail:$it" }
                tab == AppTab.HOME -> HomeScreen(events, raw.count { it.state == "PENDING" }, conflicts.size, { route = "detail:$it" },
                    { tabName = AppTab.INBOX.name }, { route = "conflicts" }, { route = "paste" }, { tabName = AppTab.SETTINGS.name })
                tab == AppTab.EVENTS -> EventsScreen(events, seen, readStore::markRead) { route = "detail:$it" }
                tab == AppTab.HOMEWORK -> HomeworkScreen(events, suggestions, raw,
                    onEvent = { route = "detail:$it" }, onConfirm = { route = "confirm:$it" },
                    onDismiss = { perform { repository.dismissSuggestion(it) } }, onAdd = { route = "newHomework" },
                    onPaste = { pasteText = ""; route = "paste" }, seen = seen, onRead = readStore::markRead)
                tab == AppTab.INBOX -> InboxScreen(raw, suggestions, onConfirm = { route = "confirm:$it" },
                    onDismiss = { perform { repository.dismissSuggestion(it) } }, onDelete = { perform { repository.deleteRaw(it) } },
                    onPaste = { pasteText = ""; route = "paste" }, onRetry = { perform { repository.enqueueRetry(it) } },
                    onCloudReview = { perform { repository.enqueueRetry(it, forceCloud = true) } },
                    onComplete = { route = "complete:$it" }, onIgnoreRaw = { perform { repository.dismiss(it) } },
                    onManual = { perform { route = "confirm:${repository.manualSuggestion(it)}" } }, jobs = jobs,
                    seen = seen, onRead = readStore::markRead)
                else -> SettingsScreen(repository, snackbar) { route = "api" }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun EventCard(event: Event, onClick: () -> Unit) {
    val overdue = eventBucket(event, System.currentTimeMillis()) == "OVERDUE"
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(if (event.kind == "DEADLINE") Icons.Default.HourglassBottom else Icons.Default.Event, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(eventTime(event), style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.location.isNotBlank()) Text(event.location, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text("${event.category} · ${if (overdue) "已逾期" else statusLabel(event.status)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun HomeScreen(events: List<Event>, pending: Int, conflicts: Int, onEvent: (String) -> Unit, onPending: () -> Unit,
    onConflicts: () -> Unit, onPaste: () -> Unit, onSettings: () -> Unit) {
    val today = LocalDate.now()
    val active = events.filter { it.status == "ACTIVE" }
    val todays = active.filter { it.anchor?.let { t -> Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate() == today } == true }
    val due = active.filter { it.kind == "DEADLINE" && it.dueAt != null && it.dueAt <= System.currentTimeMillis() + 7 * 86_400_000L }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 6.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.CHINA)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                    Text("让重要的事\n都有迹可循", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    Text("今天 ${todays.size} 件事项 · ${active.size} 件进行中", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("待确认", pending, Icons.AutoMirrored.Filled.FactCheck, Modifier.weight(1f), onPending)
                StatCard("时间冲突", conflicts, Icons.Default.Schedule, Modifier.weight(1f), onConflicts)
            }
        }
        if (events.isEmpty()) item { EmptyState("从一条通知开始", "粘贴班群通知，核对后生成事项；也可以在设置中授权接入应用通知。", Icons.Default.AutoStories) {
            Button(onClick = onPaste) { Text("粘贴通知") }; TextButton(onClick = onSettings) { Text("设置通知接入") }
        } }
        item { Text("今日安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (todays.isEmpty()) item { Text("今天还没有安排，留些时间给自己。", style = MaterialTheme.typography.bodyMedium) }
        items(todays, key = { "today-${it.id}" }) { EventCard(it) { onEvent(it.id) } }
        item { Text("近期截止", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (due.isEmpty()) item { Text("未来 7 天没有待完成的截止事项。", style = MaterialTheme.typography.bodyMedium) }
        items(due, key = { "due-${it.id}" }) { EventCard(it) { onEvent(it.id) } }
    }
}

@Composable
fun StatCard(label: String, count: Int, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EventsScreen(events: List<Event>, seen: Map<String, String> = emptyMap(), onRead: (List<ReadMarker>) -> Unit = {},
    onEvent: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("ACTIVE") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val filtered = events.filter { (status == "ALL" || eventBucket(it, now) == status) && (category == "全部" || it.category == category) &&
        "${it.title} ${it.location} ${it.action}".contains(query, ignoreCase = true) }
    val markers = filtered.map { eventReadMarker(it, now) }
    LaunchedEffect(markers) { onRead(markers) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索标题、地点或要求") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ACTIVE" to "进行中", "OVERDUE" to "已逾期", "DONE" to "已完成", "CANCELLED" to "已取消", "ALL" to "全部").forEach { (value, label) ->
                StatusFilter(status == value, label,
                    events.any { (value == "ALL" || eventBucket(it, now) == value) && eventReadMarker(it, now).unread(seen) }) { status = value }
            }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("全部") + events.map { it.category }.distinct()).forEach { name -> FilterChip(category == name, { category = name }, label = { Text(name) }) }
        } }
        if (filtered.isEmpty()) item { EmptyState("没有匹配的事项", "点击右下角添加，或导入通知生成事项。", Icons.AutoMirrored.Filled.EventNote) }
        var lastDate = ""
        filtered.forEach { e ->
            val group = e.anchor?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)) } ?: "待补充时间"
            if (group != lastDate) { item(key = "group-${e.id}") { Text(group, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }; lastDate = group }
            item(key = e.id) { EventCard(e) { onEvent(e.id) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(raw: List<RawMessage>, suggestions: List<Suggestion>, onConfirm: (String) -> Unit, onDismiss: (String) -> Unit,
    onDelete: (String) -> Unit, onPaste: () -> Unit, onRetry: (String) -> Unit = {}, onCloudReview: (String) -> Unit = {},
    onComplete: (String) -> Unit = {}, onIgnoreRaw: (String) -> Unit = {}, onManual: (String) -> Unit = {},
    jobs: List<MessageJob> = emptyList(), seen: Map<String, String> = emptyMap(), onRead: (List<ReadMarker>) -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf("PENDING") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val filtered = raw.filter { filter == "ALL" || it.state == filter }
    val markers = remember(raw, suggestions) { raw.associate { it.id to rawReadMarker(it, suggestions) } }
    val displayed = filtered.mapNotNull { markers[it.id] }
    LaunchedEffect(displayed) { onRead(displayed) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("先核对，再进入你的日程。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("PENDING" to "待确认", "CONFIRMED" to "已确认", "IGNORED" to "已忽略", "ALL" to "全部").forEach { (value, label) ->
                StatusFilter(filter == value, label,
                    raw.any { (value == "ALL" || it.state == value) && markers[it.id]?.unread(seen) == true }) { filter = value }
            }
        } }
        if (filtered.isEmpty()) item { EmptyState("通知箱空空的", "粘贴通知，或在设置中启用应用通知接入。", Icons.Default.Inbox) { Button(onPaste) { Text("导入文本") } } }
        items(filtered, key = { it.id }) { r ->
            val drafts = suggestions.filter { it.rawId == r.id }
            val job = jobs.find { it.rawId == r.id }
            val processing = job?.state == "PROCESSING"
            val queued = job?.state == "QUEUED"
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${r.source} · ${formatTime(r.receivedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(r.title.ifBlank { "原始通知" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (expanded == r.id) {
                        SelectionContainer { Text(r.body, overflow = TextOverflow.Clip, style = MaterialTheme.typography.bodyMedium) }
                        Text("已采集正文 ${r.body.length} 字", style = MaterialTheme.typography.labelSmall)
                    } else Text(r.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (NoticeSafety.mayBeIncomplete(r.body)) Text(NoticeSafety.INCOMPLETE_HINT,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    drafts.forEach { suggestion ->
                        HorizontalDivider()
                        Text(suggestion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${suggestion.category} · ${if (suggestion.kind == "DEADLINE") "任务" else "日程"} · ${typeLabel(suggestion.type)}", style = MaterialTheme.typography.labelMedium)
                        Text(draftTime(suggestion.draft()), style = MaterialTheme.typography.bodyMedium)
                        suggestion.subtasks.forEach { task -> Text("${if (task.optional) "选做 · " else ""}${task.title} · ${fieldTime(task.dueAt, task.due)}", style = MaterialTheme.typography.bodySmall) }
                        if (suggestion.issues.isNotBlank()) Text(suggestion.issues, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onConfirm(suggestion.id) }) { Text("核对确认") }
                            TextButton(onClick = { onDismiss(suggestion.id) }) { Text("忽略此项") }
                        }
                    }
                    if (r.info.isNotBlank()) Text(r.info, style = MaterialTheme.typography.bodySmall)
                    if (job?.state == "RETRY") Text("下次自动重试：${formatTime(job.nextAttemptAt)}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (drafts.isEmpty() && r.state == "PENDING") Button(onClick = { onRetry(r.id) }, enabled = !processing && !queued) {
                            Text(if (processing) "识别中…" else if (queued) "等待处理" else "重试识别")
                        }
                        if (drafts.isEmpty() && r.state in setOf("PENDING", "IGNORED")) TextButton(onClick = { onCloudReview(r.id) }, enabled = !processing) { Text("送云端复核") }
                        TextButton(onClick = { expanded = if (expanded == r.id) null else r.id }) { Text(if (expanded == r.id) "收起原文" else "查看原文") }
                        if (r.state != "CONFIRMED") TextButton(onClick = { onComplete(r.id) }, enabled = !processing) { Text("补全正文") }
                        if (drafts.isEmpty() && r.state != "CONFIRMED") TextButton(onClick = { onManual(r.id) }) { Text("手动整理") }
                        if (drafts.isEmpty() && r.state == "PENDING") TextButton(onClick = { onIgnoreRaw(r.id) }) { Text("忽略通知") }
                        TextButton(onClick = { deleteId = r.id }) { Text("删除") }
                    }
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除原始通知？") },
        text = { Text("这条原文及其来源关联将被删除，已经确认的事项会保留。") },
        confirmButton = { TextButton(onClick = { onDelete(deleteId!!); deleteId = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("保留") } })
}

@Composable
fun ConflictScreen(conflicts: List<TimeConflict>, events: List<Event>, onEvent: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("只比较开始和结束均明确的进行中日程。时长未知的事项无法完整检查冲突。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (conflicts.isEmpty()) item { EmptyState("暂无已确认的时间冲突", "时间完整的日程没有重叠；时间未完整的事项仍需核对。", Icons.Default.CheckCircle) }
        items(conflicts) { c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("重叠 ${((c.endAt - c.startAt) / 60000)} 分钟", fontWeight = FontWeight.Bold)
                    Text("${formatTime(c.startAt)} — ${formatTime(c.endAt)}", style = MaterialTheme.typography.bodyMedium)
                    listOf(c.firstId, c.secondId).forEach { id -> events.find { it.id == id }?.let { e -> TextButton(onClick = { onEvent(id) }) { Text("${e.title} · 调整安排") } } }
                }
            }
        }
    }
}
