package cn.yiji.app.data

import androidx.room.withTransaction
import cn.yiji.app.core.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.platform.ExtractionWork
import cn.yiji.app.ml.HybridScheduleExtractor
import cn.yiji.app.ml.ContextualScheduleExtractor
import cn.yiji.app.ml.ScheduleExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

enum class IngestDisposition { PENDING, IGNORED, DUPLICATE, QUEUED }
data class IngestResult(val id: String, val disposition: IngestDisposition)

class Repository(val database: YijiDatabase, val preferences: Preferences, private val reminders: ReminderScheduler,
    extractor: ScheduleExtractor? = null, val apiProfiles: ApiProfileStore = ApiProfileStore(preferences.context)) {
    private val extractor = extractor ?: HybridScheduleExtractor(preferences.context, apiProfiles, onCandidate = { id ->
        dao.job(id)?.let { dao.putJob(it.copy(candidate = true)) }
    })
    val dao = database.dao()
    private val lock = Mutex()
    val events = dao.observeEvents()
    val rawMessages = dao.observeRaw()
    val suggestions = dao.observeSuggestions()

    private fun schedule() {
        if (preferences.context is cn.yiji.app.YijiApplication) ExtractionWork.schedule(preferences.context)
    }
    suspend fun ingest(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): String =
        ingestWithResult(title, body, source, packageName, receivedAt).id

    /** Synchronous entry for integrations and tests. Raw data is committed before inference. */
    suspend fun ingestWithResult(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): IngestResult {
        val queued = storeRaw(title, body, source, packageName, receivedAt)
        if (queued.disposition == IngestDisposition.DUPLICATE) return queued
        processRaw(queued.id)
        return IngestResult(queued.id, if (dao.raw(queued.id)?.state == "IGNORED") IngestDisposition.IGNORED else IngestDisposition.PENDING)
    }

    /** Notification/UI entry: commit first, then hand durable work to WorkManager. */
    suspend fun enqueueWithResult(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): IngestResult {
        val result = storeRaw(title, body, source, packageName, receivedAt)
        if (result.disposition != IngestDisposition.DUPLICATE) schedule()
        return result
    }

    private suspend fun storeRaw(title: String, body: String, source: String, packageName: String, receivedAt: Long): IngestResult = lock.withLock {
        require(body.isNotBlank() || title.isNotBlank()) { "通知文本不能为空" }
        require(body.length <= 16000 && title.length <= 200) { "通知内容过长，请分段导入" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest((packageName + "|" + title + "|" + body).toByteArray()).joinToString("") { "%02x".format(it) }
        if (packageName != "manual") dao.recentDuplicate(fingerprint, receivedAt - 86_400_000)?.let {
            return@withLock IngestResult(it.id, IngestDisposition.DUPLICATE)
        }
        val raw = RawMessage(title = title, body = body, source = source, packageName = packageName,
            receivedAt = receivedAt, fingerprint = fingerprint, info = "原文已保存，等待本地筛选")
        database.withTransaction { dao.putRaw(raw); dao.putJob(MessageJob(raw.id)) }
        IngestResult(raw.id, IngestDisposition.QUEUED)
    }

    private suspend fun input(raw: RawMessage, forceCloud: Boolean): ExtractionInput {
        if (!apiProfiles.current.includeContext) return ExtractionInput(raw, forceCloud = forceCloud)
        val sameConversation = raw.packageName != "manual" && raw.title.isNotBlank()
        val previous = if (sameConversation) dao.conversation(raw.packageName, raw.title, raw.id, raw.receivedAt,
            raw.receivedAt - 86_400_000).map { it.copy(body = it.body.take(1500)) } else emptyList()
        val scoped = if (sameConversation) dao.conversationEvents(raw.packageName, raw.title) else emptyList()
        val named = dao.allEvents().filter { it.status == "ACTIVE" && AssociationMatcher.similarity(raw.title + raw.body.take(300), it.title) >= .20 }
            .sortedByDescending { it.updatedAt }.take(4)
        return ExtractionInput(raw, previous.reversed(), (scoped + named).distinctBy { it.id }.take(8), forceCloud)
    }

    suspend fun processRaw(rawId: String) {
        val original = lock.withLock {
            val raw = dao.raw(rawId) ?: return
            if (raw.state != "PENDING" || dao.claimJob(rawId, System.currentTimeMillis()) == 0) return
            dao.putRaw(raw.copy(info = "正在本地筛选；日程候选将交给所选云端模型提取"))
            raw
        }
        val job = dao.job(rawId) ?: return
        val extraction = try {
            if (extractor is ContextualScheduleExtractor) extractor.extract(input(original, job.forceCloud))
            else extractor.extract(original.title, original.body, original.receivedAt)
        } catch (e: CancellationException) { throw e }
        catch (e: ExtractionFailure) { failed(original.id, job, e); return }
        catch (_: Exception) { failed(original.id, job, ExtractionFailure("识别未完成，原文已保存，请重试识别或手动添加事项")); return }
        catch (_: LinkageError) { failed(original.id, job, ExtractionFailure("本地筛选模型加载失败，原文已保存，请重试或送云端复核")); return }
        if (!extraction.ignored && extraction.drafts.isEmpty()) {
            failed(rawId, job, ExtractionFailure("模型未返回事项草稿，请重试或手动添加"))
            return
        }
        lock.withLock {
            val current = dao.raw(rawId) ?: return@withLock
            if (current.state != "PENDING" || dao.job(rawId)?.state != "PROCESSING") return@withLock
            val existing = dao.allEvents().sortedByDescending { it.updatedAt }
            val drafts = extraction.drafts.mapIndexed { index, draft ->
                val operation = extraction.operations.getOrNull(index) ?: extraction.operation
                val targetId = extraction.targetIds.getOrNull(index)
                val target = existing.firstOrNull { it.id == targetId && it.status == "ACTIVE" && it.kind == draft.kind }
                val match = AssociationMatcher.suggest(draft, operation, existing.map { it.existing() })
                val type = if (operation in setOf("UPDATE", "CANCEL")) operation else match.type
                Suggestion(rawId = rawId, type = type, targetId = target?.id ?: match.targetId,
                    title = draft.title, category = draft.category, kind = draft.kind, startAt = draft.startAt,
                    endAt = draft.endAt, dueAt = draft.dueAt, location = draft.location, action = draft.action,
                    note = draft.note, issues = draft.issues.joinToString("\n"),
                    reason = if (target != null) "模型关联到已有事项，请核对后确认" else match.reason, engine = extraction.engine)
            }
            database.withTransaction {
                dao.putRaw(current.copy(state = if (extraction.ignored) "IGNORED" else "PENDING", info = extraction.info))
                drafts.forEach { dao.putSuggestion(it) }
                dao.putJob((dao.job(rawId) ?: job).copy(state = "DONE", updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun failed(id: String, job: MessageJob, failure: ExtractionFailure) = lock.withLock {
        val current = dao.raw(id) ?: return@withLock
        if (current.state != "PENDING" || dao.job(id)?.state != "PROCESSING") return@withLock
        val attempts = job.attempts + 1
        val retry = failure.retryable && attempts < 5
        val state = if (failure.waitingForApi) "WAITING_API" else if (retry) "RETRY" else "FAILED"
        val delay = 30_000L * (1L shl minOf(attempts - 1, 5))
        database.withTransaction {
            dao.putRaw(current.copy(info = failure.userMessage + if (retry) "（自动重试 " + attempts + "/4）" else ""))
            dao.putJob((dao.job(id) ?: job).copy(state = state, attempts = attempts, updatedAt = System.currentTimeMillis(),
                nextAttemptAt = if (retry) System.currentTimeMillis() + delay else 0))
        }
    }
    suspend fun processQueued() {
        val started = System.currentTimeMillis()
        repeat(24) {
            if (System.currentTimeMillis() - started > 240_000) return
            val job = dao.readyJobs(System.currentTimeMillis()).firstOrNull() ?: return
            processRaw(job.rawId)
        }
    }
    suspend fun resumeWaiting(): Int {
        val count = dao.allJobs().count { it.state in setOf("WAITING_API", "FAILED", "RETRY") }
        dao.resumeJobs()
        schedule()
        return count
    }
    suspend fun enqueueRetry(rawId: String, forceCloud: Boolean = false) {
        lock.withLock {
            val raw = dao.raw(rawId) ?: error("原文已不存在")
            require(raw.state != "CONFIRMED" && dao.allSuggestions().none { it.rawId == rawId }) { "该通知已有识别结果，不能重复提取" }
            database.withTransaction {
                dao.putRaw(raw.copy(state = "PENDING", info = if (forceCloud) "已请求云端复核，等待处理" else "已加入重新识别队列"))
                dao.putJob(MessageJob(rawId, forceCloud = forceCloud))
            }
        }
        schedule()
    }
    suspend fun retry(rawId: String) {
        enqueueRetry(rawId)
        processRaw(rawId)
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
            dao.deleteJob(rawId)
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
            backup.raw.filter { raw -> raw.state == "PENDING" && backup.suggestions.none { it.rawId == raw.id } }
                .forEach { dao.putJob(MessageJob(it.id)) }
        }
        previousIds.forEach { reminders.cancel(it) }; reschedule(); schedule()
    }
    suspend fun clear() = lock.withLock {
        dao.allEvents().forEach { reminders.cancel(it.id) }
        database.withTransaction { dao.clearEvents(); dao.clearRaw() }
    }
}
