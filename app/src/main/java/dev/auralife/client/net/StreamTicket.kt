package dev.auralife.client.net

/**
 * POST /api/stream-ticket 的响应判定（纯逻辑，可 JVM 单测）。
 *
 * - {ok:true, ticket}          → 用票连 SSE
 * - 旧服务端（reason 含「不认识这个接口」）→ 回退旧 ?token= 协议
 * - 其它                       → 取票失败，走退避重试 + 「实时连接凭证获取失败，正在重试…」
 */
object StreamTicket {
    sealed class Result {
        data class Ticket(val ticket: String) : Result()
        /** 旧服务端没有该接口，回退 deprecated 的 ?token= 方式。 */
        object Legacy : Result()
        data class Failed(val reason: String) : Result()
    }

    fun parse(ok: Boolean, ticket: String, reason: String): Result {
        val t = ticket.trim()
        if (ok && t.isNotEmpty()) return Result.Ticket(t)
        if (reason.contains("不认识这个接口")) return Result.Legacy
        return Result.Failed(reason.trim().ifEmpty { "拿不到票据" })
    }
}