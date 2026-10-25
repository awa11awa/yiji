package cn.yiji.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import cn.yiji.app.ui.YijiApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openQq by mutableStateOf(false)
    private var openEvent by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveIntent(intent)
        val repository = (application as YijiApplication).repository
        lifecycleScope.launch { repository.reschedule() }
        setContent { YijiApp(repository, openEvent, sharedText, openQq) { openEvent = null; sharedText = null; openQq = false } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveIntent(intent) }
    override fun onResume() {
        super.onResume()
        cn.yiji.app.qq.QqCaptureService.resumeIfEnabled(this)
        (application as YijiApplication).collectionSupervisor.refresh(allowForegroundStart = true, recover = true)
    }
    private fun receiveIntent(intent: Intent) {
        openQq = intent.getBooleanExtra("openQq", false)
        openEvent = intent.getStringExtra("eventId")
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
    }
}
