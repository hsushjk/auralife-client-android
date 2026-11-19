package dev.auralife.client

import dev.auralife.client.net.StreamTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTicketTest {
    @Test
    fun okTicketIsUsed() {
        val r = StreamTicket.parse(ok = true, ticket = " t1 ", reason = "")
        assertTrue(r is StreamTicket.Result.Ticket)
        assertEquals("t1", (r as StreamTicket.Result.Ticket).ticket)
    }

    @Test
    fun okWithoutTicketIsFailureWithDefaultReason() {
        val r = StreamTicket.parse(ok = true, ticket = "", reason = "")
        assertTrue(r is StreamTicket.Result.Failed)
        assertEquals("拿不到票据", (r as StreamTicket.Result.Failed).reason)
    }

    @Test
    fun unknownEndpointFallsBackToLegacyToken() {
        val r = StreamTicket.parse(
            ok = false,
            ticket = "",
            reason = "不认识这个接口：/api/stream-ticket",
        )
        assertEquals(StreamTicket.Result.Legacy, r)
    }

    @Test
    fun serverReasonIsPreservedForRetryMessage() {
        val r = StreamTicket.parse(ok = false, ticket = "", reason = "没登录")
        assertEquals("没登录", (r as StreamTicket.Result.Failed).reason)
    }
}