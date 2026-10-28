package cn.yiji.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiji.app.data.*
import cn.yiji.app.platform.IgnoredCleanup
import kotlinx.coroutines.launch

@Composable
fun IgnoredScreen(repository: Repository, snackbar: SnackbarHostState) {
    val items by repository.ignoredItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<IgnoredItem?>(null) }
    var fullText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var retention by remember { mutableIntStateOf(repository.preferences.ignoredRetentionDays) }
    var custom by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("") }
    fun saveRetention(days: Int) {
        repository.preferences.ignoredRetentionDays = days
        retention = days
        IgnoredCleanup.sync(context)
    }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { action() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作未完成，请重试") }
            finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SettingsSection("自动清理") {
            Text(if (retention == 0) "当前不自动清理" else "保留 $retention 天，每天检查并清理到期记录")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 3, 7, 30).forEach { days ->
                    FilterChip(retention == days, onClick = { saveRetention(days) }, label = { Text(if (days == 0) "不自动清理" else "$days 天") })
                }
                FilterChip(retention !in listOf(0, 1, 3, 7, 30), onClick = {
                    customDays = retention.takeIf { it > 0 }?.toString().orEmpty(); custom = true
                }, label = { Text("自定义") })
            }
            Text("只清理忽略区中的恢复副本，不删除正常待办。到期清理后无法恢复；后台检查时间由系统安排，启动应用时也会检查。",
                style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("手动忽略或删除的事项、通知先保存在这里，不参与待办计数。恢复事项时会重排未来提醒，已过期的提醒不会补发。") }
        if (items.isEmpty()) item { Text("忽略区为空") }
        items(items, key = { it.id }) { item ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title.ifBlank { "未命名通知" }, style = MaterialTheme.typography.titleMedium)
                    Text("${item.source} · ${formatTime(item.ignoredAt)}", style = MaterialTheme.typography.labelMedium)
                    if (item.preview.isNotBlank()) Text(item.preview, maxLines = 4, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { work { repository.restoreIgnored(item.id); snackbar.showSnackbar("已恢复；请在事项或通知箱查看") } }, enabled = !busy) { Text("恢复") }
                        TextButton(onClick = { work {
                            val snapshot = repository.dao.ignored(item.id) ?: error("记录已不存在")
                            val backup = BackupCodec.decode(snapshot.payload)
                            fullText = (backup.raw.map { it.title + "\n" + it.body } +
                                backup.events.map { it.title + "\n" + it.note }).distinct().joinToString("\n\n")
                        } }, enabled = !busy) { Text("查看全文") }
                        TextButton(onClick = { deleting = item }, enabled = !busy) { Text("彻底删除") }
                    }
                }
            }
        }
    }
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text("自定义保留天数") },
        text = { OutlinedTextField(customDays, { customDays = it.filter(Char::isDigit).take(4) },
            label = { Text("保留天数（1—3650）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
        confirmButton = { TextButton(onClick = { saveRetention(customDays.toInt()); custom = false },
            enabled = (customDays.toIntOrNull() ?: 0) in 1..3650) { Text("保存") } },
        dismissButton = { TextButton(onClick = { custom = false }) { Text("取消") } })
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("彻底删除这条记录？") },
        text = { Text("将删除忽略区中的恢复副本，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { deleting = null; work { repository.purgeIgnored(item.id) } }) { Text("彻底删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } }) }
    fullText?.let { text -> AlertDialog(onDismissRequest = { fullText = null }, title = { Text("已保存的原文") },
        text = { SelectionContainer { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(text) } } },
        confirmButton = { TextButton(onClick = { fullText = null }) { Text("关闭") } }) }
}
