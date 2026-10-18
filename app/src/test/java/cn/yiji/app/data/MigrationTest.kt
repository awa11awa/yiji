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
            .addMigrations(YijiDatabase.MIGRATION_1_2).build()
        try {
            assertEquals("明晚交报告", database.dao().raw("old-raw")!!.body)
            database.dao().putJob(MessageJob("old-raw"))
            assertEquals("QUEUED", database.dao().job("old-raw")!!.state)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
