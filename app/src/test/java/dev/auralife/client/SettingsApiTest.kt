package dev.auralife.client

import dev.auralife.client.net.SettingsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.91.78 客人档位取消后的请求体规则：客户端不再发送 level。 */
class SettingsApiTest {

    @Test
    fun addGuestBodyHasNoLevel() {
        val body = SettingsApi.addGuestBody("friend", "朋友", "备注")
        assertEquals("friend", body.getString("username"))
        assertEquals("朋友", body.getString("displayName"))
        assertEquals("备注", body.getString("note"))
        assertFalse("创建客人的请求体不应再带 level", body.has("level"))
        assertFalse(body.toString().contains("level"))
    }

    @Test
    fun guestPasswordBodyOnlyCarriesPassword() {
        val body = SettingsApi.guestPasswordBody("12345678")
        assertEquals("12345678", body.getString("password"))
        assertEquals(1, body.length())
        assertFalse(body.has("level"))
        assertTrue(body.toString().contains("password"))
    }
}