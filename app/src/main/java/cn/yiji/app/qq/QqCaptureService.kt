package cn.yiji.app.qq

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.yiji.app.MainActivity
import cn.yiji.app.R
import dev.yiji.qqlocalprobe.AccountStore
import dev.yiji.qqlocalprobe.LoginInteraction
import dev.yiji.qqlocalprobe.QqDiagnostics
import dev.yiji.qqlocalprobe.QqSession
import kotlinx.coroutines.*

class QqCaptureService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var store: QqStore
    private lateinit var interaction: LoginInteraction
    private var running: Job?=null
    @Volatile private var session: QqSession?=null
    @Volatile private var forgetRequested=false
    override fun onCreate() {
        super.onCreate();store=QqStore(this);interaction=LoginInteraction(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"QQ 持续采集",NotificationManager.IMPORTANCE_LOW).apply { setSound(null,null);enableVibration(false) })
        interaction.observe { state -> if(store.enabled) {
            store.status(state[0],state[3]);store.put("challengeKind",state[1]);show(state[0],state[3])
        } }
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        when(intent?.action) {
            ACTION_PAUSE -> { stopCapture(false);return START_NOT_STICKY }
            ACTION_LOGOUT -> { stopCapture(true);return START_NOT_STICKY }
            ACTION_REPLY -> { interaction.submit(intent.getStringExtra("id"),intent.getStringExtra("result").orEmpty());if(!store.enabled) stopSelf();return if(store.enabled) START_STICKY else START_NOT_STICKY }
            ACTION_REFRESH -> { session?.refresh();if(!store.enabled) stopSelf();return if(store.enabled) START_STICKY else START_NOT_STICKY }
            ACTION_SELECTION -> { session?.selectionChanged();if(!store.enabled) stopSelf();return if(store.enabled) START_STICKY else START_NOT_STICKY }
        }
        val login=intent?.action==ACTION_LOGIN
        if(!login && !store.enabled) { stopSelf();return START_NOT_STICKY }
        if(running?.isCompleted==false) {
            if(store.enabled) { val s=store.snapshot();show(s.title,s.detail) }
            return if(store.enabled) START_STICKY else START_NOT_STICKY
        }
        val resume=!login || intent?.getBooleanExtra("resume",false)==true
        if(resume && !AccountStore(this).exists()) {
            store.enabled=false;store.status("请登录 QQ","尚无可恢复的登录票据");stopSelf();return START_NOT_STICKY
        }
        val uin=intent?.getLongExtra("uin",0) ?: 0
        var password=intent?.getStringExtra("password").orEmpty();intent?.removeExtra("password")
        store.directMode=true;store.enabled=true
        show("正在连接 QQ","正在准备接收端")
        running=scope.launch {
            try {
                val receiver=QqSession(this@QqCaptureService,store,interaction,uin,password,resume)
                password="";session=receiver;receiver.run()
            }
            catch(e: TimeoutCancellationException) { store.enabled=false;store.status("QQ 登录超时","请检查网络后重试");QqDiagnostics.error(this@QqCaptureService,"QQ-TIMEOUT",e) }
            catch(e: CancellationException) { throw e }
            catch(e: Throwable) {
                store.enabled=false;store.status("QQ 接收已中断",e.message?.take(200) ?: e.javaClass.simpleName)
                QqDiagnostics.error(this@QqCaptureService,"QQ-SESSION",e)
            } finally {
                password="";session=null;store.put("challengeKind","")
                if(forgetRequested) clearAccount()
                withContext(NonCancellable+Dispatchers.Main.immediate) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
            }
        }
        return START_STICKY
    }
    private fun stopCapture(forget: Boolean) {
        store.enabled=false;store.put("challengeKind","");interaction.cancel()
        store.status("QQ 采集已暂停","已保存的原文继续保留")
        forgetRequested=forgetRequested || forget
        val job=running
        if(job==null || job.isCompleted) {
            if(forgetRequested) clearAccount()
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        } else job.cancel()
    }
    private fun clearAccount() {
        AccountStore(this).clear();store.forget();LoginInteraction.stateFile(this).delete()
    }
    private fun show(title: String,detail: String) {
        val open=PendingIntent.getActivity(this,220,Intent(this,MainActivity::class.java).putExtra("openQq",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,221,Intent(this,javaClass).setAction(ACTION_PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title).setContentText(detail).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0,"暂停采集",pause).build()
        ServiceCompat.startForeground(this,ID,notification,if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        interaction.cancel();scope.cancel();store.close();super.onDestroy()
        // FEKit holds process-wide native state. Release only its isolated process.
        if(Build.VERSION.SDK_INT>=28 && Application.getProcessName().endsWith(":qq_capture")) android.os.Process.killProcess(android.os.Process.myPid())
        else if(Build.VERSION.SDK_INT<28) {
            val own=getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid==android.os.Process.myPid() }
            if(own?.processName?.endsWith(":qq_capture")==true) android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    companion object {
        const val ACTION_LOGIN="cn.yiji.app.qq.LOGIN"
        const val ACTION_REPLY="cn.yiji.app.qq.REPLY"
        const val ACTION_REFRESH="cn.yiji.app.qq.REFRESH"
        const val ACTION_SELECTION="cn.yiji.app.qq.SELECTION"
        const val ACTION_PAUSE="cn.yiji.app.qq.PAUSE"
        const val ACTION_LOGOUT="cn.yiji.app.qq.LOGOUT"
        private const val CHANNEL="yiji-qq-capture"
        private const val ID=220
        fun resumeIfEnabled(context: Context) {
            if(!QqStore.exists(context)) return
            val enabled=QqStore(context).use { it.enabled }
            if(enabled && AccountStore(context).exists()) runCatching { ContextCompat.startForegroundService(context,Intent(context,QqCaptureService::class.java)) }
        }
    }
}
