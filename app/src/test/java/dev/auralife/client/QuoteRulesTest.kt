package dev.auralife.client

import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Message
import dev.auralife.client.net.MessageStore
import dev.auralife.client.net.Quote
import dev.auralife.client.net.QuoteRules
import dev.auralife.client.net.toMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.91.78 引用消息快照规则（服务端 cleanQuote / 摘要规则）的纯逻辑单测。 */
class QuoteRulesTest {

    private fun msg(
        who: String = "user",
        text: String = "",
        kind: String = "text",
        t: Long = 1_700_000_000_000L,
        heard: String = "",
        name: String = "",
        fileName: String = "",
    ) = Message(id = "m-012345", who = who, text = text, kind = kind, t = t, heard = heard, name = name, fileName = fileName)

    @Test
    fun voiceSummaryUsesHeard() {
        assertEquals("[语音] 你好呀", QuoteRules.summary(msg(kind = "voice", text = "raw", heard = "你好呀")))
        // 没有 heard 时用消息自身的 text（不是加前缀），空文本才退化成 [语音]
        assertEquals("raw", QuoteRules.summary(msg(kind = "voice", text = "raw")))
        assertEquals("[语音]", QuoteRules.summary(msg(kind = "voice", text = "")))
    }

    @Test
    fun imageStickerFileSummary() {
        assertEquals("[图片]", QuoteRules.summary(msg(kind = "image")))
        assertEquals("[表情 猫猫]", QuoteRules.summary(msg(kind = "sticker", name = "猫猫")))
        assertEquals("[表情]", QuoteRules.summary(msg(kind = "sticker")))
        assertEquals("[文件] a.pdf", QuoteRules.summary(msg(kind = "file", fileName = "a.pdf")))
    }

    @Test
    fun textSummarySlicesTo300() {
        val long = "x".repeat(400)
        assertEquals(300, QuoteRules.summary(msg(text = long)).length)
        assertEquals("hi", QuoteRules.summary(msg(text = "hi")))
    }

    @Test
    fun snapshotNormalizesWhoKindAndAt() {
        val q = QuoteRules.snapshot(msg(who = "bot", text = "hello", kind = "weird", t = 42L))
        assertNotNull(q)
        assertEquals("m-012345", q!!.id)
        assertEquals("bot", q.who)
        assertEquals("hello", q.text)
        assertEquals(42L, q.at)
        assertEquals("text", q.kind)

        // who 非 bot 一律 user
        assertEquals("user", QuoteRules.snapshot(msg(who = "owner", text = "hi"))!!.who)
    }

    @Test
    fun snapshotRejectsShortIdOrEmptyText() {
        assertNull(QuoteRules.snapshot(msg(text = "hi").copy(id = "abc"))) // id < 6
        assertNull(QuoteRules.snapshot(msg(text = ""))) // text 空
        assertNull(QuoteRules.snapshot(msg(text = "   ")))
    }

    @Test
    fun wireJsonCarriesServerFields() {
        val q = Quote(id = "m-012345", who = "bot", text = "hi", at = 7L, kind = "voice")
        val o = QuoteRules.wireJson(q)!!
        assertEquals("m-012345", o.getString("id"))
        assertEquals("bot", o.getString("who"))
        assertEquals("hi", o.getString("text"))
        assertEquals(7L, o.getLong("at"))
        assertEquals("voice", o.getString("kind"))

        // 只有 id、没有 text 不产生 quote（服务端会静默丢弃）
        assertNull(QuoteRules.wireJson(Quote(id = "m-012345", text = "")))
        assertNull(QuoteRules.wireJson(Quote(id = "abc", text = "hi")))
    }

    @Test
    fun fromJsonParsesAndRejectsBadData() {
        val o = JSONObject("""{"id":"m-012345","who":"bot","text":"引用内容","at":9,"kind":"image"}""")
        val q = QuoteRules.fromJson(o)!!
        assertEquals("m-012345", q.id)
        assertEquals("bot", q.who)
        assertEquals("引用内容", q.text)
        assertEquals(9L, q.at)
        assertEquals("image", q.kind)

        assertNull(QuoteRules.fromJson(JSONObject("""{"id":"m-012345"}""")))
        assertNull(QuoteRules.fromJson(JSONObject("""{"id":"m-012345","text":""}""")))
        assertNull(QuoteRules.fromJson(null))
        // 非法 kind 归 text、非 bot 归 user
        val bad = QuoteRules.fromJson(JSONObject("""{"id":"m-012345","who":"owner","text":"x","kind":"weird"}"""))!!
        assertEquals("user", bad.who)
        assertEquals("text", bad.kind)
    }

    @Test
    fun quoteNameAndLineFollowWebCopy() {
        val q = Quote(id = "m-012345", who = "bot", text = "hi")
        assertEquals("Aura", QuoteRules.quoteName(q, "Aura"))
        assertEquals("Ta", QuoteRules.quoteName(q, ""))
        assertEquals("你", QuoteRules.quoteName(q.copy(who = "user"), "Aura"))
        assertEquals("引用 Aura：hi", QuoteRules.quoteLine(q, "Aura"))
        assertEquals("引用 你：hi", QuoteRules.quoteLine(q.copy(who = "user"), "Aura"))
    }

    @Test
    fun locateFailureMapsOldServerToNotFound() {
        assertEquals(QuoteRules.MSG_LOCATE_MISS, QuoteRules.locateFailure("那条消息已经找不到了"))
        assertEquals(QuoteRules.MSG_LOCATE_MISS, QuoteRules.locateFailure(""))
        // 旧服务端不认识 locate：按「不在当前加载的记录里」处理，不显示接口错误原文
        assertEquals(QuoteRules.MSG_NOT_LOADED, QuoteRules.locateFailure("不认识这个接口：api/messages/locate"))
        assertFalse(QuoteRules.locateFailure("不认识这个接口：api/messages/locate").contains("不认识"))
    }

    @Test
    fun messageStoreKeepsQuoteWhenUpserting() {
        val list = mutableListOf<Message>()
        val q = Quote(id = "m-012345", who = "bot", text = "旧内容")
        val m = msg(text = "新内容").copy(quote = q)
        assertTrue(MessageStore.upsert(list, m))
        assertEquals(q, list[0].quote)
        // 同 id 覆盖（例如 ASR 转写后重发）也保留 quote
        val again = msg(text = "转写后的内容").copy(quote = q)
        assertFalse(MessageStore.upsert(list, again))
        assertEquals("转写后的内容", list[0].text)
        assertEquals(q, list[0].quote)
    }

    @Test
    fun toMessageParsesQuoteField() {
        val api = AuraApi(base = "http://127.0.0.1:2332", token = "t")
        try {
            val raw = JSONObject(
                """{"id":"m-abc123","who":"bot","text":"hello","kind":"text","t":5,
                   "quote":{"id":"m-000001","who":"user","text":"原始消息","at":4,"kind":"text"}}""",
            )
            val m = raw.toMessage()
            val q = m.quote
            assertNotNull(q)
            assertEquals("m-000001", q!!.id)
            assertEquals("user", q.who)
            assertEquals("原始消息", q.text)
            assertEquals(4L, q.at)

            // 无 quote 字段 → null，旧服务端兼容
            assertNull(JSONObject("""{"id":"m-abc123","text":"hi"}""").toMessage().quote)
        } finally {
            AuraApi.forget(api)
        }
    }
}