package dev.auralife.client.data

/**
 * 登录态判定 + 本地优先退出流程（纯逻辑，可 JVM 单测）。
 *
 * 服务端约定（reports/adaptation-spec.md §1.3）：
 * - 业务失败是 HTTP 200 + {ok:false, reason}，只有 reason 是「没登录」才踢回登录页
 * - 「只有主人能这么做」这类权限提示只提示，不能把登录态清掉
 * - 真 401 只出现在 /api/stream?ticket= 与 /api/media/:id
 */
object Session {
    const val REASON_NOT_LOGGED_IN = "没登录"
    const val REASON_OWNER_ONLY = "只有主人能这么做"

    fun isLoginLost(reason: String): Boolean = reason.trim().contains(REASON_NOT_LOGGED_IN)

    fun isOwnerOnly(reason: String): Boolean = reason.trim().contains(REASON_OWNER_ONLY)
}

/**
 * AND-2：退出登录必须「本地优先」——
 * 1) 立即清本地 token / 登录态、停 Live、停前台服务、回 ConnectScreen；
 * 2) POST /api/logout 只做 best-effort（3~5s 超时，失败忽略），不能阻塞导航。
 */
object LocalLogout {
    const val REMOTE_TIMEOUT_MS = 4_000L

    /**
     * 顺序固定：先本地清，再停连接/服务，最后异步 best-effort 通知服务端。
     * 远端动作抛异常也必须吞掉，不影响本地登出。
     */
    fun run(
        clearSession: () -> Unit,
        stopLive: () -> Unit,
        stopService: () -> Unit,
        remoteBestEffort: () -> Unit,
    ) {
        clearSession()
        runCatching { stopLive() }
        runCatching { stopService() }
        runCatching { remoteBestEffort() }
    }
}