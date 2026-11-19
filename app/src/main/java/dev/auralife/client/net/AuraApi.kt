package dev.auralife.client.net

import android.util.Log
import dev.auralife.client.data.LocalLogout
import dev.auralife.client.data.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class Message(
    val id: String,
    val who: String,
    val text: String,
    val kind: String = "text",
    val t: Long = 0,
    val sticker: String = "",
    val name: String = "",
    val caption: String = "",
    val uid: String = "",
    // 媒体走服务端 mediaId，404 即已过期
    val mediaId: String = "",
    val mediaMime: String = "",
    val fileName: String = "",
    val bytes: Long = 0,
    // 转写完成后服务端重发同 id 消息，带 heard
    val dur: Int = 0,
    val heard: String = "",
    // 旧协议内联 base64，新协议恒空
    val image: String = "",
    val imageMime: String = "",
    val voice: String = "",
    val voiceMime: String = "",
    val mediaExpired: Boolean = false,
    // v1.91.78：被引用的消息快照，历史消息/SSE 都可能带
    val quote: Quote? = null,
)

fun JSONObject.toMessage(): Message = Message(
    id = optString("id"),
    who = optString("who", "bot"),
    text = optString("text"),
    kind = optString("kind", "text"),
    t = optLong("t", 0L),
    sticker = optString("sticker"),
    name = optString("name"),
    caption = optString("caption"),
    uid = optString("uid"),
    mediaId = optString("mediaId"),
    mediaMime = optString("mediaMime"),
    fileName = optString("fileName"),
    bytes = optLong("bytes", 0L),
    dur = optInt("dur", 0),
    heard = optString("heard"),
    image = optString("image"),
    imageMime = optString("imageMime"),
    voice = optString("voice"),
    voiceMime = optString("voiceMime"),
    mediaExpired = optBoolean("mediaExpired", false),
    quote = QuoteRules.fromJson(optJSONObject("quote")),
)

data class Page(
    val messages: List<Message>,
    val oldestAt: Long = 0,
    val hasMore: Boolean = false,
    val fileIndex: Int = -1,
    val fileTotal: Int = 0,
)

data class Sticker(val md5: String, val name: String, val caption: String = "")

data class FileSend(val dataUri: String, val name: String, val mime: String)

/** SSE 回调，全部在读流线程上触发，上层自己切主线程。 */
class StreamListener(
    val onOpen: () -> Unit,
    val onData: () -> Unit = {},
    val onClosed: (Throwable?) -> Unit = {},
    val onMessage: (chatId: String, message: Message) -> Unit = { _, _ -> },
    val onTyping: (chatId: String, on: Boolean) -> Unit = { _, _ -> },
    val onHistory: (JSONObject) -> Unit = {},
    val onRotate: (JSONObject) -> Unit = {},
    val onNudge: (JSONObject) -> Unit = {},
    val onAzone: (JSONObject) -> Unit = {},
)

