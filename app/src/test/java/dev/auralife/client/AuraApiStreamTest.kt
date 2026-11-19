package dev.auralife.client

import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Message
import dev.auralife.client.net.StreamListener
import dev.auralife.client.net.TicketException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地协议回归：SSE 取票（一次性 ticket）、取票失败、旧服务端回退 ?token=、
 * 票据 401 不误踢登录、业务失败「没登录」才踢登录。
 */
class AuraApiStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuraApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AuraApi(server.url("/").toString().trimEnd('/'), "tok-1")
    }

    @After
    fun tearDown() {
        AuraApi.forget(api)
        server.shutdown()
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    @Test
    fun streamFetchesOneTimeTicketThenConnectsWithIt() {
        server.enqueue(json("""{"ok":true,"ticket":"T-123"}"""))
        server.enqueue(sse(": connected\n\n: ping\n\n"))

        var opened = 0
        var closed: Throwable? = null
        api.stream(StreamListener(onOpen = { opened++ }, onClosed = { closed = it }))

        assertEquals(1, opened)
        assertEquals(null, closed)
        val ticketReq = server.takeRequest()
        assertEquals("POST", ticketReq.method)
        assertEquals("/api/stream-ticket", ticketReq.path)
        assertEquals("Bearer tok-1", ticketReq.getHeader("Authorization"))
        val streamReq = server.takeRequest()
        assertEquals("GET", streamReq.method)
        assertTrue(streamReq.path!!, streamReq.path!!.startsWith("/api/stream?ticket=T-123"))
        assertFalse(streamReq.path!!.contains("token="))
    }

    @Test
    fun ticketFailureBecomesTicketExceptionForRetryMessage() {
        server.enqueue(json("""{"ok":false,"reason":"服务器忙"}"""))
        var closed: Throwable? = null
        api.stream(StreamListener(onOpen = {}, onClosed = { closed = it }))
        assertTrue(closed is TicketException)
        assertEquals("服务器忙", closed!!.message)
    }

    @Test
    fun oldServerFallsBackToDeprecatedTokenQuery() {
        server.enqueue(json("""{"ok":false,"reason":"不认识这个接口：/api/stream-ticket"}"""))
        server.enqueue(sse(": connected\n\n"))

        api.stream(StreamListener(onOpen = {}, onClosed = {}))
        assertTrue(api.legacyStream)
        server.takeRequest() // stream-ticket
        val streamReq = server.takeRequest()
        assertTrue(streamReq.path!!, streamReq.path!!.startsWith("/api/stream?token=tok-1"))
    }

    @Test
    fun expiredTicketDoesNotKickLogin() {
        server.enqueue(json("""{"ok":true,"ticket":"T-expired"}"""))
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"reason":"没登录"}"""),
        )
        var authLost = 0
        api.addAuthLostListener { authLost++ }
        var closed: Throwable? = null
        api.stream(StreamListener(onOpen = {}, onClosed = { closed = it }))

        assertEquals("SSE 票据失效", closed!!.message)
        assertEquals(0, authLost) // 新协议：重新取票即可，不算登录态失效
    }

    @Test
    fun businessNotLoggedInKicksLoginButOwnerOnlyDoesNot() {
        var authLost = 0
        api.addAuthLostListener { authLost++ }

        server.enqueue(json("""{"ok":false,"reason":"没登录"}"""))
        val r1 = api.call("api/state")
        assertEquals(false, r1.optBoolean("ok"))
        assertEquals("没登录", r1.optString("reason"))
        assertEquals(1, authLost)

        server.enqueue(json("""{"ok":false,"reason":"只有主人能这么做"}"""))
        val r2 = api.call("api/state")
        assertEquals("只有主人能这么做", r2.optString("reason"))
        assertEquals(1, authLost) // 权限提示不踢登录
    }

    @Test
    fun dispatchesMessageTypingRotateAndIgnoresUnknownEvent() {
        server.enqueue(json("""{"ok":true,"ticket":"T-1"}"""))
        server.enqueue(
            sse(
                ": connected\n\n" +
                    "data: {\"type\":\"message\",\"chatId\":\"owner\",\"message\":{\"id\":\"m1\",\"who\":\"bot\",\"text\":\"hi\",\"kind\":\"text\",\"t\":5}}\n\n" +
                    "data: {\"type\":\"typing\",\"chatId\":\"owner\",\"on\":true}\n\n" +
                    "data: {\"type\":\"rotate\",\"at\":9,\"chatId\":\"owner\",\"tokens\":100,\"window\":200,\"ratio\":50,\"source\":\"auto\"}\n\n" +
                    "data: {\"type\":\"brand-new-event\",\"x\":1}\n\n",
            ),
        )
        var chatId = ""
        var msg: Message? = null
        var typing: Boolean? = null
        var rotates = 0
        api.stream(
            StreamListener(
                onOpen = {},
                onClosed = {},
                onMessage = { cid, m -> chatId = cid; msg = m },
                onTyping = { _, on -> typing = on },
                onRotate = { rotates++ },
            ),
        )
        assertEquals("owner", chatId)
        assertNotNull(msg)
        assertEquals("m1", msg!!.id)
        assertEquals("hi", msg!!.text)
        assertEquals(true, typing)
        assertEquals(1, rotates) // 未知事件被忽略，不影响后续
    }
}