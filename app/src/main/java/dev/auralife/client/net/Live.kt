package dev.auralife.client.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时连接（SSE）+ 消息缓存。
 *
 * 对应 reports/adaptation-spec.md §1.2：
 * - 单实例：同一时刻只有一个读流线程；stop() 幂等，停后不再起线程
 * - onerror / 读流异常 → 关旧连接 → 退避 min(30s, 1s*2^min(5,n)) → 重新取票 → 重连
 * - onopen 复位退避并对齐提示「连接恢复了」；首次断线提示一次
 * - 断线期间/恢复后补拉 /api/messages?file=last，按 id upsert 去重
 * - 心跳看门狗：>60s 没有任何数据（含 : ping）视为假死，主动断流重连
 * - rotate 只代表换 session，聊天记录原地保留（禁止「已归档」）
 */
object Live {
    data class Notice(val seq: Int, val text: String)

    val messages = mutableStateListOf<Message>()
    var online by mutableStateOf(false)
    var typing by mutableStateOf(false)
    var botName by mutableStateOf("")
    // 最近一次失败是取票失败（提示文案不同）
    var ticketFailed by mutableStateOf(false)
    // 自身 chatId（/api/state 的 me.subject），用来过滤 SSE 事件
    var chatId by mutableStateOf("")

    var hasMore by mutableStateOf(false)
    var loadingOlder by mutableStateOf(false)
    var fileIndex by mutableStateOf(-1)
    var fileTotal by mutableStateOf(0)

    // 一次性提示（Toast 类）；seq 变化触发 UI 展示
    var notice by mutableStateOf(Notice(0, ""))
    // rotate：只提示 + 重拉，不清空本地记录
    var rotateSeq by mutableStateOf(0)
    var rotateAt by mutableStateOf(0L)
    // azone：空间页据此重拉
    var azoneSeq by mutableStateOf(0)

    // 后台重连 3 次都失败：进入「连接失败」，停止自动重试；
    // 由网络恢复 / 回到 App / 手动点「重试」重新触发。
    var connectFailed by mutableStateOf(false)

    @Volatile var visible = false

    /**
     * App 是否在前台。
     *
     * 不能用 visible 代替：离开聊天 tab 时 ChatScreen 的 DisposableEffect 会把 visible 置 false，
     * 而这时 App 仍在前台（设置页等），重连不该按后台的 3 次上限处理。
     */
    @Volatile var appForeground = false

    @Volatile var onNew: ((Message) -> Unit)? = null

    private val arrivals = CopyOnWriteArrayList<(Message) -> Unit>()

    fun addArrival(f: (Message) -> Unit) {
        arrivals.add(f)
    }

    fun removeArrival(f: (Message) -> Unit) {
        arrivals.remove(f)
    }

    @Volatile var onState: ((Boolean) -> Unit)? = null
    // 以下回调由 LiveService 订阅做通知
    @Volatile var onRotate: ((JSONObject) -> Unit)? = null
    @Volatile var onNudge: ((JSONObject) -> Unit)? = null
    @Volatile var onAzone: ((JSONObject) -> Unit)? = null
    @Volatile var onConnectFailed: ((Boolean) -> Unit)? = null

    private const val TYPING_HIDE_MS = 30_000L
    private const val HEARTBEAT_TIMEOUT_MS = 60_000L

    /** 后台重连节奏：10 秒一次、最多 3 次；失败后进入「连接失败」停止自动重试。 */
    private const val BACKGROUND_DELAY_MS = 10_000L
    private const val BACKGROUND_MAX_RETRIES = 3

    private val ui = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var api: AuraApi? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var watchdog: Thread? = null
    @Volatile private var running = false
    private var gen = 0

    @Volatile private var lastDataAt = 0L
    @Volatile private var selfChatId = ""
    private var typingSeq = 0L

    // 前后台不同重连节奏（纯逻辑，见 ReconnectPolicy）
    private val policy = ReconnectPolicy(BACKGROUND_DELAY_MS, BACKGROUND_MAX_RETRIES)
    // 本轮断线是否已提示过；恢复时据此提示「连接恢复了」
    private val disconnectNoticeSent = AtomicBoolean(false)

    private var boundApi: AuraApi? = null
    private val authHandler: () -> Unit = { ui.post { if (running) stop() } }

    private fun bindAuth(api: AuraApi) {
        if (boundApi === api) return
        boundApi?.removeAuthLostListener(authHandler)
        api.addAuthLostListener(authHandler)
        boundApi = api
    }