class AuraApi(@Volatile var base: String, @Volatile var token: String = "") {
    companion object {
        @Volatile private var shared: AuraApi? = null

        /**
         * 整个进程共用一份实例：Activity 和 LiveService 各建一个的话，
         * 会出现两套 OkHttpClient、两个 SSE 读流线程，重连状态互相打架（AND-1 根因之一）。
         */
        @Synchronized
        fun obtain(base: String, token: String): AuraApi {
            val cur = shared
            if (cur != null && cur.base == base && cur.token == token) return cur
            val a = AuraApi(base, token).also { it.rebuild() }
            shared = a
            return a
        }

        @Synchronized
        fun current(): AuraApi? = shared

        @Synchronized
        fun forget(api: AuraApi?) {
            if (api != null && shared === api) shared = null
        }
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val secure = base.startsWith("https://")
    private var client: OkHttpClient = buildClient(insecure = secure && Tls.isAccepted(hostKey()))

    // 登录态失效（token 过期 / 真 401 / reason=「没登录」）时回调，任意线程都可能触发
    private val authListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addAuthLostListener(l: () -> Unit) {
        authListeners.add(l)
    }

    fun removeAuthLostListener(l: () -> Unit) {
        authListeners.remove(l)
    }

    private fun fireAuthLost() {
        for (l in authListeners) runCatching { l() }
    }

    /** 服务端 < 1.78.91 没有 /api/stream-ticket，回退旧的 ?token=（deprecated）。 */
    @Volatile var legacyStream: Boolean = false

    /** /api/state 返回的 version，用于协议分支。 */
    @Volatile var serverVersion: String = ""

    private fun hostKey(): String = base.substringAfter("://").trimEnd('/')

    fun rebuild() {
        client = buildClient(insecure = secure && Tls.isAccepted(hostKey()))
    }

    internal fun url(path: String) = base.trimEnd('/') + "/" + path.trimStart('/')

    internal fun call(path: String, body: JSONObject? = null, method: String? = null): JSONObject {
        val b = Request.Builder().url(url(path)).header("Authorization", "Bearer $token")
        if (body != null) b.method(method ?: "POST", body.toString().toRequestBody(jsonType))
        else b.method(method ?: "GET", null)
        client.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            // 业务失败也是 HTTP 200（{ok:false,reason}），先解析 body 再判状态码
            val obj = when {
                s.isBlank() -> JSONObject().put("ok", r.isSuccessful)
                else -> runCatching { JSONObject(s) }
                    .getOrElse { JSONObject().put("ok", false).put("reason", "服务端回的不是 JSON") }
            }
            // 真 401 只有 /api/stream?ticket= 与 /api/media/:id；这里出现说明登录态已经没了
            if (r.code == 401) {
                fireAuthLost()
                return JSONObject().put("ok", false).put("reason", "没登录")
            }
            if (obj.optBoolean("ok") == false) {
                val why = obj.optString("reason").ifBlank { obj.optString("message") }
                // 只有「没登录」才踢登录；「只有主人能这么做」等权限提示只提示不动登录态
                if (Session.isLoginLost(why)) fireAuthLost()
            }
            return obj
        }
    }

    fun login(user: String, pass: String): JSONObject =
        call("api/login", JSONObject().put("username", user).put("password", pass))

    fun state(): JSONObject {
        val r = call("api/state")
        val v = r.optString("version")
        if (v.isNotBlank()) serverVersion = v
        if (Reconnect.isLegacyVersion(v)) legacyStream = true
        return r
    }

    /**
     * 首次初始化：POST /api/setup。必须 pledge=true 且密码 >= 6 位；
     * setup 成功不返回 token，需要再 /api/login 换正式 token。
     */
    fun setup(username: String, password: String, pledge: Boolean, botName: String, ownerName: String): JSONObject {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("pledge", pledge)
        if (botName.isNotBlank()) body.put("botName", botName)
        if (ownerName.isNotBlank()) body.put("ownerName", ownerName)
        return call("api/setup", body)
    }

    fun config(): JSONObject = call("api/config")

    // file=last 最新一片，file=prev&before= 上一片
    fun page(mode: String = "last", before: Long = 0, limit: Int = 100): Page {
        val q = StringBuilder("api/messages?")
        if (mode == "last" || mode == "prev") {
            q.append("file=").append(mode)
            if (mode == "prev" && before > 0) q.append("&before=").append(before)
        } else {
            q.append("limit=").append(limit.coerceIn(1, 100))
            if (before > 0) q.append("&before=").append(before)
        }
        val r = call(q.toString())
        val arr = r.optJSONArray("messages") ?: JSONArray()
        val f = r.optJSONObject("file")
        return Page(
            messages = (0 until arr.length()).map { arr.getJSONObject(it).toMessage() },
            oldestAt = r.optLong("oldestAt", 0L),
            hasMore = r.optBoolean("hasMore", false),
            fileIndex = f?.optInt("index", -1) ?: -1,
            fileTotal = f?.optInt("total", 0) ?: 0,
        )
    }

