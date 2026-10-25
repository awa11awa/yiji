package cn.yiji.app.qq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class QqContact(val account: String, val scene: String, val uin: Long, val uid: String = "",
    val name: String = uin.toString(), val selected: Boolean = false, val since: Long = 0, val watermark: Long = 0)
data class QqEnvelope(val id: String, val account: String, val scene: String, val peer: Long, val payload: String)
data class QqStatus(val enabled: Boolean, val directMode: Boolean, val account: String, val title: String,
    val detail: String, val selectedCount: Int, val stagedCount: Long, val pendingCount: Int, val challengeKind: String)

/** SQLite is the shared authority for the UI and the isolated QQ process. */
class QqStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE, null, 1), java.io.Closeable {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE config (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE contacts (account TEXT NOT NULL, scene TEXT NOT NULL, uin INTEGER NOT NULL, uid TEXT NOT NULL, name TEXT NOT NULL, selected INTEGER NOT NULL DEFAULT 0, since INTEGER NOT NULL DEFAULT 0, watermark INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,scene,uin))")
        db.execSQL("CREATE TABLE inbox (id TEXT PRIMARY KEY NOT NULL, account TEXT NOT NULL, scene TEXT NOT NULL, peer INTEGER NOT NULL, payload BLOB NOT NULL, ready INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX inbox_ready ON inbox(ready,createdAt)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun get(key: String, default: String = ""): String = readableDatabase.rawQuery("SELECT value FROM config WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else default }
    fun put(key: String, value: String) { writableDatabase.insertWithOnConflict("config", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    var enabled: Boolean get() = get("enabled") == "1"; set(value) = put("enabled", if (value) "1" else "0")
    var directMode: Boolean get() = get("directMode") == "1"; set(value) = put("directMode", if (value) "1" else "0")
    var account: String get() = get("account"); set(value) = put("account", value)
    fun status(title: String, detail: String) { put("title", title); put("detail", detail) }
    fun snapshot(): QqStatus = QqStatus(enabled, directMode, account, get("title", "QQ 接收端未启用"), get("detail"),
        readableDatabase.rawQuery("SELECT count(*) FROM contacts WHERE account=? AND selected=1",arrayOf(account)).use { it.moveToFirst();it.getInt(0) }, get("stagedCount", "0").toLong(), scalar("SELECT count(*) FROM inbox"), get("challengeKind"))
    private fun scalar(sql: String) = readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
    private fun Cursor.contact() = QqContact(getString(0),getString(1),getLong(2),getString(3),getString(4),getInt(5)==1,getLong(6),getLong(7))
    fun contacts(account: String): List<QqContact> = readableDatabase.rawQuery("SELECT * FROM contacts WHERE account=? ORDER BY selected DESC,name COLLATE NOCASE", arrayOf(account)).use { c -> buildList { while (c.moveToNext()) add(c.contact()) } }
    fun selected(account: String): List<QqContact> = readableDatabase.rawQuery("SELECT * FROM contacts WHERE account=? AND selected=1", arrayOf(account)).use { c -> buildList { while(c.moveToNext()) add(c.contact()) } }
    fun contact(account: String, scene: String, peer: Long): QqContact? = readableDatabase.rawQuery("SELECT * FROM contacts WHERE account=? AND scene=? AND uin=?", arrayOf(account,scene,peer.toString())).use { if(it.moveToFirst()) it.contact() else null }
    fun selected(account: String, scene: String, peer: Long): Boolean = readableDatabase.rawQuery("SELECT selected FROM contacts WHERE account=? AND scene=? AND uin=?", arrayOf(account,scene,peer.toString())).use { it.moveToFirst() && it.getInt(0)==1 }
    fun refresh(items: List<QqContact>) {
        val db=writableDatabase;db.beginTransaction()
        try {
            for(c in items) {
                val v=ContentValues().apply { put("account",c.account);put("scene",c.scene);put("uin",c.uin);put("uid",c.uid);put("name",c.name) }
                db.insertWithOnConflict("contacts",null,v,SQLiteDatabase.CONFLICT_IGNORE)
                db.update("contacts",ContentValues().apply { put("uid",c.uid);put("name",c.name) },"account=? AND scene=? AND uin=?",arrayOf(c.account,c.scene,c.uin.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun select(c: QqContact, value: Boolean, now: Long = System.currentTimeMillis()) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.update("contacts",ContentValues().apply { put("selected",if(value) 1 else 0);put("since",now);put("watermark",0) },"account=? AND scene=? AND uin=?",arrayOf(c.account,c.scene,c.uin.toString()))
            db.delete("inbox","account=? AND scene=? AND peer=?",arrayOf(c.account,c.scene,c.uin.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun checkpoint(c: QqContact, sequence: Long) { writableDatabase.execSQL("UPDATE contacts SET watermark=MAX(watermark,?) WHERE account=? AND scene=? AND uin=? AND selected=1 AND since=?",arrayOf<Any>(sequence,c.account,c.scene,c.uin,c.since)) }
    fun stage(e: QqEnvelope, ready: Boolean): Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val inserted=db.compileStatement("INSERT OR IGNORE INTO inbox(id,account,scene,peer,payload,ready,createdAt) VALUES(?,?,?,?,?,?,?)").use {
                it.bindString(1,e.id);it.bindString(2,e.account);it.bindString(3,e.scene);it.bindLong(4,e.peer);it.bindBlob(5,pack(e.payload));it.bindLong(6,if(ready) 1 else 0);it.bindLong(7,System.currentTimeMillis());it.executeInsert()!=-1L
            }
            if(inserted) db.execSQL("INSERT OR REPLACE INTO config(key,value) VALUES('stagedCount',CAST(COALESCE((SELECT value FROM config WHERE key='stagedCount'),'0') AS INTEGER)+1)")
            db.setTransactionSuccessful();return inserted
        } finally { db.endTransaction() }
    }
    private fun rows(ready: Boolean, limit: Int): List<QqEnvelope> = readableDatabase.rawQuery("SELECT id,account,scene,peer,payload FROM inbox WHERE ready=? ORDER BY createdAt LIMIT ?",arrayOf(if(ready) "1" else "0",limit.toString())).use { c -> buildList { while(c.moveToNext()) add(QqEnvelope(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),unpack(c.getBlob(4)))) } }
    fun pending(limit: Int = 64) = rows(true,limit)
    fun waitingIds(account: String): List<String> = readableDatabase.rawQuery("SELECT id FROM inbox WHERE ready=0 AND account=? ORDER BY createdAt",arrayOf(account)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun envelope(id: String): QqEnvelope? = readableDatabase.rawQuery("SELECT id,account,scene,peer,payload FROM inbox WHERE id=?",arrayOf(id)).use { c ->
        if(c.moveToFirst()) QqEnvelope(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),unpack(c.getBlob(4))) else null
    }
    fun complete(id: String, payload: String) { writableDatabase.update("inbox",ContentValues().apply { put("payload",pack(payload));put("ready",1) },"id=?",arrayOf(id)) }
    fun acknowledge(id: String) { writableDatabase.delete("inbox","id=?",arrayOf(id)) }
    fun forget() { val db=writableDatabase;db.beginTransaction();try { db.delete("inbox",null,null);db.delete("contacts",null,null);db.delete("config",null,null);db.setTransactionSuccessful() } finally { db.endTransaction() } }
    companion object {
        const val DATABASE="qq-capture.db"
        fun exists(context: Context)=context.getDatabasePath(DATABASE).isFile
        fun pack(value: String): ByteArray = ByteArrayOutputStream().use { out -> GZIPOutputStream(out).use { it.write(value.toByteArray(Charsets.UTF_8)) };out.toByteArray() }
        fun unpack(value: ByteArray): String = GZIPInputStream(value.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
