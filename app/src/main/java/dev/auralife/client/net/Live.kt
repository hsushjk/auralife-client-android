package dev.auralife.client.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 分页游标是毫秒，边界会重复，合并按 id 去重
object Live {
    val messages = mutableStateListOf<Message>()
    var online by mutableStateOf(false)
    var typing by mutableStateOf(false)
    var botName by mutableStateOf("")

    var hasMore by mutableStateOf(false)
    var loadingOlder by mutableStateOf(false)
    var fileIndex by mutableStateOf(-1)
    var fileTotal by mutableStateOf(0)

    @Volatile var visible = false

    @Volatile var onNew: ((Message) -> Unit)? = null

    private val arrivals = java.util.concurrent.CopyOnWriteArrayList<(Message) -> Unit>()

    fun addArrival(f: (Message) -> Unit) {
        arrivals.add(f)
    }

    fun removeArrival(f: (Message) -> Unit) {
        arrivals.remove(f)
    }

    @Volatile var onState: ((Boolean) -> Unit)? = null
    // 以下回调由 LiveService 订阅做通知
    @Volatile var onRotate: ((org.json.JSONObject) -> Unit)? = null
    @Volatile var onNudge: ((org.json.JSONObject) -> Unit)? = null
    @Volatile var onAzone: ((org.json.JSONObject) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var api: AuraApi? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start(context: Context, api: AuraApi) {
        if (running && this.api === api) return
        stop()
        this.api = api
        running = true
        val app = context.applicationContext
        ObjectHolder.ctx = app
        thread = Thread {
            runCatching {
                val p = api.page("last")
                ui.post {
                    merge(p.messages)
                    hasMore = p.hasMore
                    fileIndex = p.fileIndex
                    fileTotal = p.fileTotal
                }
                api.state().optString("botName").takeIf { it.isNotBlank() }?.let { n -> ui.post { botName = n } }
            }
            connect(api)
        }.apply { isDaemon = true; start() }
        Log.i("aurachat", "Live start")
    }

    private fun connect(api: AuraApi) {
        var backoff = 1000L // 指数退避：1s 起、翻倍、30s 封顶
        while (running) {
            api.stream(
                onMessage = { m ->
                    ui.post {
                        // 同 id 覆盖，避免转写重发产生双气泡
                        val idx = messages.indexOfFirst { it.id == m.id }
                        if (idx >= 0) messages[idx] = m
                        else {
                            messages.add(m)
                            if (m.who != "user") {
                                onNew?.invoke(m)
                                arrivals.forEach { runCatching { it(m) } }
                            }
                        }
                    }
                },
                onTyping = { on -> ui.post { typing = on } },
                onOpen = {
                    backoff = 1000L
                    ui.post { markOnline(true); typing = false }
                    if (running) reload()
                },
                onClosed = { ui.post { markOnline(false); typing = false } },
                // 比 droppedBefore 早的消息已清理
                onHistory = { oldest, more ->
                    ui.post {
                        if (oldest > 0) messages.removeAll { it.t < oldest }
                        hasMore = more
                    }
                },
                onRotate = { obj ->
                    ui.post { messages.clear() }
                    onRotate?.invoke(obj)
                },
                onNudge = { obj -> onNudge?.invoke(obj) },
                onAzone = { obj -> onAzone?.invoke(obj) },
            )
            if (!running) break
            try {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    fun reload() {
        val a = api ?: return
        Thread {
            val p = runCatching { a.page("last") }.getOrNull() ?: return@Thread
            ui.post {
                merge(p.messages)
                hasMore = p.hasMore
                if (p.fileIndex >= 0) {
                    fileIndex = p.fileIndex
                    fileTotal = p.fileTotal
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun loadOlder() {
        val a = api ?: return
        if (loadingOlder || !hasMore) return
        val before = messages.firstOrNull()?.t ?: 0L
        loadingOlder = true
        Thread {
            val p = runCatching { a.page("prev", before) }.getOrNull()
            ui.post {
                loadingOlder = false
                if (p == null) return@post
                merge(p.messages)
                hasMore = p.hasMore
                if (p.fileIndex >= 0) {
                    fileIndex = p.fileIndex
                    fileTotal = p.fileTotal
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // 合并而非替换，避免覆盖拉取期间推来的消息
    private fun merge(fresh: List<Message>) {
        val seen = HashSet<String>()
        val merged = ArrayList<Message>(fresh.size + messages.size)
        for (m in fresh) if (seen.add(m.id)) merged.add(m)
        for (m in messages) if (seen.add(m.id)) merged.add(m)
        val sorted = merged.sortedBy { it.t } // sortedBy 稳定，同毫秒保持原顺序
        messages.clear()
        messages.addAll(sorted)
    }

    private fun markOnline(v: Boolean) {
        if (online == v) return
        online = v
        onState?.invoke(v)
    }

    fun stop() {
        running = false
        runCatching { api?.stopStream() }
        thread = null
        online = false
        typing = false
    }

    fun clear() {
        stop()
        ui.post {
            messages.clear()
            botName = ""
            hasMore = false
            loadingOlder = false
            fileIndex = -1
            fileTotal = 0
        }
        api = null
    }

    private object ObjectHolder {
        var ctx: Context? = null
    }
}
