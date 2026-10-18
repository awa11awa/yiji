package cn.yiji.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiji.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ApiSettingsEntry(repository: Repository, onOpen: () -> Unit) {
    val state by repository.apiProfiles.state.collectAsStateWithLifecycle()
    Card(onClick = onOpen, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.AutoAwesome, null)
                Text("API 与模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(state.selected?.name ?: "选择模型服务", fontWeight = FontWeight.SemiBold)
            Text(if (state.enabled) "云端识别已开启 · TinyBERT 先在本机筛选" else "填写自己的 API Key，开启日程候选提取", style = MaterialTheme.typography.bodyMedium)
            Text("管理多套配置、切换模型、查看免费 API 获取教程", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen) { Text("管理 API 配置 →") }
        }
    }
}

@Composable
fun ApiProfilesScreen(repository: Repository, snackbar: SnackbarHostState, onGuide: () -> Unit) {
    val store = repository.apiProfiles
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    var consent by remember { mutableStateOf<ApiProfile?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    fun work(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作未完成，请重试") }
    } }
    fun activate(profile: ApiProfile) {
        work {
            if (store.key(profile.id).isBlank()) {
                editing = profile
                snackbar.showSnackbar("请先为这套配置填写 API Key")
            } else consent = profile
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("让重要消息，交给你选择的模型", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("TinyBERT 在本机区分聊天与日程候选；候选消息再由云端提取。原文先保存，结果经你核对后才加入事项。",
                modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("云端识别", fontWeight = FontWeight.Bold)
                            Text(if (state.enabled) "正在使用 " + state.selected?.model else "已关闭 · 候选原文保留待提取", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.enabled, { value -> if (value) state.selected?.let(::activate) else work { store.enable(false) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("带上相关上下文")
                            Text("最多4条同来源、同标题的近期日程候选，以及相关已确认事项。可关闭。", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.includeContext, { work { store.includeContext(it) } })
                    }
                    OutlinedButton(onClick = onGuide, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("获取免费 API · 操作教程")
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的配置", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { editing = ApiProfile() }) { Icon(Icons.Default.Add, null); Text("添加") }
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val selected = profile.id == state.selectedId
            Card(shape = RoundedCornerShape(20.dp), border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        if (selected) SuggestionChip(onClick = {}, label = { Text(if (state.enabled) "使用中" else "已选") })
                    }
                    Text(profile.model, style = MaterialTheme.typography.bodyMedium)
                    Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!selected || !state.enabled) Button(onClick = {
                            if (state.enabled) activate(profile) else work { store.select(profile.id); snackbar.showSnackbar("已选择配置，开启云端识别后生效") }
                        }) { Text(if (selected) "开启识别" else "切换") }
                        OutlinedButton(onClick = { editing = profile }) { Text("编辑") }
                        TextButton(enabled = testing == null, onClick = {
                            testing = profile.id
                            work {
                                try { result = profile.name + "：" + CloudExtractionClient().test(profile, store.key(profile.id)) }
                                finally { testing = null }
                            }
                        }) { Text(if (testing == profile.id) "测试中…" else "测试连接") }
                        IconButton(onClick = { deleting = profile }) { Icon(Icons.Default.DeleteOutline, "删除配置") }
                    }
                }
            }
        }
        if (result.isNotBlank()) item { Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            OutlinedButton(onClick = { work { val count = repository.resumeWaiting(); snackbar.showSnackbar("已重新排队 " + count + " 条待提取消息") } },
                modifier = Modifier.fillMaxWidth(), enabled = state.enabled) { Text("继续处理待提取消息") }
            Text("密钥加密保存在本机，不进入数据备份。使用你自己的服务账户额度；模型价格和限额以服务商当前说明为准。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { profile -> ApiProfileEditor(profile, store, onDismiss = { editing = null }, onSaved = {
        editing = null
        work { snackbar.showSnackbar("配置已保存") }
    }) }
    consent?.let { profile -> AlertDialog(onDismissRequest = { consent = null }, title = { Text("使用这套云端配置？") },
        text = { Text("将日程候选通知的标题、正文和接收时间发送到：\n" + profile.baseUrl +
            "\n\n模型：" + profile.model + (if (state.includeContext) "\n同时发送有限的相关消息与事项，帮助理解变更。分组依据是通知标题，可能存在歧义，请核对关联结果。" else "\n不发送历史消息与已有事项。") +
            "\n\n开启后继续处理已保存的待提取候选。所有新建、更新、取消结果仍需你确认。") },
        confirmButton = { TextButton(onClick = {
            consent = null
            work { store.select(profile.id); store.enable(true); repository.resumeWaiting(); snackbar.showSnackbar("云端识别已开启") }
        }) { Text("同意并开启") } },
        dismissButton = { TextButton(onClick = { consent = null }) { Text("取消") } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这套 API 配置？") },
        text = { Text("将删除“" + profile.name + "”及其本机密钥。事项和通知原文保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; work { store.delete(profile.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
fun ApiProfileEditor(original: ApiProfile, store: ApiProfileStore, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val existed = store.current.profiles.any { it.id == original.id }
    var name by remember { mutableStateOf(original.name) }
    var url by remember { mutableStateOf(original.baseUrl) }
    var model by remember { mutableStateOf(original.model) }
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var chooseModel by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun draft() = ApiProfile(original.id, name.trim(), url.trim().trimEnd('/'), model.trim())
    fun credentials(): String {
        require(key.isNotBlank() || (existed && url.trim().trimEnd('/') == original.baseUrl.trimEnd('/'))) { "更换服务地址后，请重新填写 API Key" }
        return key.trim().ifBlank { store.key(original.id) }
    }
    fun work(action: suspend () -> Unit) { if (!busy) scope.launch {
        busy = true
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
        finally { busy = false }
    } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 680.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (existed) "编辑 API 配置" else "添加 API 配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("智谱免费" to ApiProfile.glm, "硅基流动" to ApiProfile.silicon).forEach { (label, preset) ->
                        AssistChip(onClick = { name = preset.name; url = preset.baseUrl; model = preset.model; key = ""; message = ""; models = emptyList() }, label = { Text(label) })
                    }
                    AssistChip(onClick = { name = "自定义服务"; url = ""; model = ""; key = ""; message = ""; models = emptyList() }, label = { Text("自定义") })
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("API 基础地址 · HTTPS") }, supportingText = { Text("支持 OpenAI 兼容的 Chat Completions 接口") }, singleLine = true)
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") },
                    placeholder = { Text(if (existed) "留空保留已保存的密钥" else "粘贴你自己的 API Key") }, singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示或隐藏密钥") } })
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { work {
                        models = CloudExtractionClient().models(draft(), credentials())
                        if (models.isEmpty()) message = "服务未返回模型，请手动填写" else chooseModel = true
                    } }) { Text("获取模型列表") }
                    TextButton(enabled = !busy, onClick = { work { message = CloudExtractionClient().test(draft(), credentials()) } }) { Text("测试连接") }
                }
                Text("模型列表可能包含收费型号。使用免费服务时，请核对模型完整名称及服务商控制台价格。", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                    Button(enabled = !busy, onClick = { work {
                        val profile = draft()
                        credentials().also { require(it.isNotBlank()) { "请填写 API Key" } }
                        profile.validate()
                        if (store.current.selectedId == profile.id && url.trim().trimEnd('/') != original.baseUrl.trimEnd('/')) store.enable(false)
                        store.save(profile, key.trim().takeIf { it.isNotBlank() })
                        onSaved()
                    } }) { Text("保存配置") }
                }
            }
        }
    }
    if (chooseModel) AlertDialog(onDismissRequest = { chooseModel = false }, title = { Text("选择模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索模型") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(models.filter { it.contains(search, true) }) { id -> TextButton(onClick = { model = id; chooseModel = false }, modifier = Modifier.fillMaxWidth()) { Text(id) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseModel = false }) { Text("关闭") } })
}

