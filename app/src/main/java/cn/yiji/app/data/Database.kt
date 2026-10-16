package cn.yiji.app.data

import androidx.room.*
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
) {
    fun draft() = Draft(title, category, kind, startAt, endAt, dueAt, location, action, note)
    fun existing() = ExistingItem(id, draft(), status)
    val anchor: Long? get() = draft().anchor
    fun withDraft(d: Draft, reminder: Int = reminderMinutes) = copy(
        title = d.title, category = d.category, kind = d.kind, startAt = d.startAt,
        endAt = d.endAt, dueAt = d.dueAt, location = d.location, action = d.action,
        note = d.note, reminderMinutes = reminder, updatedAt = maxOf(System.currentTimeMillis(), updatedAt + 1))
}

@Entity(tableName = "raw_messages", indices = [Index("fingerprint")])
data class RawMessage(
    @PrimaryKey val id: String = newId(), val title: String, val body: String,
    val source: String, val packageName: String = "manual", val receivedAt: Long = System.currentTimeMillis(),
    val state: String = "PENDING", val fingerprint: String = "", val info: String = "",
)

@Entity(tableName = "suggestions", foreignKeys = [ForeignKey(entity = RawMessage::class,
    parentColumns = ["id"], childColumns = ["rawId"], onDelete = ForeignKey.CASCADE)], indices = [Index("rawId")])
data class Suggestion(
    @PrimaryKey val id: String = newId(), val rawId: String, val type: String = "NEW", val targetId: String? = null,
    val title: String, val category: String, val kind: String,
    val startAt: Long? = null, val endAt: Long? = null, val dueAt: Long? = null,
    val location: String = "", val action: String = "", val note: String = "", val issues: String = "",
    val reason: String = "", val state: String = "PENDING", val engine: String = "TinyBERT 离线模型",
) {
    fun draft() = Draft(title, category, kind, startAt, endAt, dueAt, location, action, note, issues.lines().filter { it.isNotBlank() })
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
    @Query("SELECT * FROM events ORDER BY COALESCE(startAt, dueAt, 9223372036854775807), createdAt DESC") fun observeEvents(): Flow<List<Event>>
    @Query("SELECT * FROM raw_messages ORDER BY receivedAt DESC") fun observeRaw(): Flow<List<RawMessage>>
    @Query("SELECT * FROM suggestions WHERE state = 'PENDING'") fun observeSuggestions(): Flow<List<Suggestion>>
    @Query("SELECT * FROM events") suspend fun allEvents(): List<Event>
    @Query("SELECT * FROM raw_messages") suspend fun allRaw(): List<RawMessage>
    @Query("SELECT * FROM suggestions") suspend fun allSuggestions(): List<Suggestion>
    @Query("SELECT * FROM sources") suspend fun allLinks(): List<SourceLink>
    @Query("SELECT * FROM history") suspend fun allHistory(): List<History>
    @Query("SELECT * FROM events WHERE id = :id") suspend fun event(id: String): Event?
    @Query("SELECT * FROM raw_messages WHERE id = :id") suspend fun raw(id: String): RawMessage?
    @Query("SELECT * FROM suggestions WHERE id = :id") suspend fun suggestion(id: String): Suggestion?
    @Query("SELECT * FROM raw_messages WHERE fingerprint = :fingerprint AND receivedAt > :since LIMIT 1") suspend fun recentDuplicate(fingerprint: String, since: Long): RawMessage?
    @Query("SELECT raw_messages.* FROM raw_messages INNER JOIN sources ON raw_messages.id = sources.rawId WHERE sources.eventId = :id ORDER BY receivedAt DESC") suspend fun sources(id: String): List<RawMessage>
    @Query("SELECT * FROM history WHERE eventId = :id ORDER BY changedAt DESC") suspend fun history(id: String): List<History>
    @Upsert suspend fun putEvent(event: Event)
    @Upsert suspend fun putRaw(raw: RawMessage)
    @Upsert suspend fun putSuggestion(suggestion: Suggestion)
    @Upsert suspend fun putLink(link: SourceLink)
    @Upsert suspend fun putHistory(history: History)
    @Query("DELETE FROM events WHERE id = :id") suspend fun deleteEvent(id: String)
    @Query("DELETE FROM suggestions WHERE rawId = :id") suspend fun deleteSuggestions(id: String)
    @Query("DELETE FROM raw_messages WHERE id = :id") suspend fun deleteRaw(id: String)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM raw_messages") suspend fun clearRaw()
}

@Database(entities = [Event::class, RawMessage::class, Suggestion::class, SourceLink::class, History::class], version = 1, exportSchema = true)
abstract class YijiDatabase : RoomDatabase() { abstract fun dao(): YijiDao }
