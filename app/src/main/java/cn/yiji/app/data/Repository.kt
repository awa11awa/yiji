package cn.yiji.app.data

import androidx.room.withTransaction
import cn.yiji.app.core.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.ml.LocalScheduleExtractor
import cn.yiji.app.ml.ScheduleExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

enum class IngestDisposition { PENDING, IGNORED, DUPLICATE }
data class IngestResult(val id: String, val disposition: IngestDisposition)

class Repository(val database: YijiDatabase, val preferences: Preferences, private val reminders: ReminderScheduler,
    private val extractor: ScheduleExtractor = LocalScheduleExtractor(preferences.context)) {
    val dao = database.dao()
    private val lock = Mutex()
    val events = dao.observeEvents()
    val rawMessages = dao.observeRaw()
    val suggestions = dao.observeSuggestions()

    suspend fun ingest(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): String =
        ingestWithResult(title, body, source, packageName, receivedAt).id

    suspend fun ingestWithResult(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): IngestResult = lock.withLock {
        require(body.isNotBlank() || title.isNotBlank()) { "通知文本不能为空" }
        require(body.length <= 16000 && title.length <= 200) { "通知内容过长，请分段导入" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("$packageName|$title|$body".toByteArray()).joinToString("") { "%02x".format(it) }
        if (packageName != "manual") dao.recentDuplicate(fingerprint, receivedAt - 86_400_000)?.let { return@withLock IngestResult(it.id, IngestDisposition.DUPLICATE) }
        val raw = RawMessage(title = title, body = body, source = source, packageName = packageName, receivedAt = receivedAt, fingerprint = fingerprint)
        extractAndStore(raw)
    }

    private suspend fun extractAndStore(original: RawMessage): IngestResult {
        val extraction = try { extractor.extract(original.title, original.body, original.receivedAt) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return modelFailure(original) }
        catch (_: LinkageError) { return modelFailure(original) }
        val raw = original.copy(state = if (extraction.ignored) "IGNORED" else "PENDING", info = extraction.info)
        if (extraction.ignored) {
            dao.putRaw(raw)
            return IngestResult(raw.id, IngestDisposition.IGNORED)
        }
        require(extraction.drafts.isNotEmpty()) { "模型未返回草稿" }
        val existing = dao.allEvents().sortedByDescending { it.updatedAt }
        val suggestions = extraction.drafts.map { draft ->
            val match = AssociationMatcher.suggest(draft, extraction.operation, existing.map { it.existing() })
            Suggestion(rawId = raw.id, type = match.type, targetId = match.targetId,
            title = draft.title, category = draft.category, kind = draft.kind, startAt = draft.startAt,
            endAt = draft.endAt, dueAt = draft.dueAt, location = draft.location, action = draft.action,
            note = draft.note, issues = draft.issues.joinToString("\n"), reason = match.reason, engine = "TinyBERT 离线模型 v2")
        }
        database.withTransaction { dao.putRaw(raw); suggestions.forEach { dao.putSuggestion(it) } }
        return IngestResult(raw.id, IngestDisposition.PENDING)
    }

    private suspend fun modelFailure(raw: RawMessage): IngestResult {
        dao.putRaw(raw.copy(state = "PENDING", info = "本地模型识别失败，原文已保存。请重试识别或手动添加事项。"))
        return IngestResult(raw.id, IngestDisposition.PENDING)
    }

    suspend fun retry(rawId: String) = lock.withLock {
        val raw = dao.raw(rawId) ?: error("原文已不存在")
        require(raw.state == "PENDING" && dao.allSuggestions().none { it.rawId == rawId }) { "该通知已有识别结果" }
        extractAndStore(raw)
    }

    suspend fun confirm(suggestionId: String, draft: Draft, type: String, targetId: String?, reminder: Int, expectedTargetUpdatedAt: Long? = null): String = lock.withLock {
        require(type in setOf("NEW", "MERGE", "UPDATE", "CANCEL")) { "处理方式无效" }
        require(reminder in -1..10080) { "提醒时间无效" }
        var result: Event? = null
        database.withTransaction {
            val suggestion = dao.suggestion(suggestionId) ?: error("建议已不存在")
            require(suggestion.state == "PENDING") { "这条建议已经处理" }
            val target = if (type != "NEW") targetId?.let { dao.event(it) } ?: error("请选择存在的关联事项") else null
            if (target != null) {
                require(target.status == "ACTIVE") { "关联事项已完成或取消，请选择其他事项" }
                if (expectedTargetUpdatedAt != null) require(target.updatedAt == expectedTargetUpdatedAt) { "关联事项已被修改，请重新打开确认页" }
            }
            if (type in setOf("NEW", "UPDATE")) draft.validationError()?.let { error(it) }
            val event = when (type) {
                "NEW" -> Event(title = draft.title, category = draft.category, kind = draft.kind).withDraft(draft, reminder)
                "UPDATE" -> target!!.withDraft(draft, reminder)
                "CANCEL" -> target!!.copy(status = "CANCELLED", updatedAt = maxOf(System.currentTimeMillis(), target.updatedAt + 1))
                else -> target!!
            }
            dao.putEvent(event)
            dao.putLink(SourceLink(event.id, suggestion.rawId))
            dao.putHistory(History(eventId = event.id, action = when (type) { "NEW" -> "从通知新建"; "UPDATE" -> "确认变更"; "CANCEL" -> "确认取消"; else -> "合并通知来源" },
                before = target?.let { BackupCodec.eventJson(it).toString() }.orEmpty(), after = BackupCodec.eventJson(event).toString()))
            dao.putSuggestion(suggestion.copy(state = "CONFIRMED"))
            reconcileRaw(suggestion.rawId)
            result = event
        }
        if (type != "MERGE") reminders.schedule(result!!)
        result!!.id
    }

    suspend fun save(draft: Draft, reminder: Int, existingId: String? = null, expectedUpdatedAt: Long? = null): String = lock.withLock {
        draft.validationError()?.let { error(it) }
        require(reminder in -1..10080) { "提醒时间无效" }
        var saved: Event? = null
        database.withTransaction {
            val previous = existingId?.let { dao.event(it) ?: error("事项已被删除") }
            if (previous != null && expectedUpdatedAt != null) require(previous.updatedAt == expectedUpdatedAt) { "事项已被修改，请重新打开编辑页" }
            val event = (previous ?: Event(title = draft.title, category = draft.category, kind = draft.kind)).withDraft(draft, reminder)
            dao.putEvent(event)
            dao.putHistory(History(eventId = event.id, action = if (previous == null) "手动新建" else "手动编辑",
                before = previous?.let { BackupCodec.eventJson(it).toString() }.orEmpty(), after = BackupCodec.eventJson(event).toString()))
            saved = event
        }
        reminders.schedule(saved!!); saved!!.id
    }
    suspend fun setStatus(id: String, status: String) = lock.withLock {
        require(status in setOf("ACTIVE", "DONE", "CANCELLED"))
        var saved: Event? = null
        database.withTransaction {
            val before = dao.event(id) ?: error("事项已被删除")
            val after = before.copy(status = status, updatedAt = maxOf(System.currentTimeMillis(), before.updatedAt + 1))
            dao.putEvent(after)
            dao.putHistory(History(eventId = id, action = when (status) { "DONE" -> "标记完成"; "CANCELLED" -> "取消事项"; else -> "恢复事项" },
                before = BackupCodec.eventJson(before).toString(), after = BackupCodec.eventJson(after).toString()))
            saved = after
        }
        reminders.schedule(saved!!)
    }
    suspend fun dismiss(rawId: String) = lock.withLock {
        database.withTransaction {
            dao.allSuggestions().filter { it.rawId == rawId && it.state == "PENDING" }.forEach { dao.putSuggestion(it.copy(state = "DISMISSED")) }
            reconcileRaw(rawId)
        }
    }
    suspend fun dismissSuggestion(id: String) = lock.withLock {
        database.withTransaction {
            val suggestion = dao.suggestion(id) ?: error("建议已不存在")
            require(suggestion.state == "PENDING") { "这条建议已经处理" }
            dao.putSuggestion(suggestion.copy(state = "DISMISSED"))
            reconcileRaw(suggestion.rawId)
        }
    }
    private suspend fun reconcileRaw(rawId: String) {
        val siblings = dao.allSuggestions().filter { it.rawId == rawId }
        val state = when {
            siblings.any { it.state == "PENDING" } -> "PENDING"
            siblings.any { it.state == "CONFIRMED" } -> "CONFIRMED"
            else -> "IGNORED"
        }
        dao.raw(rawId)?.let { dao.putRaw(it.copy(state = state)) }
    }
    suspend fun delete(id: String) = lock.withLock { reminders.cancel(id); dao.deleteEvent(id) }
    suspend fun deleteRaw(id: String) = lock.withLock { dao.deleteRaw(id) }
    suspend fun reschedule() { dao.allEvents().forEach { reminders.schedule(it) } }
    suspend fun export(): String = lock.withLock { database.withTransaction {
        BackupCodec.encode(Backup(dao.allEvents(), dao.allRaw(), dao.allSuggestions(), dao.allLinks(), dao.allHistory()))
    } }
    suspend fun restore(text: String) = lock.withLock {
        val backup = BackupCodec.decode(text) // Validate completely before replacing anything.
        val previousIds = dao.allEvents().map { it.id }
        database.withTransaction {
            dao.clearEvents(); dao.clearRaw()
            backup.events.forEach { dao.putEvent(it) }; backup.raw.forEach { dao.putRaw(it) }
            backup.suggestions.forEach { dao.putSuggestion(it) }; backup.links.forEach { dao.putLink(it) }
            backup.history.forEach { dao.putHistory(it) }
        }
        previousIds.forEach { reminders.cancel(it) }; reschedule()
    }
    suspend fun clear() = lock.withLock {
        dao.allEvents().forEach { reminders.cancel(it.id) }
        database.withTransaction { dao.clearEvents(); dao.clearRaw() }
    }
}
