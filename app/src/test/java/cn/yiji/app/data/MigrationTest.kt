package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MigrationTest {
    @Test fun v2DatabaseUpgradesWithoutChangingExistingTimesOrSuggestions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2.1.2-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/cn.yiji.app.data.YijiDatabase/2.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val placeholder = 36.toChar().toString() + "{TABLE_NAME}"
                        db.execSQL(entity.getString("createSql").replace(placeholder, entity.getString("tableName")))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace(placeholder, entity.getString("tableName")))
                    }
                    db.execSQL("INSERT INTO raw_messages VALUES ('raw-v2','原文','课程调整','微信','wechat',1000,'PENDING','hash-v2','原文保留')")
                    db.execSQL("INSERT INTO events (id,title,category,kind,startAt,endAt,dueAt,location,action,note,status,reminderMinutes,createdAt,updatedAt) VALUES ('event-v2','班会','会议','SCHEDULE',5000,6000,NULL,'102','','旧事项','ACTIVE',30,1000,2000)")
                    db.execSQL("INSERT INTO suggestions (id,rawId,type,targetId,title,category,kind,startAt,endAt,dueAt,location,action,note,issues,reason,state,engine) VALUES ('suggestion-v2','raw-v2','UPDATE','event-v2','班会调整','会议','SCHEDULE',7000,8000,NULL,'103','','来源原文','','待核对','PENDING','旧模型')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        helper.writableDatabase; helper.close()
        val database = Room.databaseBuilder(context, YijiDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(YijiDatabase.MIGRATION_2_3).build()
        try {
            val event = database.dao().event("event-v2")!!
            assertEquals(5000L, event.startAt); assertEquals(6000L, event.endAt)
            assertEquals(30, event.reminderMinutes); assertTrue(event.subtasks.isEmpty()); assertNull(event.opensAt)
            val suggestion = database.dao().suggestion("suggestion-v2")!!
            assertEquals("event-v2", suggestion.targetId); assertEquals(7000L, suggestion.startAt)
            assertEquals("课程调整", database.dao().raw("raw-v2")!!.body)
            database.dao().putEvent(event.copy(subtasks = listOf(cn.yiji.app.core.Subtask("第一项"))))
            assertEquals(1, database.dao().event(event.id)!!.subtasks.size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun v1DatabaseUpgradesWithoutLosingMessagesAndCanStoreQueueJobs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2.1-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/cn.yiji.app.data.YijiDatabase/1.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val placeholder = 36.toChar().toString() + "{TABLE_NAME}"
                        db.execSQL(entity.getString("createSql").replace(placeholder, entity.getString("tableName")))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace(placeholder, entity.getString("tableName")))
                    }
                    db.execSQL("INSERT INTO raw_messages VALUES ('old-raw','旧通知','明晚交报告','微信','wechat',1000,'PENDING','old-hash','旧版原文')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        helper.writableDatabase
        helper.close()
        val database = Room.databaseBuilder(context, YijiDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(YijiDatabase.MIGRATION_1_2, YijiDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("明晚交报告", database.dao().raw("old-raw")!!.body)
            database.dao().putJob(MessageJob("old-raw"))
            assertEquals("QUEUED", database.dao().job("old-raw")!!.state)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
