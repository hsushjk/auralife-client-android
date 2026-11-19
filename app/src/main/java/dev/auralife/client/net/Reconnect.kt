package dev.auralife.client.net

/**
 * 断线重连的纯逻辑：退避曲线、协议版本判断、连接提示文案。
 *
 * 这里刻意不依赖 Android / org.json，方便跑 JVM 单测。
 * 对应 reports/adaptation-spec.md §1.2：退避 min(30s, 1s * 2^min(5, n))，
 * 首次连上后 n 归零；服务端版本 < 1.78.91 时没有 /api/stream-ticket，回退旧 ?token=。
 */
object Reconnect {
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /** 用户可感知的连接提示，逐字与 web 一致（reports/adaptation-spec.md §1.2 / web-audit E1）。 */
    const val MSG_DISCONNECTED = "连接断了，正在自动重连…"
    const val MSG_TICKET_FAILED = "实时连接凭证获取失败，正在重试…"
    const val MSG_RECOVERED = "连接恢复了"

    /**
     * 后台重连（10s × 3 次）都失败后的状态：
     * 停止自动重试，等网络恢复 / 回到 App / 手动点「重试」。
     */
    const val MSG_CONNECT_FAILED = "连接失败"
    const val MSG_CONNECT_FAILED_HINT = "连接失败，回到 App 重试"

    /** rotate 只代表换 session，聊天记录原地保留，禁止「已归档」。 */
    const val MSG_ROTATE_CHAT = "这一段聊得有点长了，接着换新的一段继续（之前的聊天记录都还在）"
    const val MSG_ROTATE_OTHER = "刚才那段聊得长，已经换到新的一段继续"

    /** 第 n 次失败后等待的毫秒数：1s,2s,4s,8s,16s,30s,30s… */
    fun delayMillis(attempt: Int): Long {
        val doublings = attempt.coerceIn(0, 5)
        return (BASE_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
    }

    /** 失败次数自增，供 delayMillis 用；封顶后一直停在 30s。 */
    fun nextAttempt(attempt: Int): Int = if (attempt >= 5) 5 else attempt + 1

    /** 服务端版本号（如 "1.78.91"）是否低于支持 stream-ticket 的版本。 */
    fun isLegacyVersion(version: String): Boolean {
        val want = parseVersion("1.78.91") ?: return false
        val got = parseVersion(version) ?: return false
        return compareVersion(got, want) < 0
    }

    /**
     * 服务端是否支持引用消息（v1.91.78 起）。版本号缺失/解析失败时按支持处理：
     * 旧服务端会静默忽略 quote、locate 返回「不认识这个接口」，客户端已有兜底。
     */
    fun supportsQuote(version: String): Boolean {
        val got = parseVersion(version) ?: return true
        val want = parseVersion("1.91.78") ?: return true
        return compareVersion(got, want) >= 0
    }

    fun parseVersion(version: String): IntArray? {
        val raw = version.trim().removePrefix("v").removePrefix("V")
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i].trim()
            val n = p.toIntOrNull() ?: return null
            if (n < 0) return null
            out[i] = n
        }
        return out
    }

    fun compareVersion(a: IntArray, b: IntArray): Int {
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }
}

/** 一次 SSE 连接被服务端正常结束（EOF / 读超时 / 主动 stop）都算需要重连的失败。 */
class TicketException(reason: String) : java.io.IOException(reason.ifBlank { "拿不到票据" })