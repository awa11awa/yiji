package cn.yiji.app.core

/** Recall protection only: never invents a title, time, place or action. */
object NoticeSafety {
    fun isNoise(body: String, hasContext: Boolean): Boolean {
        if (hasContext) return false
        val text = java.text.Normalizer.normalize(body, java.text.Normalizer.Form.NFKC).trim()
        val reply = text.replace(Regex("[,，。.!！\\s]"), "")
        return text.isNotBlank() && (text.all { it.isDigit() || it.isWhitespace() } ||
            text.all { it in "。，,.!?！？、~～… " } ||
            reply in setOf("收到", "收到谢谢", "谢谢", "好的", "OK", "ok"))
    }
    private val subject = Regex("讲坛|讲座|班会|会议|课程|上课|调课|考试|体测|作业|报名|竞赛|培训|活动|材料|报告|缴费")
    private val notice = Regex("通知|各位同学|全体同学|举办|开展|主讲|讲座内容|具体安排|具体调整|原定|另行通知")
    private val change = Regex("调整|改期|延期|取消|变更|改为|改到|推迟|提前|补交|截止|提交|上交|参加|集合")

    fun protects(title: String, body: String): Boolean {
        val text = body.ifBlank { title }
        return subject.containsMatchIn(text) && (notice.containsMatchIn(text) || change.containsMatchIn(text))
    }

    fun mayBeIncomplete(body: String): Boolean {
        val value = body.trimEnd()
        return value.isBlank() || value.endsWith("…") || value.endsWith("...") || value.length >= 16000
    }

    const val INCOMPLETE_HINT = "通知可能只提供了摘要。这里显示的是已采集正文；请从来源应用复制完整消息，再点“补全正文”。"
}
