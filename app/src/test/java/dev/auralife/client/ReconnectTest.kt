package dev.auralife.client

import dev.auralife.client.net.Reconnect
import dev.auralife.client.net.TicketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test
    fun backoffFollowsCappedExponentialCurve() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        val actual = expected.indices.map { Reconnect.delayMillis(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun negativeAttemptIsTreatedAsZero() {
        assertEquals(1_000L, Reconnect.delayMillis(-3))
    }

    @Test
    fun nextAttemptStopsAtFive() {
        assertEquals(1, Reconnect.nextAttempt(0))
        assertEquals(2, Reconnect.nextAttempt(1))
        assertEquals(5, Reconnect.nextAttempt(4))
        assertEquals(5, Reconnect.nextAttempt(5))
        assertEquals(5, Reconnect.nextAttempt(99))
    }

    @Test
    fun legacyDetectionMatchesVersionGate() {
        assertTrue(Reconnect.isLegacyVersion("1.78.90"))
        assertTrue(Reconnect.isLegacyVersion("1.70"))
        assertTrue(Reconnect.isLegacyVersion("v1.78.9"))
        assertFalse(Reconnect.isLegacyVersion("1.78.91"))
        assertFalse(Reconnect.isLegacyVersion("1.79.0"))
        assertFalse(Reconnect.isLegacyVersion("2.0.0"))
    }

    @Test
    fun unparsableVersionIsNotLegacy() {
        assertFalse(Reconnect.isLegacyVersion(""))
        assertFalse(Reconnect.isLegacyVersion("不是版本号"))
        assertFalse(Reconnect.isLegacyVersion("1.x.91"))
    }

    @Test
    fun copyMatchesWebVerbatim() {
        assertEquals("连接断了，正在自动重连…", Reconnect.MSG_DISCONNECTED)
        assertEquals("实时连接凭证获取失败，正在重试…", Reconnect.MSG_TICKET_FAILED)
        assertEquals("连接恢复了", Reconnect.MSG_RECOVERED)
        assertEquals(
            "这一段聊得有点长了，接着换新的一段继续（之前的聊天记录都还在）",
            Reconnect.MSG_ROTATE_CHAT,
        )
        assertEquals("刚才那段聊得长，已经换到新的一段继续", Reconnect.MSG_ROTATE_OTHER)
    }

    @Test
    fun ticketExceptionCarriesReason() {
        assertEquals("拿不到票据", TicketException("").message)
        assertEquals("服务器忙", TicketException("服务器忙").message)
    }
}