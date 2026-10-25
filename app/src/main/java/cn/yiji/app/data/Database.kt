package cn.yiji.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.yiji.app.core.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Entity(tableName = "events")
data class Event(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val category: String,
    val kind: String,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val dueAt: Long? = null,
    val location: String = "",
    val action: String = "",
    val note: String = "",
    val status: String = "ACTIVE",
    val reminderMinutes: Int = 30,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val opensAt: Long? = null,
    @ColumnInfo(defaultValue = "'{}'") val timing: Timing = Timing(),
    @ColumnInfo(defaultValue = "'[]'") val subtasks: List<Subtask> = emptyList(),
) {
    fun draft() = Draft(title, category, kind, startAt, endAt, dueAt, location, action, note,
        opensAt = opensAt, timing = timing, subtasks = subtasks)
    fun existing() = ExistingItem(id, draft(), status)
    val anchor: Long? get() = draft().anchor
    fun withDraft(d: Draft, reminder: Int = reminderMinutes) = copy(
        title = d.title, category = d.category, kind = d.kind, startAt = d.startAt,
        endAt = d.endAt, dueAt = d.dueAt, location = d.location, action = d.action,
        note = d.note, reminderMinutes = reminder, updatedAt = maxOf(System.currentTimeMillis(), updatedAt + 1),
        opensAt = d.opensAt, timing = d.timing, subtasks = d.subtasks)
}

@Entity(tableName = "raw_messages", indices = [Index("fingerprint")])
data class RawMessage(
    @PrimaryKey val id: String = newId(), val title: String, val body: String,
    val source: String, val packageName: String = "manual", val receivedAt: Long = System.currentTimeMillis(),
    val state: String = "PENDING", val fingerprint: String = "", val info: String = "",
    @ColumnInfo(defaultValue = "''") val qqMetadata: String = "",
)

@Entity(tableName = "message_jobs", foreignKeys = [ForeignKey(entity = RawMessage::class,
    parentColumns = ["id"], childColumns = ["rawId"], onDelete = ForeignKey.CASCADE)], indices = [Index("state")])
data class MessageJob(@PrimaryKey val rawId: String, val state: String = "QUEUED", val attempts: Int = 0,
    val forceCloud: Boolean = false, val updatedAt: Long = System.currentTimeMillis(), val nextAttemptAt: Long = 0,
    val candidate: Boolean = false)

@Entity(tableName = "suggestions", foreignKeys = [ForeignKey(entity = RawMessage::class,
    parentColumns = ["id"], childColumns = ["rawId"], onDelete = ForeignKey.CASCADE)], indices = [Index("rawId")])
data class Suggestion(
    @PrimaryKey val id: String = newId(), val rawId: String, val type: String = "NEW", val targetId: String? = null,
    val title: String, val category: String, val kind: String,
    val startAt: Long? = null, val endAt: Long? = null, val dueAt: Long? = null,
    val location: String = "", val action: String = "", val note: String = "", val issues: String = "",
    val reason: String = "", val state: String = "PENDING", val engine: String = "TinyBERT 离线模型",
    @ColumnInfo(defaultValue = "NULL") val opensAt: Long? = null,
    @ColumnInfo(defaultValue = "'{}'") val timing: Timing = Timing(),
    @ColumnInfo(defaultValue = "'[]'") val subtasks: List<Subtask> = emptyList(),
    @ColumnInfo(defaultValue = "0") val keepSubtasks: Boolean = false,
) {
    fun draft() = Draft(title, category, kind, startAt, endAt, dueAt, location, action, note,
        issues.lines().filter { it.isNotBlank() }, opensAt, timing, subtasks, keepSubtasks)
}

