package dev.auralife.client

import dev.auralife.client.net.Message
import dev.auralife.client.net.MessageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {
    private fun msg(id: String, t: Long, text: String = id) = Message(id = id, who = "bot", text = text, t = t)

    @Test
    fun upsertInsertsInTimeOrderAndReturnsTrue() {
        val list = mutableListOf(msg("a", 100))
        assertTrue(MessageStore.upsert(list, msg("c", 300)))
        assertTrue(MessageStore.upsert(list, msg("b", 200)))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }

    @Test
    fun upsertExistingIdOverwritesWithoutDuplicate() {
        val list = mutableListOf(msg("a", 100, "old"))
        assertFalse(MessageStore.upsert(list, msg("a", 100, "new")))
        assertEquals(1, list.size)
        assertEquals("new", list[0].text)
    }

    @Test
    fun mergeDedupesBoundaryMessages() {
        val list = mutableListOf(msg("a", 100), msg("b", 200))
        val added = MessageStore.merge(list, listOf(msg("b", 200), msg("c", 300)))
        assertEquals(1, added)
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }

    @Test
    fun rotateDoesNotClearHistory() {
        // rotate 只代表换 session：合并新页后老记录原地保留
        val list = mutableListOf(msg("a", 100), msg("b", 200))
        MessageStore.merge(list, listOf(msg("c", 300)))
        assertEquals(3, list.size)
    }

    @Test
    fun dropOlderThanRemovesOnlyOlderMessages() {
        val list = mutableListOf(msg("a", 100), msg("b", 200), msg("c", 300))
        val dropped = MessageStore.dropOlderThan(list, 250)
        assertEquals(2, dropped)
        assertEquals(listOf("c"), list.map { it.id })
    }

    @Test
    fun dropOlderThanIgnoresZeroOrMissingBoundary() {
        val list = mutableListOf(msg("a", 100))
        assertEquals(0, MessageStore.dropOlderThan(list, 0))
        assertEquals(1, list.size)
    }
}