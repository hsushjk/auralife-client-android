package dev.auralife.client.net

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

class AuraApi(@Volatile var base: String, @Volatile var token: String = "") {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val secure = base.startsWith("https://")
    private var client: OkHttpClient = buildClient(insecure = secure && Tls.isAccepted(hostKey()))

    // 鉴权失败时回调，上层清 token 回登录页
    @Volatile var onAuthFail: (() -> Unit)? = null

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
            if (r.code == 401) { onAuthFail?.invoke(); return JSONObject().put("ok", false).put("reason", "没登录") }
            if (s.isBlank()) return JSONObject().put("ok", r.isSuccessful)
            val obj = runCatching { JSONObject(s) }.getOrElse { JSONObject().put("ok", false).put("reason", "服务端回的不是 JSON") }
            if (obj.optBoolean("ok") == false) {
                val why = obj.optString("reason").ifBlank { obj.optString("message") }
                if (why.contains("没登录")) onAuthFail?.invoke()
            }
            return obj
        }
    }

    fun login(user: String, pass: String): JSONObject =
        call("api/login", JSONObject().put("username", user).put("password", pass))

    fun state(): JSONObject = call("api/state")

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

    fun send(text: String): JSONObject = call("api/messages", JSONObject().put("text", text))

    private fun asDataUri(data: String, mime: String): String =
        if (data.startsWith("data:")) data else "data:$mime;base64,$data"

    fun sendImage(base64: String, mime: String, name: String = "image"): JSONObject =
        call(
            "api/messages",
            JSONObject().put(
                "images",
                JSONArray().put(
                    JSONObject().put("data", asDataUri(base64, mime)).put("mediaType", mime).put("name", name),
                ),
            ),
        )

    fun sendSticker(md5: String): JSONObject = call("api/messages", JSONObject().put("sticker", md5))

    fun sendVoice(base64: String, mime: String, dur: Int): JSONObject =
        call(
            "api/messages",
            JSONObject().put(
                "voice",
                JSONObject().put("data", asDataUri(base64, mime)).put("mime", mime).put("dur", dur.coerceIn(1, 60)),
            ),
        )

    fun sendFiles(files: List<FileSend>): JSONObject {
        val arr = JSONArray()
        for (f in files) arr.put(JSONObject().put("data", f.dataUri).put("name", f.name).put("mime", f.mime))
        return call("api/messages", JSONObject().put("files", arr))
    }

    fun transcribeVoice(dataUri: String, mime: String): JSONObject =
        call("api/voice", JSONObject().put("data", dataUri).put("mime", mime))

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
                if (r.code == 401) onAuthFail?.invoke()
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    fun typing(on: Boolean) {
        runCatching { call("api/typing", JSONObject().put("typing", on)) }
    }

    fun logout() {
        runCatching { call("api/logout", JSONObject(), "POST") }
    }

    fun stickerUrl(md5: String) = url("api/sticker/$md5") + "?token=$token"

    // 需要 URL 时用，取字节走 mediaBytes（带鉴权）
    fun mediaUrl(mediaId: String) = url("api/media/" + mediaId) + "?token=" + token

    fun mediaBytes(mediaId: String): ByteArray? =
        fetchBytes("api/media/" + mediaId).also { Log.d("auralife", "media $mediaId -> ${it?.size ?: "404"}") }

    @Volatile private var streamCall: okhttp3.Call? = null

    fun stopStream() {
        runCatching { streamCall?.cancel() }
    }

    // 服务端 SSE 不写 event:/retry: 行，type 在 JSON 里
    fun stream(
        onMessage: (Message) -> Unit,
        onTyping: (Boolean) -> Unit,
        onOpen: () -> Unit,
        onClosed: (Throwable?) -> Unit,
        onHistory: (oldestAt: Long, hasMore: Boolean) -> Unit = { _, _ -> },
        onRotate: (JSONObject) -> Unit = {},
        onNudge: (JSONObject) -> Unit = {},
        onAzone: (JSONObject) -> Unit = {},
    ) {
        val req = Request.Builder().url(url("api/stream") + "?token=$token").build()
        val sse = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        var failure: Throwable? = null
        try {
            val call = sse.newCall(req)
            streamCall = call
            call.execute().use { r ->
                if (r.code == 401) { onAuthFail?.invoke(); throw java.io.IOException("SSE 401") }
                if (!r.isSuccessful) throw java.io.IOException("SSE HTTP " + r.code)
                onOpen()
                val src = r.body?.source() ?: throw java.io.IOException("SSE 没有响应体")
                var event = ""
                val data = StringBuilder()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            dispatch(event, data.toString(), onMessage, onTyping, onHistory, onRotate, onNudge, onAzone)
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
            streamCall = null
        }
        onClosed(failure)
    }

    private fun dispatch(
        event: String,
        payload: String,
        onMessage: (Message) -> Unit,
        onTyping: (Boolean) -> Unit,
        onHistory: (Long, Boolean) -> Unit,
        onRotate: (JSONObject) -> Unit,
        onNudge: (JSONObject) -> Unit,
        onAzone: (JSONObject) -> Unit,
    ) {
        if (payload.isEmpty()) return
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val type = if (event.isNotEmpty()) event else root.optString("type")
        when (type) {
            "message" -> onMessage((root.optJSONObject("message") ?: root).toMessage())
            "typing" -> onTyping(root.optBoolean("on", false))
            // droppedBefore 之前的消息已清理，oldestAt 是兼容字段
            "history" -> onHistory(root.optLong("droppedBefore", root.optLong("oldestAt", 0L)), root.optBoolean("hasMore", false))
            "rotate" -> onRotate(root)
            "nudge" -> onNudge(root)
            "azone" -> onAzone(root)
        }
    }
}