    fun currentApi(): AuraApi? = api

    /** 幂等：已经用同一份实例连着就直接返回。 */
    fun start(api: AuraApi) {
        val shared = AuraApi.obtain(api.base, api.token)
        synchronized(lock) {
            if (running && this.api === shared) return
            stopLocked()
            this.api = shared
            bindAuth(shared)
            gen++
            val g = gen
            running = true
            lastDataAt = 0L
            disconnectNoticeSent.set(false)
            policy.reset(appForeground)
            thread = Thread({ bootstrap(g, shared) }, "aura-live").apply {
                isDaemon = true
                start()
            }
            watchdog = Thread({ watch(g, shared) }, "aura-watchdog").apply {
                isDaemon = true
                start()
            }
        }
        Log.i("aurachat", "Live start")
    }

    /** 幂等停止：取消读流、停线程、状态归零；之后不会再起连接线程。 */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        gen++
        runCatching { api?.stopStream() }
        thread?.interrupt()
        thread = null
        watchdog?.interrupt()
        watchdog = null
        lastDataAt = 0L
        disconnectNoticeSent.set(false)
        ui.post {
            markOnline(false)
            typing = false
            markConnectFailed(false)
        }
    }

    fun clear() {
        val old: AuraApi?
        synchronized(lock) {
            stopLocked()
            old = api
            api = null
        }
        if (old != null) {
            old.removeAuthLostListener(authHandler)
            if (boundApi === old) boundApi = null
        }
        AuraApi.forget(old)
        selfChatId = ""
        ui.post {
            messages.clear()
            botName = ""
            chatId = ""
            hasMore = false
            loadingOlder = false
            fileIndex = -1
            fileTotal = 0
            online = false
            typing = false
            ticketFailed = false
            connectFailed = false
            rotateSeq = 0
            rotateAt = 0L
            azoneSeq = 0
            notice = Notice(notice.seq + 1, "")
        }
    }

    /**
     * App 回到前台：如果断线/退避/「连接失败」，立即重连一次；
     * 之后仍按前台 1s→30s 常规退避自愈（无线重试，不放弃）。
     */
    fun onForeground() {
        appForeground = true
        synchronized(lock) {
            if (!running) return
            policy.setForeground(true)
            if (!online || connectFailed) wakeForReconnectLocked("foreground")
        }
    }

    /** App 退到后台：重连降级为 10 秒一次、最多 3 次，失败后进入「连接失败」。 */
    fun onBackground() {
        appForeground = false
        synchronized(lock) {
            policy.setForeground(false)
            // 前台退避等待中切到后台：唤醒一次，让后续等待改用后台 10s 节奏
            val t = thread
            if (running && !online && !connectFailed && t != null && t.isAlive &&
                t.state == Thread.State.TIMED_WAITING
            ) {
                t.interrupt()
            }
        }
    }

    /**
     * 网络从无到有（ConnectivityManager.onAvailable）：
     * - 断线/退避/连接失败 → 立即重连（后台也生效，这是「联网后一直干等」的关键修复）；
     * - 仍显示在线时网络切换 → 主动断掉旧 SSE，让读流线程按新网络重新取票重连（防半开 socket）。
     */
    fun onNetworkAvailable() {
        synchronized(lock) {
            if (!running || api == null) return
            if (online && !connectFailed) {
                Log.i("aurachat", "网络恢复：主动断开旧 SSE，按新网络重连")
                runCatching { api?.stopStream() }
                return
            }
            wakeForReconnectLocked("network")
        }
    }

    /** 手动重试（聊天页顶栏「重试」；通知点击回 App 走 onForeground 自动重试一次）。 */
    fun retryNow() {
        synchronized(lock) {
            if (!running || (online && !connectFailed)) return
            wakeForReconnectLocked("manual")
        }
    }

    /**
     * 立即唤醒重连：
     * - worker 还活着（在退避等待）→ interrupt 唤醒，跳过剩余等待；
     * - worker 已退出（后台 3 次用尽、进入连接失败）→ 重新起 worker + watchdog。
     */
    private fun wakeForReconnectLocked(reason: String) {
        val a = api ?: return
        policy.onRetryRequested(appForeground)
        ui.post { if (running) markConnectFailed(false) }
        val t = thread
        if (t != null && t === Thread.currentThread()) {
            // 已经在重连线程里（回调直接触发），无需再起线程
            return
        }
        if (t != null && t.isAlive) {
            Log.i("aurachat", "立即重连（$reason）：唤醒退避等待")
            t.interrupt()
            return
        }
        gen++
        val g = gen
        lastDataAt = 0L
        disconnectNoticeSent.set(false)
        watchdog?.interrupt()
        thread = Thread({ bootstrap(g, a) }, "aura-live").apply {
            isDaemon = true
            start()
        }
        watchdog = Thread({ watch(g, a) }, "aura-watchdog").apply {
            isDaemon = true
            start()
        }
        Log.i("aurachat", "立即重连（$reason）：重启连接线程")
    }

    fun reload() {
        val a = api ?: return
        val g = gen
        Thread {
            loadLast(g, a)
            refreshIdentity(g, a)
        }.apply { isDaemon = true }.start()
    }

    fun loadOlder() {
        val a = api ?: return
        if (loadingOlder || !hasMore) return
        val g = gen
        val before = messages.firstOrNull()?.t ?: 0L
        loadingOlder = true
        Thread {
            val p = runCatching { a.page("prev", before) }.getOrNull()
            ui.post {
                if (gen != g) return@post
                loadingOlder = false
                if (p == null) return@post
                MessageStore.merge(messages, p.messages)
                hasMore = p.hasMore
                if (p.fileIndex >= 0) {
                    fileIndex = p.fileIndex
                    fileTotal = p.fileTotal
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun active(g: Int): Boolean = running && gen == g

    private fun bootstrap(g: Int, api: AuraApi) {
        refreshIdentity(g, api)
        loadLast(g, api)
        connect(g, api)
    }

    private fun refreshIdentity(g: Int, api: AuraApi) {
        val st = runCatching { api.state() }.getOrNull() ?: return
        if (!active(g)) return
        val name = st.optString("botName")
        val chat = st.optJSONObject("me")?.optString("subject").orEmpty()
        ui.post {
            if (!active(g)) return@post
            if (name.isNotBlank()) botName = name
            if (chat.isNotBlank()) {
                selfChatId = chat
                chatId = chat
            }
        }
    }

    private fun loadLast(g: Int, api: AuraApi) {
        val p = runCatching { api.page("last") }.getOrNull() ?: return
        ui.post {
            if (!active(g)) return@post
            MessageStore.merge(messages, p.messages)
            hasMore = p.hasMore
            if (p.fileIndex >= 0) {
                fileIndex = p.fileIndex
                fileTotal = p.fileTotal
            }
        }
    }

    private fun connect(g: Int, api: AuraApi) {
        var wasOnline = false
        while (active(g)) {
            var failure: Throwable? = null
            val ticketProblem = try {
                api.stream(
                    StreamListener(
                        onOpen = {
                            wasOnline = true
                            policy.onConnected()
                            lastDataAt = System.currentTimeMillis()
                            ui.post {
                                if (!active(g)) return@post
                                ticketFailed = false
                                markConnectFailed(false)
                                markOnline(true)
                                typing = false
                                if (disconnectNoticeSent.getAndSet(false)) pushNotice(Reconnect.MSG_RECOVERED)
                            }
                            // 断线期间可能漏消息，连上后补拉最新一片（按 id 去重）
                            Thread {
                                refreshIdentity(g, api)
                                loadLast(g, api)
                            }.apply { isDaemon = true }.start()
                        },
                        onData = { lastDataAt = System.currentTimeMillis() },
                        onMessage = { cid, m ->
                            if (acceptChat(cid)) {
                                ui.post {
                                    if (!active(g) || !acceptChat(cid)) return@post
                                    val added = MessageStore.upsert(messages, m)
                                    if (added && m.who != "user") {
                                        runCatching { onNew?.invoke(m) }
                                        arrivals.forEach { runCatching { it(m) } }
                                    }
                                }
                            }
                        },
                        onTyping = { cid, on ->
                            if (acceptChat(cid)) {
                                ui.post {
                                    if (!active(g) || !acceptChat(cid)) return@post
                                    typing = on
                                    val seq = ++typingSeq
                                    // 服务端 10 分钟兜底，客户端 30s 没更新自己隐藏
                                    if (on) ui.postDelayed({ if (typingSeq == seq) typing = false }, TYPING_HIDE_MS)
                                }
                            }
                        },
                        onHistory = { root ->
                            if (acceptChat(root.optString("chatId"))) {
                                ui.post {
                                    if (!active(g)) return@post
                                    // droppedBefore 之前的已清理，oldestAt 是兼容字段
                                    val dropped = root.optLong("droppedBefore", root.optLong("oldestAt", 0L))
                                    MessageStore.dropOlderThan(messages, dropped)
                                    hasMore = root.optBoolean("hasMore", false)
                                }
                            }
                        },
                        onRotate = { root ->
                            if (acceptChat(root.optString("chatId"))) {
                                // 只代表换 session：本地记录保留，提示 + 重拉最新一片
                                ui.post {
                                    if (!active(g)) return@post
                                    rotateAt = System.currentTimeMillis()
                                    rotateSeq++
                                }
                                runCatching { onRotate?.invoke(root) }
                                Thread { loadLast(g, api) }.apply { isDaemon = true }.start()
                            }
                        },
                        onNudge = { root ->
                            if (acceptChat(root.optString("chatId"))) runCatching { onNudge?.invoke(root) }
                        },
                        onAzone = { root ->
                            if (acceptChat(root.optString("chatId"))) {
                                ui.post { if (active(g)) azoneSeq++ }
                                runCatching { onAzone?.invoke(root) }
                            }
                        },
                    ),
                )
                false
            } catch (e: Throwable) {
                failure = e
                e is TicketException
            }
            if (!active(g)) break
            // 连上过又断了：开始新一段断线，退避计数清零（避免沿用上一段的 attempt）
            if (wasOnline) {
                wasOnline = false
                policy.onConnectionLost(appForeground)
            }
            ui.post {
                if (!active(g)) return@post
                markOnline(false)
                typing = false
                ticketFailed = ticketProblem
            }
            if (failure != null) Log.w("aurachat", "SSE 断开，准备重连：${failure.message}")
            // 同一次断线只提示一次；恢复时再提示「连接恢复了」
            if (disconnectNoticeSent.compareAndSet(false, true)) {
                ui.post {
                    if (!active(g)) return@post
                    pushNotice(if (ticketProblem) Reconnect.MSG_TICKET_FAILED else Reconnect.MSG_DISCONNECTED)
                }
            }
            // 前台：1s→30s 无限退避；后台：10s×3 后进入「连接失败」停止自动重试
            val decision = policy.onRetryFailed(appForeground)
            if (decision.giveUp) {
                Log.w("aurachat", "后台重连失败 ${BACKGROUND_MAX_RETRIES} 次，进入「连接失败」")
                lastDataAt = 0L
                ui.post {
                    if (!active(g)) return@post
                    markOnline(false)
                    typing = false
                    markConnectFailed(true)
                    pushNotice(Reconnect.MSG_CONNECT_FAILED_HINT)
                }
                break
            }
            if (!sleepWhileActive(g, decision.delayMs)) break
        }
    }

    private fun pushNotice(text: String) {
        notice = Notice(notice.seq + 1, text)
    }

    private fun markOnline(v: Boolean) {
        if (online == v) return
        online = v
        runCatching { onState?.invoke(v) }
    }

    private fun markConnectFailed(v: Boolean) {
        if (connectFailed == v) return
        connectFailed = v
        runCatching { onConnectFailed?.invoke(v) }
    }

    private fun acceptChat(cid: String): Boolean {
        if (cid.isBlank()) return true
        val self = selfChatId
        return self.isBlank() || cid == self
    }

    /** 心跳看门狗：读流线程阻塞在 socket 上时，靠它发现假死并 cancel。 */
    private fun watch(g: Int, api: AuraApi) {
        while (active(g)) {
            try {
                Thread.sleep(5_000L)
            } catch (e: InterruptedException) {
                break
            }
            if (!active(g)) break
            val last = lastDataAt
            if (last > 0 && System.currentTimeMillis() - last > HEARTBEAT_TIMEOUT_MS) {
                Log.w("aurachat", "SSE 超过 ${HEARTBEAT_TIMEOUT_MS}ms 没数据，判定假死并重连")
                lastDataAt = 0L
                runCatching { api.stopStream() }
            }
        }
    }

    /**
     * 退避等待；stop() 会 interrupt 让循环退出；
     * 网络恢复 / 回前台 / 手动重试会 interrupt 让它立即进入下一次重连（不是退出）。
     */
    private fun sleepWhileActive(g: Int, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (active(g)) {
            val left = until - System.currentTimeMillis()
            if (left <= 0L) return true
            try {
                Thread.sleep(minOf(left, 400L))
            } catch (e: InterruptedException) {
                // stop()：running/代际已变 → active=false → 退出；
                // wakeForReconnect：仍 active → 立刻继续重连（跳过剩余退避）
                return active(g)
            }
        }
        return false
    }
}