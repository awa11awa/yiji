package cn.yiji.app.qq

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.data.*
import cn.yiji.app.ml.Extraction
import cn.yiji.app.ml.ScheduleExtractor
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class QqIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    private lateinit var store: QqStore
    private var calls=0
    private lateinit var repository: Repository
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(QqStore.DATABASE)
        database=Room.inMemoryDatabaseBuilder(context,YijiDatabase::class.java).allowMainThreadQueries().build()
        store=QqStore(context);calls=0
        repository=Repository(database,Preferences(context),ReminderScheduler(context),ScheduleExtractor { _,_,_ -> calls++;Extraction(emptyList(),ignored=true) })
    }
    @After fun close() { store.close();database.close();context.deleteDatabase(QqStore.DATABASE) }
    private fun contact(account: String="111",scene: String="GROUP",peer: Long=222)=QqContact(account,scene,peer,name="课程群")
    private fun envelope(seed: String,body: String="明天交报告")=QqEnvelope(UUID.nameUUIDFromBytes(seed.toByteArray()).toString(),"111","GROUP",222,
        JSONObject().put("account","111").put("scene","GROUP").put("peerUin","222").put("senderUin","333")
            .put("conversationName","课程群").put("timestamp",1900000000L).put("text",body)
            .put("segments",org.json.JSONArray()).put("rawMessage",JSONObject().put("messageUid",seed)).toString())

    @Test fun selectionIsOptInAndScopedToAccountAndConversationType() {
        val selected=contact();store.refresh(listOf(selected,contact("444"),contact(scene="FRIEND")))
        assertFalse(store.selected("111","GROUP",222));assertTrue(store.selected("111").isEmpty())
        store.select(selected,true,1000)
        assertTrue(store.selected("111","GROUP",222));assertFalse(store.selected("444","GROUP",222));assertFalse(store.selected("111","FRIEND",222))
        store.refresh(listOf(selected.copy(name="课程群改名")))
        assertEquals("课程群改名",store.contact("111","GROUP",222)!!.name)
        assertEquals(1000L,store.selected("111").single().since)
    }
    @Test fun reselectStartsANewBoundaryAndDropsUnimportedOldRows() {
        val c=contact();store.refresh(listOf(c));store.select(c,true,1000)
        store.checkpoint(store.selected("111").single(),55)
        store.stage(envelope("old"),true)
        store.select(c,false,2000);assertTrue(store.pending().isEmpty())
        store.select(c,true,3000)
        val current=store.selected("111").single();assertEquals(3000L,current.since);assertEquals(0L,current.watermark)
        store.checkpoint(c.copy(since=1000),99);assertEquals(0L,store.selected("111").single().watermark)
    }
    @Test fun unfinishedExpansionSurvivesReopeningAndDoesNotDeliverAPreview() {
        val e=envelope("long","预览")
        store.stage(e,false);assertTrue(store.pending().isEmpty())
        store.close();store=QqStore(context)
        assertEquals(e.id,store.waitingIds("111").single())
        assertEquals(e,store.envelope(e.id))
        val full=envelope("long","完整长消息")
        store.complete(full.id,full.payload)
        assertEquals(full,store.pending().single());assertTrue(store.waitingIds("111").isEmpty())
    }
    @Test fun fastBurstAndReplaySurviveReopenAndBatchedImport()=runBlocking {
        repeat(250) { assertTrue(store.stage(envelope("burst-$it","相同文字"),true)) }
        assertFalse(store.stage(envelope("burst-10","相同文字"),true))
        store.close();store=QqStore(context)
        var imported=0
        while(true) {
            val batch=store.pending();if(batch.isEmpty()) break
            assertTrue(batch.size<=64)
            for(e in batch) { repository.enqueueQq(e,false);store.acknowledge(e.id);imported++ }
        }
        assertEquals(250,imported);assertEquals(250,repository.dao.allRaw().size)
        assertEquals(250,repository.dao.allJobs().size);assertEquals(0,calls)
        assertTrue(store.pending().isEmpty())
    }
    @Test fun identicalTextHasSeparateIdentityWhileReplayDoesNotRepeatInference()=runBlocking {
        val first=envelope("a");val second=envelope("b")
        repository.enqueueQq(first,false);repository.enqueueQq(second,false)
        assertEquals(IngestDisposition.DUPLICATE,repository.enqueueQq(first,false).disposition)
        assertEquals(0,calls);repository.processQueued()
        assertEquals(2,calls);assertEquals(2,repository.dao.allRaw().size)
        repository.enqueueQq(first,false);repository.processQueued();assertEquals(2,calls)
    }
    @Test fun largeUnicodeBodyAndStructuredContentRoundTripThroughArchiveAndBackup()=runBlocking {
        val text="完整正文🙂，不是通知预览。\n".repeat(10000)
        val e=envelope("large",text);repository.enqueueQq(e,false)
        val raw=repository.dao.raw(e.id)!!;assertEquals(text,raw.body);assertEquals(0,calls)
        assertEquals(e.payload,QqStore.unpack(Base64.decode(raw.qqMetadata,Base64.NO_WRAP)))
        assertTrue(raw.qqMetadata.length<e.payload.length/4)
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(emptyList(),listOf(raw),emptyList(),emptyList(),emptyList()))).raw.single()
        assertEquals(text,restored.body);assertEquals(raw.qqMetadata,restored.qqMetadata)
    }
    @Test fun taskRemovalKeepsEnabledCaptureInAnIsolatedService() {
        val info=context.packageManager.getServiceInfo(ComponentName(context,QqCaptureService::class.java),0)
        assertTrue(info.processName.endsWith(":qq_capture"));assertFalse(info.exported)
        assertEquals(0,info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        val controller=Robolectric.buildService(QqCaptureService::class.java).create()
        store.enabled=true;controller.get().onTaskRemoved(Intent())
        assertTrue(store.enabled);controller.destroy()
    }
    @Test fun explicitPausePersistsAndStickyRestartDoesNotReenableIt() {
        store.enabled=true;store.directMode=true
        val controller=Robolectric.buildService(QqCaptureService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY,controller.get().onStartCommand(Intent(context,QqCaptureService::class.java).setAction(QqCaptureService.ACTION_PAUSE),0,1))
        assertFalse(store.enabled);assertTrue(store.directMode)
        assertEquals(android.app.Service.START_NOT_STICKY,controller.get().onStartCommand(null,0,2))
        assertFalse(store.enabled);controller.destroy()
    }
}
