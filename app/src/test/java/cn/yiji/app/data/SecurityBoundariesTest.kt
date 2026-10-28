package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.ml.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.qq.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SecurityBoundariesTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    private lateinit var profiles: ApiProfileStore
    private lateinit var repository: Repository
    private val inputs = mutableListOf<ExtractionInput>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(QqStore.DATABASE)
        context.getSharedPreferences("yiji-settings", 0).edit().clear().commit()
        context.getSharedPreferences("yiji-api-profiles", 0).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        profiles = ApiProfileStore(context, MemoryApiSecrets())
        repository = Repository(database, Preferences(context), ReminderScheduler(context), object : ContextualScheduleExtractor {
            override suspend fun extract(input: ExtractionInput): Extraction {
                inputs += input; return Extraction(emptyList(), needsReview = true)
            }
        }, profiles)
    }
    @After fun close() { database.close(); context.deleteDatabase(QqStore.DATABASE) }

    @Test fun otherGroupsAndManualItemsRequireSeparatePersistedConsent() = runBlocking {
        val original = RawMessage(title = "班级群", body = "旧会议通知", source = "微信", packageName = "wechat", state = "CONFIRMED")
        val other = original.copy(id = newId(), title = "家庭群")
        database.dao().putRaw(original); database.dao().putRaw(other)
        val same = Event(title = "项目会议", category = "会议", kind = "SCHEDULE")
        val private = same.copy(id = newId(), location = "私人地址")
        val manual = same.copy(id = newId(), location = "手动私人事项")
        listOf(same, private, manual).forEach { database.dao().putEvent(it) }
        database.dao().putLink(SourceLink(same.id, original.id)); database.dao().putLink(SourceLink(private.id, other.id))
        repository.ingestWithResult("班级群", "项目会议通知：明天参加会议", "微信", "wechat")
        assertEquals(listOf(same.id), inputs.last().candidates.map { it.id })
        assertFalse(profiles.current.crossSourceContext)
        profiles.crossSourceContext(true)
        assertTrue(ApiProfileStore(context, MemoryApiSecrets()).current.crossSourceContext)
        repository.ingestWithResult("班级群", "项目会议通知：后天参加会议", "微信", "wechat")
        assertEquals(setOf(same.id, private.id, manual.id), inputs.last().candidates.map { it.id }.toSet())
        profiles.crossSourceContext(false)
        repository.ingestWithResult("班级群", "项目会议通知：下周参加会议", "微信", "wechat")
        assertEquals(listOf(same.id), inputs.last().candidates.map { it.id })
    }

    private fun envelope(time: Long, text: String = "旧通知") = QqEnvelope(newId(), "111", "GROUP", 222,
        JSONObject().put("text", text).put("timestamp", time / 1000).put("conversationName", "班级群").toString())

    @Test fun disablingCrossSourceDuringLocalScreeningRemovesAlreadySelectedPrivateCandidates() = runBlocking {
        profiles.save(ApiProfile.glm, "fixture-key"); profiles.enable(true); profiles.crossSourceContext(true)
        var sent = ""
        val client = CloudExtractionClient(ApiTransport { _, _, payload ->
            sent = payload!!
            ApiResponse(200, JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", "{\"protocolVersion\":2,\"decision\":\"REVIEW\",\"items\":[]}")))).toString())
        })
        val same = Event(title = "当前会话会议", category = "会议", kind = "SCHEDULE")
        val private = same.copy(id = newId(), title = "私人会议")
        val hybrid = HybridScheduleExtractor(context, profiles, client, MessageGate {
            profiles.crossSourceContext(false); GatePrediction(true, .99, false)
        })
        hybrid.extract(ExtractionInput(RawMessage(title = "通知", body = "明天参加项目会议", source = "微信"),
            candidates = listOf(same, private), crossSourceCandidateIds = setOf(private.id)))
        val outgoing = JSONObject(JSONObject(sent).getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(listOf(same.id), (0 until outgoing.getJSONArray("existingItems").length()).map {
            outgoing.getJSONArray("existingItems").getJSONObject(it).getString("id")
        })
    }

    @Test fun clearingDiscardsReadyAndUnfinishedSpoolsAndRejectsLateOldMessages() = runBlocking {
        QqStore(context).use { store ->
            val c = QqContact("111", "GROUP", 222)
            store.refresh(listOf(c)); store.select(c, true, 1000)
            val selected = store.selected("111").single()
            val now = System.currentTimeMillis()
            store.stage(envelope(now - 1000), true)
            store.stage(envelope(now - 1000), false)
            repository.clear()
            assertTrue(store.pending().isEmpty()); assertTrue(store.waitingIds("111").isEmpty())
            assertEquals(0, QqIngress.drain(repository))
            assertFalse(store.stage(envelope(now - 1000), true))
            assertFalse(store.stage(envelope(now + 60_000), true, selected.since))
            val fresh = store.selected("111").single()
            assertTrue(store.stage(envelope(System.currentTimeMillis() + 2000, "新通知"), true, fresh.since))
            assertEquals(1, QqIngress.drain(repository))
            assertEquals("新通知", repository.dao.allRaw().single().body)
        }
    }

    @Test fun clearingWaitsForAnyBatchAlreadyTakenByTheImporter() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val batch = launch(start = CoroutineStart.UNDISPATCHED) { QqIngress.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { repository.clear() }
        assertFalse(clearing.isCompleted)
        release.complete(Unit); batch.join(); withTimeout(2000) { clearing.await() }
        assertTrue(repository.dao.allRaw().isEmpty())
    }

    @Test fun systemNotificationRecoveryCannotReimportContentFromBeforeTheClear() = runBlocking {
        val old = System.currentTimeMillis() - 1000
        repository.clear()
        val result = repository.enqueueWithResult("通知", "清空之前的正文", "微信", "wechat", old)
        assertEquals(IngestDisposition.IGNORED, result.disposition)
        assertTrue(repository.dao.allRaw().isEmpty())
        assertEquals(old < Preferences(context).clearedBefore, true)
    }
}
