package cn.yiji.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiji.app.BuildConfig
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.Repository
import cn.yiji.app.data.readLimited
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.platform.YijiNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(repository: Repository, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val prefs = repository.preferences
    val monitor = (context.applicationContext as YijiApplication).notificationMonitor
    val notificationStatus by monitor.status.collectAsStateWithLifecycle()
    val recordTime = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var collecting by remember { mutableStateOf(prefs.collecting) }
    var packages by remember { mutableStateOf(prefs.packages) }
    var reminder by remember { mutableIntStateOf(prefs.defaultReminder) }
    var sourceDialog by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showAllRecords by remember { mutableStateOf(false) }
    val authorized = remember(revision) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
    val notificationAllowed = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAllowed = remember(revision) { ReminderScheduler(context).exactAllowed() }
    LaunchedEffect(authorized, collecting, packages, revision) {
        if (authorized && collecting && packages.isNotEmpty()) YijiNotificationListener.reconnectAndRecover(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            revision++; scope.launch { repository.reschedule() }
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun work(action: suspend () -> String) { if (!busy) scope.launch {
        busy = true
        try { snackbar.showSnackbar(action()) } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作失败，请重试") }
        finally { busy = false }
    } }
    fun openSettings(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { scope.launch { snackbar.showSnackbar("无法打开此设置，请在系统设置中手动开启") } } }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work {
            val data = repository.export()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件") }
            "备份已保存"
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val candidate = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use {
                val bytes = it.readLimited(10 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            } ?: error("无法读取文件") }
            cn.yiji.app.data.BackupCodec.decode(candidate)
            restoreText = candidate
            "已读取备份，请确认恢复"
        }
    }
    val apps = remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
        .distinctBy { it.first }.sortedBy { it.second } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { SettingsSection("通知接入") {
            Text("授权后仅接收选定应用发布的系统通知。完整聊天记录可通过粘贴或分享文本导入。", style = MaterialTheme.typography.bodyMedium)
            if (!authorized) OutlinedButton(onClick = { consent = true }, modifier = Modifier.fillMaxWidth()) { Text("授权读取系统通知") }
            else Text("通知读取权限已开启", color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("采集选定应用")
                    Text(when {
                        !collecting -> "采集已暂停"
                        !authorized -> "请开启通知读取权限"
                        packages.isEmpty() -> "请先选择来源应用"
                        notificationStatus.connected -> "通知监听已连接"
                        else -> "通知监听未连接，请尝试重新连接"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                Switch(collecting, { value -> if (value && !authorized) consent = true else { collecting = value; prefs.collecting = value } })
            }
            OutlinedButton(onClick = { sourceDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("选择来源 · 已选 ${packages.size} 个应用") }
            OutlinedButton(onClick = {
                YijiNotificationListener.reconnectAndRecover(context)
                scope.launch { snackbar.showSnackbar("已请求连接或补采，请查看处理记录") }
            }, enabled = authorized && collecting && packages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("重新连接并补采通知栏") }
            Text("只补采通知栏中仍保留的文字。相同来源应用、标题与正文在 24 小时内自动去重。", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsSection("通知处理记录") {
            Text("记录所选来源通知的处理结果，最多保留 20 条，不包含聊天正文。", style = MaterialTheme.typography.bodySmall)
            if (notificationStatus.records.isEmpty()) Text("尚无记录。开启采集后，请让来源应用收到一条新通知。", style = MaterialTheme.typography.bodyMedium)
            notificationStatus.records.take(if (showAllRecords) 20 else 5).forEach { record ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${recordTime.format(Instant.ofEpochMilli(record.at))} · ${record.source}", style = MaterialTheme.typography.labelMedium)
                    Text(record.result, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                if (notificationStatus.records.size > 5) TextButton(onClick = { showAllRecords = !showAllRecords }) { Text(if (showAllRecords) "收起" else "查看全部记录") }
                if (notificationStatus.records.isNotEmpty()) TextButton(onClick = { monitor.clear() }) { Text("清空记录") }
            }
        } }
        item { SettingsSection("事项提醒") {
            Text(if (notificationAllowed) "提醒通知已允许" else "尚未允许提醒通知", color = if (notificationAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (!notificationAllowed) OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("允许提醒通知") }
            Text(if (exactAllowed) "精确提醒已允许" else "当前使用系统普通提醒，可能有延迟", style = MaterialTheme.typography.bodySmall)
            if (!exactAllowed && Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("允许精确提醒") }
            Text("新增事项默认提前提醒", fontWeight = FontWeight.Bold)
            ReminderChips(reminder) { reminder = it; prefs.defaultReminder = it }
        } }
        item { SettingsSection("本地模型") {
            Text("TinyBERT 4L-312D · 中文日程模型 v2", fontWeight = FontWeight.Bold)
            Text("模型随安装包内置，在手机上完成通知筛选、事项分类和时间角色识别，无需联网或配置服务。首次识别需要加载模型。", style = MaterialTheme.typography.bodyMedium)
            Text("当前模型为初始微调版本，请核对识别结果。具体时间尚待通知或需预约的事项会保留为草稿。", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsSection("数据与备份") {
            Text("事项、原文和历史保存在本机。备份包含通知内容，请保存到自己的位置。权限和应用选择不包含在备份内。", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { export.launch("yiji-backup-${java.time.LocalDate.now()}.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出备份") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
            TextButton(onClick = { clearDialog = true }, enabled = !busy) { Text("清空本地数据", color = MaterialTheme.colorScheme.error) }
        } }
        item { Text("忆迹 v${BuildConfig.VERSION_NAME}\n多源通知语义关联与时序冲突检测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("允许忆迹接收通知") }, text = { Text("系统通知可能包含私人信息。忆迹只处理你选择的来源；TinyBERT 模型在手机上运行，通知内容保存在本机。你随时可以暂停采集或撤销系统授权。") },
        confirmButton = { TextButton(onClick = { consent = false; openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("前往系统授权") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("稍后") } })
    if (sourceDialog) AlertDialog(onDismissRequest = { sourceDialog = false }, title = { Text("选择通知来源") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(apps, key = { it.first }) { (pkg, label) -> Row(Modifier.fillMaxWidth().clickable {
            packages = if (pkg in packages) packages - pkg else packages + pkg; prefs.packages = packages
        }, verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in packages, onCheckedChange = { checked -> packages = if (checked) packages + pkg else packages - pkg; prefs.packages = packages }); Text(label) } } }
    }, confirmButton = { TextButton(onClick = { sourceDialog = false }) { Text("完成") } })
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清空所有本地数据？") }, text = { Text("将删除所有事项、通知原文和变更历史，并撤销提醒。建议先导出备份。") },
        confirmButton = { TextButton(onClick = { clearDialog = false; work { repository.clear(); monitor.clear(); "本地数据已清空" } }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("保留") } })
    if (restoreText != null) AlertDialog(onDismissRequest = { restoreText = null }, title = { Text("恢复备份？") }, text = { Text("备份会替换当前的事项、通知和历史，并重新安排提醒。") },
        confirmButton = { TextButton(onClick = { val data = restoreText!!; restoreText = null; work { repository.restore(data); "备份已恢复" } }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreText = null }) { Text("取消") } })
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content()
        }
    }
}
