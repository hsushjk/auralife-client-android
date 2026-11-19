package dev.auralife.client.net

import org.json.JSONObject

/**
 * 引用消息（quote，服务端 v1.91.78 起）的纯逻辑：快照摘要、发送体、跳转失败文案。
 *
 * 刻意不依赖 Android，可跑 JVM 单测。规格见 reports/adaptation-spec-v19178.md §1.2-§1.6：
 * - quote = {id(完整消息 id), who:"user"|"bot", text(快照), at(ms), kind}
 * - 服务端 cleanQuote 要求 id 与 text 都非空，id 长度 < 6 会被静默丢弃
 * - 显示时直接用服务端返回的 text，不再二次摘要
 */
data class Quote(
    val id: String = "",
    val who: String = "user",
    val text: String = "",
    val at: Long = 0L,
    val kind: String = "text",
)

object QuoteRules {
    /** 服务端 text trim 后 slice(0, 300)。 */
    const val MAX_TEXT = 300

    /** 服务端 id trim 后 slice(0, 80)；长度 < 6 视为找不到/丢弃。 */
    const val MIN_ID_LEN = 6
    const val MAX_ID_LEN = 80

    const val MSG_LOCATE_MISS = "那条消息已经找不到了"
    const val MSG_NOT_LOADED = "那条消息不在当前加载的记录里"

    private val KINDS = setOf("text", "voice", "sticker", "image", "file")

    /** 被引用消息的快照摘要（客户端构造 & 显示规则一致）。 */
    fun summary(m: Message): String = when (m.kind) {
        "voice" -> if (m.heard.isNotBlank()) "[语音] " + m.heard else m.text.ifBlank { "[语音]" }
        "image" -> "[图片]"
        "sticker" -> if (m.name.isNotBlank()) "[表情 ${m.name}]" else "[表情]"
        "file" -> "[文件] " + m.fileName
        else -> m.text.take(MAX_TEXT)
    }

    /** 把一条消息变成可发送的引用快照；id 太短或文本为空返回 null（不产生 quote）。 */
    fun snapshot(m: Message): Quote? {
        val id = m.id.trim()
        val text = summary(m).trim()
        if (id.length < MIN_ID_LEN || text.isEmpty()) return null
        return Quote(
            id = id.take(MAX_ID_LEN),
            who = if (m.who == "bot") "bot" else "user",
            text = text.take(MAX_TEXT),
            at = m.t,
            kind = if (m.kind in KINDS) m.kind else "text",
        )
    }

    /** 发送体里的 quote 对象；字段不合法时返回 null（服务端也会静默丢弃，这里提前拦住）。 */
    fun wireJson(q: Quote): JSONObject? {
        val id = q.id.trim()
        val text = q.text.trim()
        if (id.length < MIN_ID_LEN || text.isEmpty()) return null
        return JSONObject()
            .put("id", id.take(MAX_ID_LEN))
            .put("who", if (q.who == "bot") "bot" else "user")
            .put("text", text.take(MAX_TEXT))
            .put("at", q.at)
            .put("kind", if (q.kind in KINDS) q.kind else "text")
    }

    /** 解析服务端消息里的 quote；缺 id/text 视为没有引用。 */
    fun fromJson(o: JSONObject?): Quote? {
        if (o == null) return null
        val id = o.optString("id").trim()
        val text = o.optString("text").trim()
        if (id.isEmpty() || text.isEmpty()) return null
        return Quote(
            id = id.take(MAX_ID_LEN),
            who = if (o.optString("who") == "bot") "bot" else "user",
            text = text.take(MAX_TEXT),
            at = o.optLong("at", 0L),
            kind = o.optString("kind", "text").let { if (it in KINDS) it else "text" },
        )
    }

    fun quoteName(q: Quote, botName: String): String = if (q.who == "bot") botName.ifBlank { "Ta" } else "你"

    /** 输入区预览条 / tooltip 用的一行文案。 */
    fun quoteLine(q: Quote, botName: String): String = "引用 ${quoteName(q, botName)}：${q.text}"

    /**
     * locate 失败时的提示：旧服务端返回「不认识这个接口」按找不到处理（web focusQuoted 同款），
     * 其它情况展示服务端 reason，reason 为空给兜底文案。
     */
    fun locateFailure(reason: String): String {
        val r = reason.trim()
        return if (r.contains("不认识这个接口")) MSG_NOT_LOADED else r.ifBlank { MSG_LOCATE_MISS }
    }
}