@Entity(tableName = "sources", primaryKeys = ["eventId", "rawId"], foreignKeys = [
    ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = RawMessage::class, parentColumns = ["id"], childColumns = ["rawId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("rawId")])
data class SourceLink(val eventId: String, val rawId: String)

@Entity(tableName = "history", foreignKeys = [ForeignKey(entity = Event::class, parentColumns = ["id"],
    childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class History(
    @PrimaryKey val id: String = newId(), val eventId: String, val changedAt: Long = System.currentTimeMillis(),
    val action: String, val before: String = "", val after: String = "",
)

@Dao
interface YijiDao {
    @Query("SELECT * FROM message_jobs") fun observeJobs(): Flow<List<MessageJob>>
    @Query("SELECT * FROM events ORDER BY COALESCE(startAt, dueAt, 9223372036854775807), createdAt DESC") fun observeEvents(): Flow<List<Event>>
    // The list displays text; load structured QQ archives only for export/detail reads.
    @Query("SELECT id,title,body,source,packageName,receivedAt,state,fingerprint,info,'' AS qqMetadata FROM raw_messages ORDER BY receivedAt DESC") fun observeRaw(): Flow<List<RawMessage>>
    @Query("SELECT * FROM suggestions WHERE state = 'PENDING'") fun observeSuggestions(): Flow<List<Suggestion>>
    @Query("SELECT * FROM events") suspend fun allEvents(): List<Event>
    @Query("SELECT * FROM raw_messages") suspend fun allRaw(): List<RawMessage>
    @Query("SELECT * FROM suggestions") suspend fun allSuggestions(): List<Suggestion>
    @Query("SELECT * FROM sources") suspend fun allLinks(): List<SourceLink>
    @Query("SELECT * FROM history") suspend fun allHistory(): List<History>
    @Query("SELECT * FROM message_jobs WHERE rawId = :id") suspend fun job(id: String): MessageJob?
    @Query("SELECT * FROM message_jobs ORDER BY updatedAt") suspend fun allJobs(): List<MessageJob>
    @Query("SELECT * FROM message_jobs WHERE state IN ('QUEUED', 'RETRY') AND nextAttemptAt <= :now ORDER BY updatedAt LIMIT 8") suspend fun readyJobs(now: Long): List<MessageJob>
    @Query("UPDATE message_jobs SET state = 'PROCESSING', updatedAt = :now WHERE rawId = :id AND state IN ('QUEUED', 'RETRY')") suspend fun claimJob(id: String, now: Long): Int
    @Query("UPDATE message_jobs SET state = 'QUEUED', nextAttemptAt = 0 WHERE state = 'PROCESSING'") suspend fun recoverJobs()
    @Query("UPDATE message_jobs SET state = 'QUEUED', attempts = 0, nextAttemptAt = 0 WHERE state IN ('WAITING_API', 'FAILED', 'RETRY')") suspend fun resumeJobs()
    @Query("SELECT * FROM raw_messages WHERE packageName = :pkg AND title = :title AND id != :exclude AND state != 'IGNORED' AND receivedAt <= :at AND receivedAt >= :since AND (state = 'CONFIRMED' OR EXISTS (SELECT 1 FROM message_jobs WHERE message_jobs.rawId = raw_messages.id AND candidate = 1) OR EXISTS (SELECT 1 FROM suggestions WHERE suggestions.rawId = raw_messages.id)) ORDER BY receivedAt DESC LIMIT 4")
    suspend fun conversation(pkg: String, title: String, exclude: String, at: Long, since: Long): List<RawMessage>
    @Query("SELECT DISTINCT events.* FROM events INNER JOIN sources ON events.id = sources.eventId INNER JOIN raw_messages ON raw_messages.id = sources.rawId WHERE events.status = 'ACTIVE' AND raw_messages.packageName = :pkg AND raw_messages.title = :title ORDER BY events.updatedAt DESC LIMIT 6")
    suspend fun conversationEvents(pkg: String, title: String): List<Event>
    @Query("SELECT * FROM events WHERE id = :id") suspend fun event(id: String): Event?
    @Query("SELECT * FROM raw_messages WHERE id = :id") suspend fun raw(id: String): RawMessage?
    @Query("SELECT * FROM suggestions WHERE id = :id") suspend fun suggestion(id: String): Suggestion?
    @Query("SELECT * FROM raw_messages WHERE fingerprint = :fingerprint AND receivedAt > :since LIMIT 1") suspend fun recentDuplicate(fingerprint: String, since: Long): RawMessage?
    @Query("SELECT raw_messages.* FROM raw_messages INNER JOIN sources ON raw_messages.id = sources.rawId WHERE sources.eventId = :id ORDER BY receivedAt DESC") suspend fun sources(id: String): List<RawMessage>
    @Query("SELECT * FROM history WHERE eventId = :id ORDER BY changedAt DESC") suspend fun history(id: String): List<History>
    @Upsert suspend fun putEvent(event: Event)
    @Upsert suspend fun putRaw(raw: RawMessage)
    @Upsert suspend fun putJob(job: MessageJob)
    @Query("DELETE FROM message_jobs WHERE rawId = :id") suspend fun deleteJob(id: String)
    @Upsert suspend fun putSuggestion(suggestion: Suggestion)
    @Upsert suspend fun putLink(link: SourceLink)
    @Upsert suspend fun putHistory(history: History)
    @Query("DELETE FROM events WHERE id = :id") suspend fun deleteEvent(id: String)
    @Query("DELETE FROM suggestions WHERE rawId = :id") suspend fun deleteSuggestions(id: String)
    @Query("DELETE FROM raw_messages WHERE id = :id") suspend fun deleteRaw(id: String)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM raw_messages") suspend fun clearRaw()
}

@TypeConverters(StructuredFields::class)
@Database(entities = [Event::class, RawMessage::class, Suggestion::class, SourceLink::class, History::class, MessageJob::class], version = 4, exportSchema = true)
abstract class YijiDatabase : RoomDatabase() {
    abstract fun dao(): YijiDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_messages ADD COLUMN qqMetadata TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("events", "suggestions").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN opensAt INTEGER DEFAULT NULL")
                    db.execSQL("ALTER TABLE $table ADD COLUMN timing TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL("ALTER TABLE $table ADD COLUMN subtasks TEXT NOT NULL DEFAULT '[]'")
                }
                db.execSQL("ALTER TABLE suggestions ADD COLUMN keepSubtasks INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS message_jobs (rawId TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, forceCloud INTEGER NOT NULL, updatedAt INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, candidate INTEGER NOT NULL, PRIMARY KEY(rawId), FOREIGN KEY(rawId) REFERENCES raw_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_jobs_state ON message_jobs (state)")
                db.execSQL("INSERT INTO message_jobs (rawId, state, attempts, forceCloud, updatedAt, nextAttemptAt, candidate) SELECT id, 'QUEUED', 0, 0, receivedAt, 0, 0 FROM raw_messages WHERE state = 'PENDING' AND NOT EXISTS (SELECT 1 FROM suggestions WHERE suggestions.rawId = raw_messages.id)")
            }
        }
    }
}
