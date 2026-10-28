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
        database.withTransaction {
            val job = dao.job(id)?.takeIf { it.state == "PROCESSING" }
                ?: throw CancellationException("通知已清空或停止处理")
            dao.putJob(job.copy(candidate = true))
            dao.raw(id)?.let { raw -> dao.putRaw(raw.copy(info = "正在请求所选云端模型，请等待；原文已保存")) }
        }
    })
    val dao = database.dao()
    private val lock = Mutex()
    val events = dao.observeEvents()
    val rawMessages = dao.observeRaw()
    val suggestions = dao.observeSuggestions()
    val jobs = dao.observeJobs()
    val ignoredItems = dao.observeIgnored()

    private fun schedule() {
        if (preferences.context is cn.yiji.app.YijiApplication) ExtractionWork.schedule(preferences.context)
    }
    suspend fun ingest(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): String =
        ingestWithResult(title, body, source, packageName, receivedAt).id

    /** Synchronous entry for integrations and tests. Raw data is committed before inference. */
    suspend fun ingestWithResult(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): IngestResult {
        val queued = storeRaw(title, body, source, packageName, receivedAt)
        if (queued.disposition in setOf(IngestDisposition.DUPLICATE, IngestDisposition.IGNORED)) return queued
        processRaw(queued.id)
        return IngestResult(queued.id, if (dao.raw(queued.id)?.state == "IGNORED") IngestDisposition.IGNORED else IngestDisposition.PENDING)
    }

    /** Notification/UI entry: commit first, then hand durable work to WorkManager. */
    suspend fun enqueueWithResult(title: String, body: String, source: String, packageName: String = "manual", receivedAt: Long = System.currentTimeMillis()): IngestResult {
        val result = storeRaw(title, body, source, packageName, receivedAt)
        if (result.disposition == IngestDisposition.QUEUED) schedule()
        return result
    }

    /** QQ IDs distinguish identical text and make crash replay idempotent. */
    suspend fun enqueueQq(envelope: cn.yiji.app.qq.QqEnvelope, scheduleWork: Boolean = true): IngestResult {
        val result=lock.withLock {
            dao.raw(envelope.id)?.let { return@withLock IngestResult(it.id,IngestDisposition.DUPLICATE) }
            if (dao.isArchivedRaw(envelope.id)) return@withLock IngestResult(envelope.id, IngestDisposition.IGNORED)
            val o=org.json.JSONObject(envelope.payload)
            val sender=o.optJSONObject("senderInfo")?.let { it.optString("groupCard").ifBlank { it.optString("nickname") } }
                .orEmpty().ifBlank { o.optString("senderUin") }
            val body=o.optString("text").ifBlank { "[消息内容见结构化存档]" }
            val source=if(envelope.scene=="GROUP") "QQ 群聊 · $sender" else "QQ 私聊"
            val metadata=android.util.Base64.encodeToString(cn.yiji.app.qq.QqStore.pack(envelope.payload),android.util.Base64.NO_WRAP)
            val raw=RawMessage(id=envelope.id,title=o.optString("conversationName",envelope.peer.toString()).take(200),body=body,
                source=source,packageName="qq-protocol:${envelope.account}:${envelope.scene}:${envelope.peer}",
                receivedAt=o.optLong("timestamp")*1000,info=when(o.optString("contentState")) {
                    "expansion_failed" -> "长消息展开失败，已保存现有正文、原始字段和资源标识"
                    "partial" -> "含暂不支持的消息元素，已保存现有正文和原始字段"
                    else -> "完整原文已保存，等待本地筛选"
                },qqMetadata=metadata)
            database.withTransaction { dao.putRaw(raw);dao.putJob(MessageJob(raw.id)) }
            IngestResult(raw.id,IngestDisposition.QUEUED)
        }
        if(scheduleWork && result.disposition==IngestDisposition.QUEUED) schedule()
        return result
    }

    private suspend fun storeRaw(title: String, body: String, source: String, packageName: String, receivedAt: Long): IngestResult = lock.withLock {
        require(body.isNotBlank() || title.isNotBlank()) { "通知文本不能为空" }
        require(body.length <= 16000 && title.length <= 200) { "通知内容过长，请分段导入" }
        val clearedBefore = preferences.clearedBefore
        if (clearedBefore > 0 && packageName != "manual" && receivedAt <= clearedBefore)
            return@withLock IngestResult(newId(), IngestDisposition.IGNORED)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest((packageName + "|" + title + "|" + body).toByteArray()).joinToString("") { "%02x".format(it) }
        if (packageName != "manual") dao.archivedFingerprint(fingerprint)?.let {
            return@withLock IngestResult(it, IngestDisposition.IGNORED)
        }
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
        val named = if (apiProfiles.current.crossSourceContext) dao.allEvents()
            .filter { it.status == "ACTIVE" && AssociationMatcher.similarity(raw.title + raw.body.take(300), it.title) >= .20 }
            .sortedByDescending { it.updatedAt }.take(4) else emptyList()
        val candidates = (scoped + named).distinctBy { it.id }.take(8)
        return ExtractionInput(raw, previous.reversed(), candidates, forceCloud,
            candidates.map { it.id }.toSet() - scoped.map { it.id }.toSet())
    }

    suspend fun processRaw(rawId: String) {
        val original = lock.withLock {
            val raw = dao.raw(rawId) ?: return
            if (raw.state != "PENDING" || dao.claimJob(rawId, System.currentTimeMillis()) == 0) return
            dao.putRaw(raw.copy(info = "正在本地筛选；日程候选将交给所选云端模型提取"))
            raw
        }
        val job = dao.job(rawId)?.takeIf { it.state == "PROCESSING" } ?: return
        val extraction = try {
            if (extractor is ContextualScheduleExtractor) extractor.extract(input(original, job.forceCloud))
            else extractor.extract(original.title, original.body, original.receivedAt)
        } catch (e: CancellationException) { throw e }
        catch (e: ExtractionFailure) { failed(original, job, e); return }
        catch (_: Exception) { failed(original, job, ExtractionFailure("识别未完成，原文已保存，请重试识别或手动添加事项")); return }
        catch (_: LinkageError) { failed(original, job, ExtractionFailure("本地筛选模型加载失败，原文已保存，请重试或送云端复核")); return }
        if (!extraction.ignored && !extraction.needsReview && extraction.drafts.isEmpty()) {
            failed(original, job, ExtractionFailure("模型未返回事项草稿，请重试或手动添加"))
            return
        }
        lock.withLock {
            val current = dao.raw(rawId) ?: return@withLock
            val activeJob = dao.job(rawId)
            if (current.state != "PENDING" || activeJob?.state != "PROCESSING" || activeJob.updatedAt != job.updatedAt ||
                current.body != original.body || current.title != original.title) return@withLock
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
                    reason = if (target != null) "模型关联到已有事项，请核对后确认" else match.reason, engine = extraction.engine,
                    opensAt = draft.opensAt, timing = draft.timing, subtasks = draft.subtasks, keepSubtasks = draft.keepSubtasks)
            }
            database.withTransaction {
                dao.putRaw(current.copy(state = if (extraction.ignored) "IGNORED" else "PENDING", info = extraction.info))
                drafts.forEach { dao.putSuggestion(it) }
                dao.putJob((dao.job(rawId) ?: job).copy(state = if (extraction.needsReview) "REVIEW" else "DONE", updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun failed(original: RawMessage, job: MessageJob, failure: ExtractionFailure) = lock.withLock {
        val id = original.id
        val current = dao.raw(id) ?: return@withLock
        val activeJob = dao.job(id)
        if (current.state != "PENDING" || activeJob?.state != "PROCESSING" || activeJob.updatedAt != job.updatedAt ||
            current.body != original.body || current.title != original.title) return@withLock
        val attempts = job.attempts + 1
        val retry = failure.retryable && attempts < 3
        val state = if (failure.waitingForApi) "WAITING_API" else if (retry) "RETRY" else "FAILED"
        val delay = maxOf(60_000L * (1L shl minOf(attempts - 1, 4)), failure.retryAfterMillis)
        database.withTransaction {
            dao.putRaw(current.copy(info = failure.userMessage + if (retry) "（等待自动重试 " + attempts + "/2）"
                else if (failure.retryable) "（自动重试已停止，可手动重试或切换配置）" else ""))
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
            val job = dao.job(rawId)
            require(job?.state != "PROCESSING") { "这条通知正在识别，请等待当前请求完成" }
            if (job?.state == "QUEUED") {
                if (forceCloud && !job.forceCloud) dao.putJob(job.copy(forceCloud = true))
                return@withLock
            }
            database.withTransaction {
                dao.putRaw(raw.copy(state = "PENDING", info = if (forceCloud) "已请求云端复核，等待处理" else "已加入重新识别队列"))
                dao.putJob(MessageJob(rawId, forceCloud = forceCloud))
            }
        }
        schedule()
    }

    suspend fun completeBody(rawId: String, title: String, body: String) = lock.withLock {
        require(body.isNotBlank() && body.length <= 16000 && title.length <= 200) { "请填写完整正文，最多16000字" }
        database.withTransaction {
            val raw = dao.raw(rawId) ?: error("原文已不存在")
            require(raw.state != "CONFIRMED" && dao.allSuggestions().none { it.rawId == rawId && it.state == "CONFIRMED" }) { "通知已有确认事项，请从导入文本添加补充通知" }
            require(dao.job(rawId)?.state != "PROCESSING") { "这条通知正在识别，请等待当前请求完成后补全" }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest((raw.packageName + "|" + title + "|" + body).toByteArray())
                .joinToString("") { "%02x".format(it) }
            dao.deleteSuggestions(rawId)
            dao.putRaw(raw.copy(title = title, body = body, fingerprint = fingerprint, state = "PENDING", info = "正文已补全，等待重新识别"))
            dao.putJob(MessageJob(rawId, forceCloud = true))
        }
        schedule()
    }

    /** Rescue only automatic v2.1.0 ignores; user-dismissed drafts are left alone. */
    suspend fun recoverMisclassifiedNotices(): Int = lock.withLock {
        val withSuggestions = dao.allSuggestions().map { it.rawId }.toSet()
        val recover = dao.allRaw().filter { raw -> raw.state == "IGNORED" &&
            (raw.info.startsWith("云端复核为聊天") || raw.info.startsWith("TinyBERT 二分类") && NoticeSafety.protects(raw.title, raw.body)) }
            .filter { raw -> raw.id !in withSuggestions }
        database.withTransaction {
            recover.forEach { raw ->
                dao.putRaw(raw.copy(state = "PENDING", info = "旧版自动忽略结果已恢复待核对；可补全正文或送云端复核"))
                dao.putJob(MessageJob(raw.id, state = "DONE", candidate = true))
            }
        }
        recover.size
    }

    suspend fun manualSuggestion(rawId: String): String = lock.withLock {
        database.withTransaction {
            val raw = dao.raw(rawId) ?: error("原文已不存在")
            require(raw.state != "CONFIRMED" && dao.allSuggestions().none { it.rawId == rawId }) { "该通知已有识别结果，请直接核对" }
            val suggestion = Suggestion(rawId = rawId, title = "", category = "其他", kind = "SCHEDULE",
                note = raw.body, reason = "手动整理原文，请填写标题及时间", engine = "手动整理")
            dao.deleteJob(rawId)
            dao.putRaw(raw.copy(state = "PENDING", info = "已转为手动整理，请核对填写"))
            dao.putSuggestion(suggestion)
            suggestion.id
        }
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
            val resolved = if (type == "UPDATE") draft.resolvedAgainst(target?.draft()) else draft
            if (type in setOf("NEW", "UPDATE")) resolved.validationError()?.let { error(it) }
            val event = when (type) {
                "NEW" -> Event(title = resolved.title, category = resolved.category, kind = resolved.kind).withDraft(resolved, reminder)
                "UPDATE" -> target!!.withDraft(resolved, reminder)
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
    suspend fun setSubtaskDone(eventId: String, subtaskId: String, done: Boolean) = lock.withLock {
        var saved: Event? = null
        database.withTransaction {
            val before = dao.event(eventId) ?: error("事项已不存在")
            require(before.subtasks.any { it.id == subtaskId }) { "子任务已不存在" }
            val after = before.copy(subtasks = before.subtasks.map { if (it.id == subtaskId) it.copy(done = done) else it },
                updatedAt = maxOf(System.currentTimeMillis(), before.updatedAt + 1))
            dao.putEvent(after)
            dao.putHistory(History(eventId = eventId, action = if (done) "完成子任务" else "恢复子任务",
                before = BackupCodec.eventJson(before).toString(), after = BackupCodec.eventJson(after).toString()))
            saved = after
        }
        reminders.schedule(saved!!)
    }
    suspend fun dismiss(rawId: String) = lock.withLock {
        database.withTransaction {
            archiveRaw(rawId)
        }
    }
    suspend fun dismissSuggestion(id: String) = lock.withLock {
        database.withTransaction {
            val suggestion = dao.suggestion(id) ?: error("建议已不存在")
            require(suggestion.state == "PENDING") { "这条建议已经处理" }
            val siblings = dao.allSuggestions().filter { it.rawId == suggestion.rawId && it.id != id }
            if (siblings.none { it.state in setOf("PENDING", "CONFIRMED") }) archiveRaw(suggestion.rawId)
            else {
                val raw = dao.raw(suggestion.rawId) ?: error("原文已不存在")
                archive("SUGGESTION", id, suggestion.title, raw.source, raw.body,
                    Backup(emptyList(), listOf(raw), listOf(suggestion), emptyList(), emptyList()))
                dao.putSuggestion(suggestion.copy(state = "DISMISSED"))
                reconcileRaw(suggestion.rawId)
            }
        }
    }
    private suspend fun reconcileRaw(rawId: String) {
        val siblings = dao.allSuggestions().filter { it.rawId == rawId }
        val state = when {
            siblings.any { it.state == "PENDING" } -> "PENDING"
            dao.job(rawId)?.state == "REVIEW" -> "PENDING"
            siblings.any { it.state == "CONFIRMED" } -> "CONFIRMED"
            else -> "IGNORED"
        }
        dao.raw(rawId)?.let { dao.putRaw(it.copy(state = state)) }
    }
    private suspend fun archive(kind: String, traceId: String, title: String, source: String, preview: String,
        backup: Backup, links: List<SourceLink> = emptyList(), fingerprint: String = "") {
        val payload = org.json.JSONObject(BackupCodec.encode(backup)).put("savedLinks", org.json.JSONArray(links.map {
            org.json.JSONObject().put("eventId", it.eventId).put("rawId", it.rawId)
        })).toString()
        dao.putIgnored(IgnoredItem(traceId = traceId, kind = kind, title = title, source = source,
            preview = preview.take(500), payload = payload, fingerprint = fingerprint))
    }

    /** Caller holds the repository lock and transaction; late inference cannot recreate deleted raw data. */
    private suspend fun archiveRaw(id: String) {
        val raw = dao.raw(id) ?: return
        archive("RAW", id, raw.title.ifBlank { raw.body.take(40) }, raw.source, raw.body,
            Backup(emptyList(), listOf(raw), dao.allSuggestions().filter { it.rawId == id }, emptyList(), emptyList()),
            dao.allLinks().filter { it.rawId == id }, raw.fingerprint)
        dao.deleteRaw(id)
    }

    suspend fun delete(id: String) = lock.withLock {
        database.withTransaction {
            val event = dao.event(id) ?: error("事项已不存在")
            val sources = dao.sources(id)
            archive("EVENT", id, event.title, sources.firstOrNull()?.source ?: "手动录入", event.note,
                Backup(listOf(event), sources, emptyList(), emptyList(), dao.history(id)),
                dao.allLinks().filter { it.eventId == id })
            dao.deleteEvent(id)
        }
        reminders.cancel(id)
    }
    suspend fun deleteRaw(id: String) = lock.withLock { database.withTransaction { archiveRaw(id) } }

    /** Restores snapshots, history and surviving links; the scheduler skips elapsed reminder points. */
    suspend fun restoreIgnored(id: String) = lock.withLock {
        val item = dao.ignored(id) ?: error("忽略记录已不存在")
        val backup = BackupCodec.decode(item.payload)
        val links = org.json.JSONObject(item.payload).optJSONArray("savedLinks") ?: org.json.JSONArray()
        database.withTransaction {
            backup.events.forEach { event ->
                require(dao.event(event.id) == null) { "事项已存在，请返回事项列表查看" }
                dao.putEvent(event.copy(updatedAt = maxOf(System.currentTimeMillis(), event.updatedAt + 1)))
            }
            backup.raw.forEach { raw -> if (dao.raw(raw.id) == null) dao.putRaw(raw) }
            backup.suggestions.forEach { suggestion ->
                val current = dao.suggestion(suggestion.id)
                require(current == null || current.state == "DISMISSED") { "这条建议已处理，请返回通知箱查看" }
                require(dao.raw(suggestion.rawId)?.body == backup.raw.first { it.id == suggestion.rawId }.body) { "原文已修改，请重新整理当前原文" }
                dao.putSuggestion(suggestion)
            }
            backup.history.forEach { dao.putHistory(it) }
            for (index in 0 until links.length()) {
                val link = links.getJSONObject(index)
                val eventId = link.getString("eventId"); val rawId = link.getString("rawId")
                if (dao.event(eventId) != null && dao.raw(rawId) != null) dao.putLink(SourceLink(eventId, rawId))
            }
            if (item.kind != "EVENT") backup.raw.forEach { raw ->
                if (backup.suggestions.none { it.rawId == raw.id } && raw.state == "PENDING") dao.putJob(MessageJob(raw.id))
                else reconcileRaw(raw.id)
            }
            dao.deleteIgnored(id)
        }
        backup.events.forEach { dao.event(it.id)?.let { event -> reminders.schedule(event) } }
        if (item.kind != "EVENT" && backup.raw.any { raw -> raw.state == "PENDING" && backup.suggestions.none { it.rawId == raw.id } }) schedule()
    }

    suspend fun purgeIgnored(id: String) = lock.withLock { dao.deleteIgnored(id) }

    suspend fun purgeExpiredIgnored(now: Long = System.currentTimeMillis()): Int = lock.withLock {
        val days = preferences.ignoredRetentionDays
        if (days == 0) return@withLock 0
        dao.deleteIgnoredBefore(now - days * 86_400_000L)
    }

    suspend fun quickAdd(title: String, body: String, source: String, drafts: List<Draft>): List<String> = lock.withLock {
        require(body.isNotBlank() && body.length <= 16000 && title.length <= 200 && drafts.isNotEmpty()) { "请粘贴有效通知，最多16000字" }
        drafts.forEach { it.validationError()?.let { problem -> error(problem) } }
        val raw = RawMessage(title = title, body = body, source = source, state = "CONFIRMED", info = "已按本地规则预览直接加入待办")
        val saved = drafts.map { Event(title = it.title, category = it.category, kind = it.kind).withDraft(it, preferences.defaultReminder) }
        database.withTransaction {
            dao.putRaw(raw)
            saved.forEach { event ->
                dao.putEvent(event); dao.putLink(SourceLink(event.id, raw.id))
                dao.putHistory(History(eventId = event.id, action = "从粘贴预览添加", after = BackupCodec.eventJson(event).toString()))
            }
        }
        saved.forEach { reminders.schedule(it) }
        saved.map { it.id }
    }
    suspend fun reschedule() { dao.allEvents().forEach { reminders.schedule(it) } }
    suspend fun export(): String = lock.withLock { database.withTransaction {
        BackupCodec.encode(Backup(dao.allEvents(), dao.allRaw(), dao.allSuggestions(), dao.allLinks(), dao.allHistory(), dao.allIgnored()))
    } }
    suspend fun restore(text: String) = lock.withLock {
        val backup = BackupCodec.decode(text) // Validate completely before replacing anything.
        val previousIds = dao.allEvents().map { it.id }
        database.withTransaction {
            dao.clearEvents(); dao.clearRaw(); dao.clearIgnored()
            backup.events.forEach { dao.putEvent(it) }; backup.raw.forEach { dao.putRaw(it) }
            backup.suggestions.forEach { dao.putSuggestion(it) }; backup.links.forEach { dao.putLink(it) }
            backup.history.forEach { dao.putHistory(it) }
            backup.ignored.forEach { dao.putIgnored(it) }
            backup.raw.filter { raw -> raw.state == "PENDING" && backup.suggestions.none { it.rawId == raw.id } }
                .forEach { dao.putJob(MessageJob(it.id)) }
        }
        previousIds.forEach { reminders.cancel(it) }; reschedule(); schedule()
    }
    suspend fun clear() = cn.yiji.app.qq.QqIngress.exclusive {
        lock.withLock {
            val at = System.currentTimeMillis()
            preferences.markCleared(at)
            if (cn.yiji.app.qq.QqStore.exists(preferences.context))
                cn.yiji.app.qq.QqStore(preferences.context).use { it.discardPending(at) }
            dao.allEvents().forEach { reminders.cancel(it.id) }
            database.withTransaction { dao.clearEvents(); dao.clearRaw(); dao.clearIgnored() }
        }
    }
}
