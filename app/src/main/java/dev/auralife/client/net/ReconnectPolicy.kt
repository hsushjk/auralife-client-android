package dev.auralife.client.net

/**
 * 重连策略（纯 Kotlin，便于 JVM 单测）。
 *
 * 前台（App 在前台）：
 *  - 掉线后按 1s→2s→4s…30s 指数退避无限重试（沿用 §1.2 原策略）；
 *  - 回前台 / 网络恢复 / 手动重试：立即重连一次，之后继续常规退避。
 *
 * 后台（App 不在前台）：
 *  - 掉线后每 10 秒重连一次，最多 3 次；
 *  - 3 次都失败 → 进入「连接失败」，停止自动重试（不再空转耗电），
 *    由「网络恢复 / 回到 App / 点通知或聊天页重试」重新触发。
 *
 * 说明：策略只做决策，不持有线程；Live 负责 interrupt 退避等待或起新 worker。
 */
class ReconnectPolicy(
    private val backgroundDelayMs: Long = 10_000L,
    private val backgroundMaxRetries: Int = 3,
) {
    enum class Mode { FOREGROUND, BACKGROUND }

    data class Decision(
        /** 下一次尝试前等待多少毫秒（0 表示立刻） */
        val delayMs: Long = 0L,
        /** 放弃自动重试，进入「连接失败」 */
        val giveUp: Boolean = false,
    )

    var mode: Mode = Mode.FOREGROUND
        private set

    var foregroundAttempt: Int = 0
        private set

    /** 后台已经重试过几次（掉线后第一次重试前为 0） */
    var backgroundRetries: Int = 0
        private set

    /** 是否已进入「连接失败」（后台重试次数用尽） */
    var failed: Boolean = false
        private set

    fun reset(foreground: Boolean) {
        mode = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        foregroundAttempt = 0
        backgroundRetries = 0
        failed = false
    }

    /** App 前后台切换；切入后台时开启新一段后台重试额度。 */
    fun setForeground(foreground: Boolean) {
        val next = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        if (mode == next) return
        mode = next
        if (next == Mode.BACKGROUND) backgroundRetries = 0
    }

    /** 在线（SSE 曾连上）掉线：开始新一段断线，计数清零。 */
    fun onConnectionLost(foreground: Boolean) {
        mode = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        foregroundAttempt = 0
        backgroundRetries = 0
        failed = false
    }

    /** 连接成功：全部复位。 */
    fun onConnected() {
        foregroundAttempt = 0
        backgroundRetries = 0
        failed = false
    }

    /**
     * 一次连接尝试失败后调用：返回下一次是「等多久再试」还是「放弃」。
     * 前台无限退避；后台 10s × 最多 3 次。
     */
    fun onRetryFailed(foreground: Boolean): Decision {
        mode = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        if (mode == Mode.FOREGROUND) {
            val delay = Reconnect.delayMillis(foregroundAttempt)
            foregroundAttempt = Reconnect.nextAttempt(foregroundAttempt)
            return Decision(delayMs = delay)
        }
        if (backgroundRetries >= backgroundMaxRetries) {
            failed = true
            return Decision(giveUp = true)
        }
        backgroundRetries++
        return Decision(delayMs = backgroundDelayMs)
    }

    /**
     * 网络恢复 / 手动重试 / 回前台：立即重连并复位（清掉「连接失败」与后台计数）。
     */
    fun onRetryRequested(foreground: Boolean) {
        mode = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        foregroundAttempt = 0
        backgroundRetries = 0
        failed = false
    }

    fun delayForRetry(foreground: Boolean): Long {
        val next = if (foreground) Mode.FOREGROUND else Mode.BACKGROUND
        return if (next == Mode.FOREGROUND) Reconnect.delayMillis(foregroundAttempt) else backgroundDelayMs
    }
}