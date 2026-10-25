package dev.yiji.qqlocalprobe

import android.content.Context
import cn.yiji.app.qq.QqEnvelope
import cn.yiji.app.qq.QqContact
import cn.yiji.app.qq.QqIngress
import cn.yiji.app.qq.QqStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.ntqqrev.acidify.*
import org.ntqqrev.acidify.common.SignResult
import org.ntqqrev.acidify.common.UnsafeAcidifyApi
import org.ntqqrev.acidify.common.android.*
import org.ntqqrev.acidify.event.*
import org.ntqqrev.acidify.exception.UnstableNetworkException
import org.ntqqrev.acidify.logging.*
import org.ntqqrev.acidify.message.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the protocol and durable spool. Inference runs in the main process. */
class QqSession(private val context: Context, private val store: QqStore,
    private val interaction: LoginInteraction, private val uin: Long,
    private var password: String, private val resume: Boolean) {
    private val contactRequests=Channel<Unit>(Channel.CONFLATED)
    private val historyRequests=Channel<Unit>(Channel.CONFLATED)
    private val requestedPeers=ConcurrentHashMap.newKeySet<Pair<String,Long>>()
    private val syncAll=AtomicBoolean(true)
    fun refresh() { contactRequests.trySend(Unit) }
    fun selectionChanged() { syncAll.set(true);historyRequests.trySend(Unit) }

    @OptIn(ExperimentalStdlibApi::class, UnsafeAcidifyApi::class)
    suspend fun run(): Unit = coroutineScope {
        interaction.update("准备本地签名","使用已安装 QQ 的原生库")
        val accounts=AccountStore(context)
        val identity=DeviceIdentity(context)
        val session=if(resume) AndroidSessionStore.fromJson(accounts.load()).also {
            require(it.wloginSigs.a2.isNotEmpty()) { "登录票据已失效，请重新登录" }
        } else AndroidSessionStore.empty(uin,password).also {
            it.guid=identity.guid.hexToByteArray();it.androidId=identity.androidId
            it.qimei=identity.qimei;it.deviceName=identity.name
        }
        val account=session.uin.toString();store.account=account
        val native=ProbeRunner(context,true).prepareLogin(session.uin,session.guid.toHexString())
        val signProvider=object: AndroidSignProvider {
            override suspend fun sign(uin: Long,cmd: String,buffer: ByteArray,guid: String,seq: Int,version: String,qua: String): SignResult {
                val result=native.sign(uin,cmd,buffer,seq);return SignResult(result[0],result[1],result[2])
            }
            override suspend fun energy(uin: Long,data: String,guid: String,ver: String,version: String,qua: String)=native.energy(uin,data,guid.hexToByteArray(),ver)
            override suspend fun getDebugXwid(uin: Long,data: String,guid: String,version: String,qua: String)=native.debugId(uin,data)
        }
        val logs=LogHandler { level,tag,message,error ->
            TraceLog.line(context,"[PROTOCOL-${level.name}] $tag ${if(tag.endsWith("PacketContext")) message else ""} ${error?.javaClass?.simpleName.orEmpty()}")
            if(error!=null) QqDiagnostics.error(context,"PROTOCOL",error)
        }
        val clientScope=CoroutineScope(SupervisorJob(coroutineContext[Job])+Dispatchers.IO)
        val client=AndroidBot(AndroidAppInfo.fromJson(native.appInfoJson()),session,signProvider,clientScope,LogLevel.WARN,logs)
        client.messageFilter={scene,peer -> store.selected(account,scene.name,peer)}
        client.refreshGroupMembersOnMessage=false
        val expansions=Channel<String>(Channel.UNLIMITED)
        val deliveries=Channel<Unit>(Channel.CONFLATED)
        val recent=LinkedHashSet<String>()
        val seenSequences=mutableMapOf<Pair<MessageScene,Long>,Long>()
        suspend fun save(m: BotIncomingMessage, source: String) {
            val contact=store.contact(account,m.scene.name,m.peerUin)?.takeIf { it.selected } ?: return
            if(m.timestamp < contact.since/1000) return
            val key=MessageIdentity.key(m.senderUin.toString(),m.messageUid.toString(),m.sequence.toString(),
                m.clientSequence.toString(),m.timestamp.toString(),m.random.toString(),m.packageIndex.toString(),m.divisionSequence.toString())
            val id=UUID.nameUUIDFromBytes("$account|${m.scene}|${m.peerUin}|$key".toByteArray(Charsets.UTF_8)).toString()
            synchronized(recent) { if(id in recent) return }
            val encoded=JsonObject(MessageCodec.encode(m,source)+buildJsonObject { put("account",account);put("conversationName",contact.name) })
            val envelope=QqEnvelope(id,account,m.scene.name,m.peerUin,encoded.toString())
            if(store.stage(envelope,!MessageCodec.needsExpansion(m))) {
                if(MessageCodec.needsExpansion(m)) expansions.send(envelope.id) else deliveries.trySend(Unit)
            }
            synchronized(recent) { recent.add(id);if(recent.size>4096) recent.remove(recent.first()) }
        }
        val events=launch(start=CoroutineStart.UNDISPATCHED) {
            client.eventFlow.collect { event -> when(event) {
                is AndroidSessionStoreUpdatedEvent -> accounts.save(event.sessionStore.toJson())
                is MessageReceiveEvent -> {
                    val m=event.message;save(m,"push")
                    val key=m.scene to m.peerUin;val last=seenSequences[key]
                    if(last==null || m.sequence>last) seenSequences[key]=m.sequence
                    if(last==null || m.sequence>last+1) {
                        requestedPeers.add(m.scene.name to m.peerUin);historyRequests.trySend(Unit)
                    }
                }
                is BotOfflineEvent -> error("QQ 接收端已离线，请重新连接")
            } }
        }
        val delivery=launch {
            for(signal in deliveries) { delay(300);QqIngress.signal(context) }
        }
        val enrich=launch {
            for(id in expansions) {
                val envelope=store.envelope(id) ?: continue
                if(!store.selected(envelope.account,envelope.scene,envelope.peer)) { store.acknowledge(envelope.id);continue }
                val encoded=Json.parseToJsonElement(envelope.payload).jsonObject
                val expanded=try { MessageCodec.expandEncoded(client,encoded) }
                catch(e: Exception) {
                    currentCoroutineContext().ensureActive();QqDiagnostics.error(context,"QQ-EXPAND",e)
                    JsonObject(encoded+buildJsonObject { put("contentState","expansion_failed");put("expansionError",e.javaClass.simpleName) })
                }
                store.complete(envelope.id,expanded.toString());deliveries.trySend(Unit)
            }
        }
        val channel=launch {
            while(isActive) {
                val request=runInterruptible { native.takeRequest() }
                val command=request[0] as String;val callback=request[2] as Long
                try {
                    val response=client.sendPacket(command,request[1] as ByteArray)
                    native.deliver(response.command,response.response,response.retCode,callback)
                } catch(e: Exception) {
                    currentCoroutineContext().ensureActive();QqDiagnostics.error(context,"QQ-CHANNEL",e)
                    native.deliver(command,byteArrayOf(),-1,callback)
                }
            }
        }
        var contacts: Job?=null;var history: Job?=null;var reconnect: Job?=null
        try {
            interaction.update(if(resume) "正在恢复 QQ 登录" else "正在登录 QQ","需要验证时请点击继续验证")
            suspend fun authenticate() {
                if(resume) client.online(preloadContacts=false)
                else client.login(onRequireCaptchaTicket={url -> runInterruptible { interaction.challenge("captcha",url,"请完成滑块验证") } },
                    onRequireSmsCode={country,phone,url -> runInterruptible { interaction.challenge("sms",url,"+$country $phone：输入短信验证码") } },preloadContacts=false)
            }
            withTimeout(6*60_000L) {
                try { authenticate() }
                catch(e: UnstableNetworkException) {
                    runInterruptible { interaction.challenge("device",e.manualVerifyUrl,"完成网页设备验证后继续") };authenticate()
                }
            }
            password="";session.password="";accounts.save(session.toJson())
            interaction.update("QQ 接收端在线","只采集勾选的会话；可退出界面")
            for(id in store.waitingIds(account)) expansions.send(id)
            deliveries.trySend(Unit)
            contacts=launch {
                for(signal in contactRequests) {
                    try {
                        val friends=client.fetchFriends().map { QqContact(account,"FRIEND",it.uin,it.uid,it.remark.ifBlank { it.nickname }.ifBlank { it.uin.toString() }) }
                        store.refresh(friends)
                        val groups=client.fetchGroups().map { QqContact(account,"GROUP",it.uin,name=it.remark.ifBlank { it.name }.ifBlank { it.uin.toString() }) }
                        store.refresh(groups)
                        interaction.update("QQ 接收端在线","会话列表已刷新，已选 ${store.selected(account).size} 个")
                    } catch(e: Exception) {
                        currentCoroutineContext().ensureActive();QqDiagnostics.error(context,"QQ-CONTACTS",e)
                        interaction.update("QQ 接收端在线","会话列表读取未完成，可点击刷新重试")
                    }
                }
            }
            history=launch {
                for(signal in historyRequests) {
                    val all=syncAll.getAndSet(false)
                    val peers=requestedPeers.toSet();requestedPeers.removeAll(peers)
                    for(c in store.selected(account).filter { all || (it.scene to it.uin) in peers }) {
                        try {
                            val newest=HistoryBackfill.run(c.since/1000,c.watermark,{start ->
                                when(c.scene) {
                                    "FRIEND" -> client.getFriendHistoryMessages(c.uin,30,start)
                                    "GROUP" -> client.getGroupHistoryMessages(c.uin,30,start)
                                    else -> error("不支持的会话类型")
                                }
                            }) { save(it,"history") }
                            store.checkpoint(c,newest)
                        } catch(e: Exception) { currentCoroutineContext().ensureActive();QqDiagnostics.error(context,"QQ-HISTORY",e) }
                    }
                }
            }
            reconnect=launch {
                var generation=client.connectionGeneration
                while(isActive) {
                    delay(60_000)
                    if(client.isLoggedIn && client.connectionGeneration!=generation) {
                        generation=client.connectionGeneration;syncAll.set(true);historyRequests.trySend(Unit)
                    }
                }
            }
            contactRequests.trySend(Unit);historyRequests.trySend(Unit)
            awaitCancellation()
        } finally {
            password="";session.password=""
            withContext(NonCancellable) {
                contacts?.cancelAndJoin();history?.cancelAndJoin();reconnect?.cancelAndJoin()
                channel.cancelAndJoin();enrich.cancelAndJoin();events.cancelAndJoin();delivery.cancelAndJoin()
                withTimeoutOrNull(3000) { if(client.isLoggedIn) client.offline() }
                QqIngress.signal(context)
            }
            clientScope.cancel();expansions.close();contactRequests.close();historyRequests.close();deliveries.close()
        }
    }
}
