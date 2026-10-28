package cn.yiji.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Archived snapshots have no foreign keys: deleting a source must not delete its recovery copy. */
@Entity(tableName = "ignored_items", indices = [Index("traceId"), Index("fingerprint")])
data class IgnoredItem(
    @PrimaryKey val id: String = newId(),
    val traceId: String,
    val kind: String,
    val title: String,
    val source: String,
    val preview: String,
    val payload: String,
    val fingerprint: String = "",
    val ignoredAt: Long = System.currentTimeMillis(),
)