    fun messages(): List<Message> = page("last").messages

    fun send(text: String, quote: Quote? = null): JSONObject =
        call("api/messages", JSONObject().put("text", text).putQuote(quote))

    /** 把引用快照放进请求体；字段不合法时什么也不放（服务端同样会静默丢弃）。 */
    private fun JSONObject.putQuote(quote: Quote?): JSONObject =
        apply { if (quote != null) QuoteRules.wireJson(quote)?.let { put("quote", it) } }

    private fun asDataUri(data: String, mime: String): String =
        if (data.startsWith("data:")) data else "data:$mime;base64,$data"

    fun sendImage(base64: String, mime: String, name: String = "image", quote: Quote? = null): JSONObject =
        call(
            "api/messages",
            JSONObject().put(
                "images",
                JSONArray().put(
                    JSONObject().put("data", asDataUri(base64, mime)).put("mediaType", mime).put("name", name),
                ),
            ).putQuote(quote),
        )

    fun sendSticker(md5: String, quote: Quote? = null): JSONObject =
        call("api/messages", JSONObject().put("sticker", md5).putQuote(quote))

    fun sendVoice(base64: String, mime: String, dur: Int, quote: Quote? = null): JSONObject =
        call(
            "api/messages",
            JSONObject().put(
                "voice",
                JSONObject().put("data", asDataUri(base64, mime)).put("mime", mime).put("dur", dur.coerceIn(1, 60)),
            ).putQuote(quote),
        )

    // 服务端 chat.js 读的是 mediaType（不是 mime），写错会导致落库 MIME=octet-stream、两端都打不开
    fun sendFiles(files: List<FileSend>, quote: Quote? = null): JSONObject {
        val arr = JSONArray()
        for (f in files) {
            arr.put(
                JSONObject()
                    .put("data", f.dataUri)
                    .put("mediaType", f.mime)
                    .put("name", f.name),
            )
        }
        return call("api/messages", JSONObject().put("files", arr).putQuote(quote))
    }

    // /api/voice 要求 dur 在 1..60，越界服务端直接 reason「语音条要 1 到 60 秒」
    fun transcribeVoice(dataUri: String, mime: String, dur: Int): JSONObject =
        call(
            "api/voice",
            JSONObject().put("data", dataUri).put("mime", mime).put("dur", dur.coerceIn(1, 60)),
        )

    /**
     * 按 id 反查一条消息（v1.91.78）：成功 {ok:true,message}；
     * 未命中 {ok:false,reason:"那条消息已经找不到了"}；
     * 旧服务端 {ok:false,reason:"不认识这个接口：api/messages/locate"}。
     */
    fun locate(id: String): JSONObject =
        call("api/messages/locate?id=" + java.net.URLEncoder.encode(id.trim(), "UTF-8"))

