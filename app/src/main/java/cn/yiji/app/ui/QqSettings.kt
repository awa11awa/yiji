package cn.yiji.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.Repository
import cn.yiji.app.qq.*
import dev.yiji.qqlocalprobe.AccountStore
import dev.yiji.qqlocalprobe.LoginChallengeActivity
import dev.yiji.qqlocalprobe.QqDiagnostics
import kotlinx.coroutines.*

private data class QqUiSnapshot(val status: QqStatus, val contacts: List<QqContact>, val hasSession: Boolean)

@Composable
fun QqSettingsScreen(repository: Repository, snackbar: SnackbarHostState) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var scene by remember { mutableStateOf("GROUP") }
    var search by remember { mutableStateOf("") }
    var loginDialog by remember { mutableStateOf(false) }
    var qq by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var logoutDialog by remember { mutableStateOf(false) }
    val empty=remember { QqUiSnapshot(QqStatus(false,false,"","QQ 接收端未启用","",0,0,0,""),emptyList(),false) }
    val snapshot by produceState(empty,lifecycle,revision) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                value=withContext(Dispatchers.IO) { QqStore(context).use { store ->
                    val status=store.snapshot();QqUiSnapshot(status,store.contacts(status.account),AccountStore(context).exists())
                } }
                delay(1500)
            }
        }
    }
    val status=snapshot.status
    fun inform(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun command(action: String) { runCatching { context.startService(Intent(context,QqCaptureService::class.java).setAction(action)) }.onFailure { inform("操作未完成，请重试") } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start(resume: Boolean) {
        val uin=qq.toLongOrNull()
        if(!resume && (uin==null || uin<=0 || password.isBlank())) { inform("请输入正确的 QQ 号和密码");return }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        QqStore(context).use { it.directMode=true }
        repository.preferences.packages=repository.preferences.packages-"com.tencent.mobileqq"
        (context.applicationContext as YijiApplication).collectionSupervisor.refresh(allowForegroundStart=true)
        val intent=Intent(context,QqCaptureService::class.java).setAction(QqCaptureService.ACTION_LOGIN).putExtra("resume",resume)
        if(!resume) intent.putExtra("uin",uin!!).putExtra("password",password)
        runCatching { ContextCompat.startForegroundService(context,intent) }
            .onSuccess { loginDialog=false;password="" }
            .onFailure { inform("无法启动接收端，请重新打开忆迹后重试") }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(QqDiagnostics.read(context).toByteArray(Charsets.UTF_8)) } ?: error("无法写入")
            } }.onSuccess { snackbar.showSnackbar("QQ 诊断日志已导出") }.onFailure { snackbar.showSnackbar("日志导出失败") }
        }
    }
    val visible=remember(snapshot.contacts,scene,search) { snapshot.contacts.filter { it.scene==scene && (search.isBlank() || it.name.contains(search,true) || it.uin.toString().contains(search)) } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { SettingsSection("QQ 接收端") {
            Text(status.title,style=MaterialTheme.typography.titleMedium)
            if(status.account.isNotBlank()) Text("账号 ${status.account} · 平板接收端",style=MaterialTheme.typography.bodySmall)
            if(status.detail.isNotBlank()) Text(status.detail,style=MaterialTheme.typography.bodyMedium)
            Text("先停止独立测试版的接收，再在这里登录。目前适配 QQ 9.3.75，手机上需保留官方 QQ。",style=MaterialTheme.typography.bodySmall)
            if(status.challengeKind.isNotBlank()) Button(onClick={ context.startActivity(Intent(context,LoginChallengeActivity::class.java)) },modifier=Modifier.fillMaxWidth()) { Text("继续 QQ 验证") }
            if(status.enabled) OutlinedButton(onClick={ command(QqCaptureService.ACTION_PAUSE) },modifier=Modifier.fillMaxWidth()) { Text("暂停 QQ 采集") }
            else {
                if(snapshot.hasSession) Button(onClick={ start(true) },modifier=Modifier.fillMaxWidth()) { Text("恢复 QQ 采集") }
                OutlinedButton(onClick={ qq=status.account;loginDialog=true },modifier=Modifier.fillMaxWidth()) { Text(if(snapshot.hasSession) "重新登录 QQ" else "登录 QQ") }
            }
            if(snapshot.hasSession || status.directMode) TextButton(onClick={ logoutDialog=true }) { Text("关闭 QQ 接收端") }
            Text("密码仅用于本次登录。登录票据在本机加密保存；关闭接收端后可重新选择系统通知作为 QQ 来源。",style=MaterialTheme.typography.bodySmall)
        } }
        item { SettingsSection("选择采集会话 · ${status.selectedCount} 个") {
            Text("默认不选中任何聊天。从勾选时开始接收，取消勾选后停止采集。不会导入勾选之前的全部历史。",style=MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(scene=="GROUP",{scene="GROUP"},label={Text("群聊")})
                FilterChip(scene=="FRIEND",{scene="FRIEND"},label={Text("联系人")})
                Spacer(Modifier.weight(1f))
                TextButton(onClick={ command(QqCaptureService.ACTION_REFRESH) },enabled=status.enabled) { Text("刷新会话") }
            }
            OutlinedTextField(search,{search=it},label={Text("搜索名称或号码")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(snapshot.contacts.isEmpty()) Text(if(status.enabled) "会话列表正在读取，稍后可点击刷新。" else "登录后读取会话列表。",style=MaterialTheme.typography.bodySmall)
            else if(visible.isEmpty()) Text("没有匹配的会话",style=MaterialTheme.typography.bodySmall)
        } }
        items(visible,key={"${it.account}:${it.scene}:${it.uin}"}) { c ->
            fun select(value: Boolean) { scope.launch {
                withContext(Dispatchers.IO) { QqStore(context).use { it.select(c,value) } }
                revision++
                if(status.enabled) command(QqCaptureService.ACTION_SELECTION)
            } }
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().clickable { select(!c.selected) }.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(c.name,style=MaterialTheme.typography.bodyLarge);Text(c.uin.toString(),style=MaterialTheme.typography.bodySmall) }
                    Checkbox(c.selected,::select)
                }
            }
        }
        item { SettingsSection("后台运行与轻量采集") {
            Text("退出页面或划掉忆迹的最近任务后，QQ 服务仍可继续接收。请允许忆迹后台运行；部分手机还需允许自启动、锁定后台。",style=MaterialTheme.typography.bodyMedium)
            Text("强行停止应用会中断服务，需要再次打开忆迹。系统限制或网络中断期间的消息会在恢复后尝试补采，补采范围受 QQ 接口保留情况影响。",style=MaterialTheme.typography.bodySmall)
            val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            OutlinedButton(onClick={
                val intent=if(unrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }.onFailure { inform("请在系统电池设置中允许忆迹后台运行") }
            },modifier=Modifier.fillMaxWidth()) { Text(if(unrestricted) "查看后台电池设置" else "允许忆迹持续后台运行") }
            Text("QQ 服务按消息触发保存，不常驻加载识别模型，不自动下载媒体，不持续持有唤醒锁。诊断日志会自动轮换。",style=MaterialTheme.typography.bodySmall)
            Text("已接收 ${status.stagedCount} 条 · 等待导入 ${status.pendingCount} 条",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={export.launch("yiji-qq-diagnostics.txt")},modifier=Modifier.fillMaxWidth()) { Text("导出 QQ 诊断日志") }
        } }
    }
    if(loginDialog) AlertDialog(onDismissRequest={loginDialog=false;password=""},title={Text("登录 QQ 接收端")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("使用平板身份接收；登录后选择需要采集的人或群聊。")
            OutlinedTextField(qq,{qq=it.filter(Char::isDigit)},label={Text("QQ 号")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            OutlinedTextField(password,{password=it},label={Text("QQ 密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        }
    },confirmButton={TextButton(onClick={start(false)}) {Text("登录")}},dismissButton={TextButton(onClick={loginDialog=false;password=""}) {Text("取消")}})
    if(logoutDialog) AlertDialog(onDismissRequest={logoutDialog=false},title={Text("关闭 QQ 接收端？")},text={Text("将清除本机 QQ 登录票据、会话选择和尚未导入的临时队列。忆迹中已保存的消息和事项继续保留。")},
        confirmButton={TextButton(onClick={command(QqCaptureService.ACTION_LOGOUT);logoutDialog=false}) {Text("关闭接收端")}},dismissButton={TextButton(onClick={logoutDialog=false}) {Text("取消")}})
}
