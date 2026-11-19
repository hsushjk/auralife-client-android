package dev.auralife.client

import dev.auralife.client.data.LocalLogout
import dev.auralife.client.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test
    fun loginLostOnlyForNotLoggedInReason() {
        assertTrue(Session.isLoginLost("没登录"))
        assertTrue(Session.isLoginLost(" 没登录 "))
        assertFalse(Session.isLoginLost("只有主人能这么做"))
        assertFalse(Session.isLoginLost(""))
    }

    @Test
    fun ownerOnlyIsNotLoginLost() {
        assertTrue(Session.isOwnerOnly("只有主人能这么做"))
        assertFalse(Session.isOwnerOnly("没登录"))
    }

    @Test
    fun localLogoutClearsLocalStateBeforeRemoteCall() {
        val order = mutableListOf<String>()
        LocalLogout.run(
            clearSession = { order += "clearSession" },
            stopLive = { order += "stopLive" },
            stopService = { order += "stopService" },
            remoteBestEffort = { order += "remote" },
        )
        // 本地优先：清登录态/停连接/停服务都在 best-effort 网络之前
        assertEquals(listOf("clearSession", "stopLive", "stopService", "remote"), order)
    }

    @Test
    fun localLogoutSwallowsRemoteFailure() {
        var remoteRan = false
        LocalLogout.run(
            clearSession = {},
            stopLive = {},
            stopService = {},
            remoteBestEffort = {
                remoteRan = true
                throw IllegalStateException("offline")
            },
        )
        assertTrue(remoteRan) // 抛异常也不能影响前面的本地登出
    }

    @Test
    fun localLogoutSwallowsStopFailuresButKeepsGoing() {
        var remoteRan = false
        LocalLogout.run(
            clearSession = {},
            stopLive = { throw IllegalStateException("live already dead") },
            stopService = { throw IllegalStateException("service already dead") },
            remoteBestEffort = { remoteRan = true },
        )
        assertTrue(remoteRan)
    }
}