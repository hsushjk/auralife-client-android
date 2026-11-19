package dev.auralife.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Live
import dev.auralife.client.net.Reconnect

class LiveService : Service() {
    companion object {
        const val CH_ALIVE = "aurachat-alive"
        const val CH_MSG = "aurachat-msg"
        const val ID_ALIVE = 1
        const val ID_MSG = 2

        fun start(ctx: Context) {
            val i = Intent(ctx, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LiveService::class.java)) }
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CH_ALIVE, "保持连接", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "让聊天在后台也能收到消息"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "新消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Ta 发来消息时弹出"
                },
            )
        }

        fun notifyMessage(ctx: Context, who: String, body: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            ensureChannels(ctx)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(who.ifBlank { "AuraLife" })
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_MSG, n)
        }

        fun clearMessages(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_MSG)
        }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 监听默认网络：onAvailable 时（后台也生效）让 Live 立即重连。
     * 旧实现没有这个回调，长期后台断网后只能等下一次 30s 退避甚至回前台才恢复。
     */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { Live.onNetworkAvailable() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { Log.w("aurachat", "注册网络回调失败：${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        startForeground(ID_ALIVE, aliveNotification("正在连接…"))
        val prefs = Prefs(this)
        if (prefs.token.isBlank()) {
            // 已经退出登录或登录态失效：前台服务没有存在的意义，直接收掉
            stopSelf()
            return
        }
        // 复用进程内唯一的 AuraApi 实例，避免 Activity 与 Service 各建一个导致双读流线程
        val a = AuraApi.current() ?: AuraApi.obtain(prefs.base, prefs.token).also { it.rebuild() }

        // 长期后台断网的关键修复：监听默认网络，网络一恢复就立即触发重连
        registerNetworkCallback()

        Live.onNew = { m ->
            if (!Live.appForeground) {
                val body = when (m.kind) {
                    "image" -> "[图片]"
                    "sticker" -> "[表情]"
                    "file" -> "[文件] " + m.fileName
                    "voice" -> if (m.dur > 0) "[语音 ${m.dur}s]" else "[语音]"
                    else -> m.text
                }
                notifyMessage(this, Live.botName.ifBlank { "AuraLife" }, body)
            }
            setOnline(Live.online)
        }
        Live.onState = { on -> setOnline(on) }
        Live.onConnectFailed = { setOnline(Live.online) }
        Live.onNudge = { obj ->
            if (!Live.appForeground) {
                val why = obj.optString("reason")
                val wish = obj.optJSONObject("wish")?.optString("text").orEmpty()
                val urge = obj.optInt("urge", 0)
                // 对齐 1.78.91 nudge {chatId,at,reason,wish:{id,text,tension},urge,topic}
                val text = when (why) {
                    "alarm" -> "闹钟响了"
                    "urge" -> if (wish.isNotBlank()) "想你了：$wish" else if (urge > 0) "想你了（冲动 $urge）" else "想你了"
                    else -> wish.ifBlank {
                        if (urge > 0) "Ta 有点按捺不住（冲动 $urge）" else "主动来找你"
                    }
                }
                notifyMessage(this, Live.botName.ifBlank { "AuraLife" }, text)
            }
        }
        // 轮换只清通知；聊天记录原地保留，由 Live 自己处理
        Live.onRotate = { clearMessages(this) }
        Live.start(a)
        setOnline(Live.online)
    }

    private fun setOnline(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val text = when {
            on -> "已连接 · 实时收消息"
            Live.connectFailed -> Reconnect.MSG_CONNECT_FAILED_HINT
            Live.ticketFailed -> Reconnect.MSG_TICKET_FAILED
            else -> "正在重连…"
        }
        nm.notify(ID_ALIVE, aliveNotification(text))
    }

    // START_STICKY：进程被系统杀掉后重建服务，避免后台长期断网/被杀后再也不恢复。
    // onCreate 里 token 为空会 stopSelf()，所以已退出登录时不会被拉起来。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterNetworkCallback()
        Live.onNew = null
        Live.onState = null
        Live.onConnectFailed = null
        Live.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun aliveNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_ALIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("AuraLife 在后台待着")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}