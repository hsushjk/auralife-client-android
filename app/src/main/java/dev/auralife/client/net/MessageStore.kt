package dev.auralife.client.net

/**
 * 消息列表的纯逻辑（可 JVM 单测）：
 * - SSE message 与分页拉取按 id upsert，边界重复不产生双气泡
 * - rotate 不归档、不清空：合并后老记录原地保留（reports/adaptation-spec.md §1.4）
 * - history 事件里 droppedBefore 之前的本地消息要删掉
 */
object MessageStore {
    /** 按 id 覆盖或按时间插入；返回 true 表示是新消息（不是覆盖）。 */
    fun upsert(list: MutableList<Message>, msg: Message): Boolean {
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) {
            list[idx] = msg
            return false
        }
        val at = list.indexOfFirst { it.t > msg.t }
        if (at < 0) list.add(msg) else list.add(at, msg)
        return true
    }

    /** 合并一页历史/最新消息，返回新增条数。 */
    fun merge(list: MutableList<Message>, fresh: List<Message>): Int {
        var added = 0
        for (m in fresh) if (upsert(list, m)) added++
        return added
    }

    /** 删掉 t < droppedBefore 的本地气泡，返回删除条数。 */
    fun dropOlderThan(list: MutableList<Message>, droppedBefore: Long): Int {
        if (droppedBefore <= 0) return 0
        val before = list.size
        list.removeAll { it.t in 1 until droppedBefore }
        return before - list.size
    }
}