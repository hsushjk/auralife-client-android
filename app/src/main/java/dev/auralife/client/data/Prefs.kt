package dev.auralife.client.data

import android.content.Context
import androidx.core.content.edit

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aurachat", Context.MODE_PRIVATE)

    var base: String
        get() = sp.getString("base", "") ?: ""
        set(v) = sp.edit { putString("base", v) }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit { putString("token", v) }

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit { putString("user", v) }

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit { putString("name", v) }

    var role: String
        get() = sp.getString("role", "") ?: ""
        set(v) = sp.edit { putString("role", v) }

    var theme: String
        get() = sp.getString("theme", "follow") ?: "follow"
        set(v) = sp.edit { putString("theme", v) }

    var draft: String
        get() = sp.getString("draft", "") ?: ""
        set(v) = sp.edit { putString("draft", v) }

    // 毫秒时间戳
    var lastRead: Long
        get() = sp.getLong("lastRead", 0L)
        set(v) = sp.edit { putLong("lastRead", v) }

    fun clearLogin() = sp.edit { remove("token"); remove("user"); remove("name"); remove("role") }

    /**
     * 本地优先退出：只清登录态（token/role/name），
     * 保留 base / user / theme / draft / lastRead，方便回来直接登录。
     */
    fun clearSession() = sp.edit {
        remove("token")
        remove("role")
        remove("name")
    }

    // 只填 IP 时补默认端口 2332
    fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            val hostPort = s.substringBefore('/')
            val withPort = if (hostPort.contains(':') && hostPort.substringAfterLast(':').all { it.isDigit() }) hostPort else "$hostPort:2332"
            s = "http://$withPort" + s.removePrefix(hostPort)
        }
        return s.trimEnd('/')
    }
}