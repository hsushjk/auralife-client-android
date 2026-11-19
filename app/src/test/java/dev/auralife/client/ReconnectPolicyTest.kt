package dev.auralife.client

import dev.auralife.client.net.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台重连策略单测（task-10）：
 * - 后台：10s/次 × 最多 3 次 → 「连接失败」，停止自动重试
 * - 网络恢复 / 回前台 / 手动重试：立即重连并复位
 * - 前台：1s→2s→4s→8s→16s→30s… 无限退避，不放弃
 */
class ReconnectPolicyTest {

    private fun policy() = ReconnectPolicy(backgroundDelayMs = 10_000L, backgroundMaxRetries = 3)

    @Test
    fun backgroundRetriesThreeTimesThenGivesUp() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)

        // 3 次重试都是 10s 一次，且都还没放弃
        repeat(3) { i ->
            val d = p.onRetryFailed(foreground = false)
            assertFalse("第 ${i + 1} 次不应放弃", d.giveUp)
            assertEquals(10_000L, d.delayMs)
        }
        assertEquals(3, p.backgroundRetries)
        assertFalse(p.failed)

        // 第 3 次重试也失败 → 进入「连接失败」，不再重试
        val giveUp = p.onRetryFailed(foreground = false)
        assertTrue(giveUp.giveUp)
        assertTrue(p.failed)
    }

    @Test
    fun backgroundBudgetResetsAfterReconnect() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)
        repeat(2) { p.onRetryFailed(foreground = false) }
        assertEquals(2, p.backgroundRetries)

        // 连上了：额度清零
        p.onConnected()
        assertEquals(0, p.backgroundRetries)
        assertFalse(p.failed)

        // 再次掉线：重新给 3 次机会
        p.onConnectionLost(foreground = false)
        repeat(3) { assertFalse(p.onRetryFailed(foreground = false).giveUp) }
        assertTrue(p.onRetryFailed(foreground = false).giveUp)
    }

    @Test
    fun newDisconnectResetsFailedStateAndBudget() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)
        repeat(3) { p.onRetryFailed(foreground = false) }
        assertTrue(p.onRetryFailed(foreground = false).giveUp)
        assertTrue(p.failed)

        // 新一段断线（比如重新连上又掉）：失败状态和计数都清零
        p.onConnectionLost(foreground = false)
        assertFalse(p.failed)
        assertEquals(0, p.backgroundRetries)
        val d = p.onRetryFailed(foreground = false)
        assertFalse(d.giveUp)
        assertEquals(10_000L, d.delayMs)
    }

    @Test
    fun networkRecoveryRetriesImmediatelyAndClearsFailed() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)
        repeat(3) { p.onRetryFailed(foreground = false) }
        assertTrue(p.onRetryFailed(foreground = false).giveUp)
        assertTrue(p.failed)

        // 网络从无到有：立即重连（复位失败状态与计数）
        p.onRetryRequested(foreground = false)
        assertFalse(p.failed)
        assertEquals(0, p.backgroundRetries)

        // 立即尝试又失败：重新按 10s 退避，而不是直接放弃
        val d = p.onRetryFailed(foreground = false)
        assertFalse(d.giveUp)
        assertEquals(10_000L, d.delayMs)
        assertEquals(1, p.backgroundRetries)
    }

    @Test
    fun returnToForegroundAutoRetriesOnceThenRegularBackoff() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)
        repeat(3) { p.onRetryFailed(foreground = false) }
        assertTrue(p.onRetryFailed(foreground = false).giveUp)

        // 回前台：自动重试一次（立即），并切回前台策略
        p.onRetryRequested(foreground = true)
        assertEquals(ReconnectPolicy.Mode.FOREGROUND, p.mode)
        assertFalse(p.failed)

        // 自动重试失败后按前台常规退避：1s→2s→4s…
        assertEquals(1_000L, p.onRetryFailed(foreground = true).delayMs)
        assertEquals(2_000L, p.onRetryFailed(foreground = true).delayMs)
        assertEquals(4_000L, p.onRetryFailed(foreground = true).delayMs)
    }

    @Test
    fun foregroundBackoffGrowsAndNeverGivesUp() {
        val p = policy()
        p.reset(foreground = true)
        p.onConnectionLost(foreground = true)

        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        expected.forEach { want ->
            val d = p.onRetryFailed(foreground = true)
            assertFalse(d.giveUp)
            assertEquals(want, d.delayMs)
        }
        // 前台永远不进入「连接失败」
        assertFalse(p.failed)
    }

    @Test
    fun foregroundConnectResetsBackoff() {
        val p = policy()
        p.reset(foreground = true)
        p.onConnectionLost(foreground = true)
        assertEquals(1_000L, p.onRetryFailed(foreground = true).delayMs)
        assertEquals(2_000L, p.onRetryFailed(foreground = true).delayMs)

        // 连上后复位，下次掉线又从 1s 开始
        p.onConnected()
        assertEquals(0, p.foregroundAttempt)
        p.onConnectionLost(foreground = true)
        assertEquals(1_000L, p.onRetryFailed(foreground = true).delayMs)
    }

    @Test
    fun enteringBackgroundResetsBackgroundBudget() {
        val p = policy()
        p.reset(foreground = false)
        p.onConnectionLost(foreground = false)
        repeat(2) { p.onRetryFailed(foreground = false) }
        assertEquals(2, p.backgroundRetries)

        // 切到前台再回后台：后台额度重新从 3 次开始
        p.setForeground(true)
        assertEquals(ReconnectPolicy.Mode.FOREGROUND, p.mode)
        p.setForeground(false)
        assertEquals(ReconnectPolicy.Mode.BACKGROUND, p.mode)
        assertEquals(0, p.backgroundRetries)

        repeat(3) { assertFalse(p.onRetryFailed(foreground = false).giveUp) }
        assertTrue(p.onRetryFailed(foreground = false).giveUp)
    }
}