@Composable
fun ApiGuideScreen() {
    val context = LocalContext.current
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("用自己的 Key，接入云端识别", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("按下面步骤获取密钥，再回到 API 与模型添加配置。教程可离线阅读，打开服务商网站需要联网。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        item { GuideCard("01 · 智谱 GLM-4.7-Flash（默认）", listOf(
            "打开智谱开放平台，注册或登录自己的账户，按页面提示完成必要验证。",
            "进入控制台的 API Key／API 密钥管理，新建一个密钥并复制。",
            "回到忆迹，编辑默认智谱配置，把密钥粘贴到 API Key。",
            "保留默认模型 glm-4.7-flash，点击测试连接，然后保存。",
            "开启云端识别并阅读发送内容说明，即可处理日程候选。"
        ), "官方将 GLM-4.7-Flash 列为免费文本模型。免费不代表没有并发和速率限制；不要选错收费型号。",
            { open("https://bigmodel.cn/") }, { open("https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash") }) }
        item { GuideCard("02 · 硅基流动", listOf(
            "打开硅基流动控制台，注册或登录，按平台要求完成验证。",
            "在 API 密钥页面创建密钥。",
            "在忆迹添加配置，点击硅基流动预设并粘贴密钥。",
            "在控制台确认所选模型输入、输出价格均为免费；模型名称完整复制。",
            "测试连接并保存，然后切换到该配置。"
        ), "预设为 Qwen/Qwen3.5-4B。模型价格与可用范围可能调整，以控制台当前显示为准。",
            { open("https://cloud.siliconflow.cn/") }, { open("https://docs.siliconflow.cn/docs/userguide/quickstart") }) }
        item { GuideCard("03 · 自定义服务", listOf(
            "确认服务支持 OpenAI 兼容的 Chat Completions 文本接口及 JSON 输出。",
            "复制服务商提供的 HTTPS 基础地址、模型完整名称和自己的 API Key。",
            "添加自定义配置，分别填写名称、地址、密钥与模型。",
            "可以获取模型列表；若平台不提供列表，直接手动填写模型名称。",
            "测试连接并保存，再开启或切换配置。"
        ), "普通聊天会员、Coding Plan 与通用 API 的密钥和计费方式可能不同，请使用服务商明确支持通用 API 调用的密钥。", null, null) }
        item {
            Text("常见问题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("401／403：核对密钥、模型权限、账户验证和剩余额度。\n404：核对基础地址与模型名称。\n429：服务限流，忆迹会保留原文并稍后重试。\n无法获取模型列表：可以手动填写。\n未配置或断网：候选原文留在通知箱，配置好后点击继续处理。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            Text("教程核对日期：2026-10-18。服务商免费政策可能变化。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GuideCard(title: String, steps: List<String>, note: String, onPlatform: (() -> Unit)?, onDocs: (() -> Unit)?) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            steps.forEachIndexed { index, text ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((index + 1).toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                onPlatform?.let { OutlinedButton(onClick = it) { Text("打开平台") } }
                onDocs?.let { TextButton(onClick = it) { Text("官方文档") } }
            }
        }
    }
}
