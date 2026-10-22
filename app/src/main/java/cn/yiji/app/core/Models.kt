package cn.yiji.app.core

data class Draft(
    val title: String = "",
    val category: String = "其他",
    val kind: String = "SCHEDULE",
    val startAt: Long? = null,
    val endAt: Long? = null,
    val dueAt: Long? = null,
    val location: String = "",
    val action: String = "",
    val note: String = "",
    val issues: List<String> = emptyList(),
    val opensAt: Long? = null,
    val timing: Timing = Timing(),
    val subtasks: List<Subtask> = emptyList(),
    val keepSubtasks: Boolean = false,
) {
    val anchor: Long? get() = if (kind == "DEADLINE") dueAt ?: opensAt ?: subtasks.filter { !it.done }.mapNotNull { it.dueAt }.minOrNull() else startAt
    fun validationError(): String? = when {
        title.isBlank() -> "请填写事项标题"
        title.length > 120 -> "标题最多 120 个字"
        kind !in setOf("SCHEDULE", "DEADLINE") -> "事项类型无效"
        timing.fields().any { !it.valid() } -> "时间依据未通过校验，请核对或清空对应时间"
        kind == "SCHEDULE" && startAt != null && endAt != null && endAt <= startAt -> "结束时间必须晚于开始时间"
        opensAt != null && dueAt != null && dueAt < opensAt -> "报名截止不能早于开放时间"
        subtasks.size > 40 || subtasks.any { it.title.isBlank() || it.title.length > 300 || !it.due.valid() } -> "请核对子任务内容和时间依据"
        subtasks.map { it.id }.distinct().size != subtasks.size -> "子任务标识重复"
        else -> null
    }
}

data class ExistingItem(val id: String, val draft: Draft, val status: String = "ACTIVE")
data class MatchSuggestion(val type: String, val targetId: String?, val reason: String, val score: Double)
data class TimeConflict(val firstId: String, val secondId: String, val startAt: Long, val endAt: Long)

object ConflictDetector {
    fun detect(items: List<ExistingItem>): List<TimeConflict> {
        val schedules = items.filter {
            it.status == "ACTIVE" && it.draft.kind == "SCHEDULE" &&
                it.draft.startAt != null && it.draft.endAt != null
        }.sortedBy { it.draft.startAt }
        val result = mutableListOf<TimeConflict>()
        for (i in schedules.indices) {
            val first = schedules[i]
            for (j in i + 1 until schedules.size) {
                val second = schedules[j]
                if (second.draft.startAt!! >= first.draft.endAt!!) break
                val start = maxOf(first.draft.startAt!!, second.draft.startAt!!)
                val end = minOf(first.draft.endAt!!, second.draft.endAt!!)
                if (start < end) result += TimeConflict(first.id, second.id, start, end)
            }
        }
        return result
    }
}

object AssociationMatcher {
    private fun normalized(value: String) = value.lowercase()
        .replace(Regex("[\\s\\p{Punct}，。；：【】《》]"), "")
    private fun grams(value: String): Set<String> = if (value.length < 2) setOf(value) else
        (0 until value.length - 1).map { value.substring(it, it + 2) }.toSet()
    fun similarity(a: String, b: String): Double {
        val x = normalized(a); val y = normalized(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        val gx = grams(x); val gy = grams(y)
        return 2.0 * gx.intersect(gy).size / (gx.size + gy.size)
    }
    fun suggest(draft: Draft, operation: String, items: List<ExistingItem>): MatchSuggestion {
        val change = operation in setOf("UPDATE", "CANCEL")
        val cancel = operation == "CANCEL"
        val candidates = items.filter { it.status == "ACTIVE" && it.draft.kind == draft.kind }
            .map { item ->
                val name = similarity(draft.title, item.draft.title)
                val days = if (draft.anchor != null && item.draft.anchor != null)
                    kotlin.math.abs(draft.anchor!! - item.draft.anchor!!) / 86_400_000.0 else null
                val dateClose = days != null && days <= 1.0 / 1440.0
                val evidence = name >= 0.72
                val acceptable = evidence && (change || dateClose || draft.anchor == null)
                Triple(item, if (acceptable) name * 0.85 + if (dateClose) 0.15 else 0.0 else 0.0, dateClose)
            }.sortedByDescending { it.second }
        val best = candidates.firstOrNull()
        if (best == null || best.second < 0.60) return MatchSuggestion("NEW", null,
            if (change) "模型判断为变更，但没有足够依据关联已有事项，请手动选择目标。" else "模型识别到待办事项，请核对提取结果。", 0.0)
        if (candidates.size > 1 && best.second - candidates[1].second < 0.08)
            return MatchSuggestion("NEW", null, "多个已有事项相近，请手动选择关联目标。", best.second)
        return when {
            cancel -> MatchSuggestion("CANCEL", best.first.id, "模型判断为取消，标题与已有事项相近。", best.second)
            change -> MatchSuggestion("UPDATE", best.first.id, "模型判断为变更，建议更新已有事项并保存历史。", best.second)
            best.third -> MatchSuggestion("MERGE", best.first.id, "标题和时间相近，建议把原通知关联到同一事项。", best.second)
            else -> MatchSuggestion("NEW", null, "标题相近但时间不同，按新的事项处理。", best.second)
        }
    }
}