    fun stickers(): List<Sticker> {
        val arr = call("api/stickers").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Sticker(md5 = o.optString("md5"), name = o.optString("name"), caption = o.optString("caption"))
        }
    }

    fun fetchBytes(path: String): ByteArray? {
        val req = Request.Builder().url(if (path.startsWith("http")) path else url(path))
            .header("Authorization", "Bearer $token").build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (r.code == 401) fireAuthLost()
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    fun typing(on: Boolean) {
        runCatching { call("api/typing", JSONObject().put("typing", on)) }
    }

    /** best-effort 退出：3~5s 超时、失败忽略，不能阻塞本地登出。 */
    fun logout() {
        val fast = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(LocalLogout.REMOTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        runCatching {
            val req = Request.Builder().url(url("api/logout"))
                .header("Authorization", "Bearer $token")
                .method("POST", JSONObject().toString().toRequestBody(jsonType))
                .build()
            fast.newCall(req).execute().use { it.body?.close() }
        }
    }

    fun stickerUrl(md5: String) = url("api/sticker/$md5") + "?token=$token"

    // 需要 URL 时用，取字节走 mediaBytes（带鉴权）
    fun mediaUrl(mediaId: String) = url("api/media/" + mediaId) + "?token=" + token

    fun mediaBytes(mediaId: String): ByteArray? =
        fetchBytes("api/media/" + mediaId).also { Log.d("auralife", "media $mediaId -> ${it?.size ?: "404"}") }

    @Volatile private var streamCall: okhttp3.Call? = null

    /** cancel 当前 SSE 连接；读流线程会抛异常退出，由重连循环决定下一步。 */
    fun stopStream() {
        runCatching { streamCall?.cancel() }
    }

    /** 取一次性票据；旧服务端回退空串，走 deprecated 的 ?token=。 */
    private fun fetchTicket(): String {
        val r = call("api/stream-ticket", JSONObject())
        return when (val res = StreamTicket.parse(
            ok = r.optBoolean("ok"),
            ticket = r.optString("ticket"),
            reason = r.optString("reason").ifBlank { r.optString("message") },
        )) {
            is StreamTicket.Result.Ticket -> res.ticket
            is StreamTicket.Result.Legacy -> {
                legacyStream = true
                ""
            }
            is StreamTicket.Result.Failed -> throw TicketException(res.reason)
        }
    }

    /**
     * 建立 SSE 连接并阻塞读流，直到连接断开（正常 EOF / 异常 / 主动 stop）。
     * 每次调用都重新取票（票据一次性、60s 过期，禁止同 URL 自动重连）。
     */
    fun stream(listener: StreamListener) {
        var failure: Throwable? = null
        var callRef: okhttp3.Call? = null
        try {
            val ticket = if (legacyStream) "" else fetchTicket()
            val baseUrl = url("api/stream").toHttpUrlOrNull() ?: throw IOException("服务端地址不对")
            val target = baseUrl.newBuilder().apply {
                if (ticket.isNotEmpty()) addQueryParameter("ticket", ticket)
                // deprecated：只有旧服务端（< 1.78.91）才用 token 直连
                else addQueryParameter("token", token)
            }.build()
            val req = Request.Builder().url(target).build()
            val sse = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val call = sse.newCall(req)
            callRef = call
            streamCall = call
            call.execute().use { r ->
                if (r.code == 401) {
                    // 新协议：票可能刚好过期，重新取票即可；旧协议：token 失效才算登录态没了
                    if (ticket.isEmpty()) fireAuthLost()
                    throw IOException(if (ticket.isEmpty()) "SSE 401" else "SSE 票据失效")
                }
                if (!r.isSuccessful) throw IOException("SSE HTTP " + r.code)
                listener.onOpen()
                val src = r.body?.source() ?: throw IOException("SSE 没有响应体")
                var event = ""
                val data = StringBuilder()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    // 心跳（: ping）也算活着的证据，看门狗靠它复位
                    listener.onData()
                    when {
                        line.isEmpty() -> {
                            dispatch(event, data.toString(), listener)
                            event = ""
                            data.setLength(0)
                        }
                        // 冒号开头是 SSE 心跳，丢弃
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                    }
                }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            if (streamCall === callRef) streamCall = null
        }
        runCatching { listener.onClosed(failure) }
    }

    private fun dispatch(event: String, payload: String, listener: StreamListener) {
        if (payload.isEmpty()) return
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val type = if (event.isNotEmpty()) event else root.optString("type")
        // chatId 只取顶层，用于上层过滤；不带 chatId 的事件推给所有连接
        val chatId = root.optString("chatId")
        runCatching {
            when (type) {
                "message" -> listener.onMessage(chatId, (root.optJSONObject("message") ?: root).toMessage())
                "typing" -> listener.onTyping(chatId, root.optBoolean("on", false))
                "history" -> listener.onHistory(root)
                "rotate" -> listener.onRotate(root)
                "nudge" -> listener.onNudge(root)
                "azone" -> listener.onAzone(root)
                // 未知事件（1.78.91 以后可能新增）记日志，便于跟进，不当作错误
                else -> Log.w("aurachat", "未知 SSE 事件类型: $type")
            }
        }
    }
}