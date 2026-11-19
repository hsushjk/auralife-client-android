@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.auralife.client.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.ImageCache
import dev.auralife.client.net.Live
import dev.auralife.client.net.Media
import dev.auralife.client.net.FileSend
import dev.auralife.client.net.Message
import dev.auralife.client.net.Quote
import dev.auralife.client.net.QuoteRules
import dev.auralife.client.net.Recorder
import dev.auralife.client.net.Reconnect
import dev.auralife.client.net.SettingsApi
import dev.auralife.client.net.Sticker
import dev.auralife.client.net.VoicePlayer
import dev.auralife.client.net.decodeBytes
import dev.auralife.client.net.decodeInline
import dev.auralife.client.net.prepareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> "0B"
    bytes < 1024 -> bytes.toString() + "B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1fMB", bytes / 1024.0 / 1024.0)
}

private fun readFileSend(ctx: Context, uri: Uri): FileSend? = runCatching {
    val cr = ctx.contentResolver
    var name = "file"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) name = c.getString(i) ?: name
        }
    }
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // 空文件以空 dataUri 作哨兵，调用方跳过
    if (bytes.isEmpty()) return FileSend("", name, mime)
    FileSend("data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), name, mime)
}.getOrNull()

// 缩略图 720px，够 240dp 气泡在 3x 屏显示
private const val THUMB_SIDE = 720

private const val BIG_SIDE = 2160

// 跨气泡共享的当前播放 id，切换时自动复位
private val playingVoiceId = mutableStateOf("")

private data class Pending(
    val key: String,
    val kind: String,
    val text: String,
    val dur: Int = 0,
    val name: String = "",
    val sending: Boolean = true,
    val sent: Boolean = false,
    val error: String = "",
)

private sealed interface Row {
    val key: String

    data class Sep(val label: String, override val key: String) : Row
    data class Item(val m: Message) : Row {
        override val key: String get() = "m:" + m.id
    }
    data class Fail(val p: Pending) : Row {
        override val key: String get() = "f:" + p.key
    }
}

@Composable
fun ChatScreen(api: AuraApi, prefs: Prefs) {
    val list = Live.messages
    var input by remember { mutableStateOf(prefs.draft.ifBlank { "" }) }
    var title by remember { mutableStateOf(prefs.name) }
    val online = Live.online
    val typing = Live.typing
    var typingSent by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var unread by remember { mutableStateOf(0) }
    var atBottom by remember { mutableStateOf(true) }
    // 视口缩矮前先记下 atBottom，缩完再滚
    var armedPin by remember { mutableStateOf(false) }
    val pending = remember { mutableStateListOf<Pending>() }
    val retries = remember { mutableStateMapOf<String, () -> JSONObject>() }
    // pending key → 这条发送附带的引用快照（发送成功才清空 pendingQuote）
    val quotes = remember { mutableStateMapOf<String, Quote>() }
    // mediaId → 本地缓存文件（仅内存）
    var downloadedDocs = remember { mutableStateMapOf<String, File>() }
    var fileAsk by remember { mutableStateOf<List<FileSend>?>(null) }
    // v1.91.78 引用：正在引用的消息快照；跳转后短暂高亮的消息 id
    var pendingQuote by remember { mutableStateOf<Quote?>(null) }
    var flashId by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val recorder = remember { Recorder(ctx) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        note = if (granted && recorder.start()) {
            recording = true
            ""
        } else {
            "不给麦克风权限就发不了语音"
        }
    }
    val state = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ui = Handler(Looper.getMainLooper())
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    DisposableEffect(Unit) {
        Live.visible = true
        val arrive: (Message) -> Unit = { m ->
            if (m.who != "user" && !atBottom) unread += 1
        }
        Live.addArrival(arrive)
        onDispose {
            Live.visible = false
            Live.removeArrival(arrive)
            list.lastOrNull()?.let { prefs.lastRead = it.t }
            VoicePlayer.stop()
            playingVoiceId.value = ""
        }
    }

    LaunchedEffect(Unit) {
        Live.start(api)
        if (Live.botName.isNotBlank()) title = Live.botName
    }

    // 停手 0.5 秒才写草稿
    LaunchedEffect(input) {
        kotlinx.coroutines.delay(500)
        prefs.draft = input
    }

    // rotate 只代表换了一段 session：消息原地保留，提示一句（Live 已自动重拉最新页）
    LaunchedEffect(Live.rotateSeq) {
        if (Live.rotateSeq > 0) {
            note = Reconnect.MSG_ROTATE_CHAT
            kotlinx.coroutines.delay(4000)
            if (note == Reconnect.MSG_ROTATE_CHAT) note = ""
        }
    }

    val rows = remember(list.toList(), query, pending.toList()) { buildRows(list, query, pending) }

    // 文件发送成功的占位，拉到下条消息时清除
    val lastArrived = list.lastOrNull()?.id
    LaunchedEffect(lastArrived) { pending.removeAll { it.sent } }

    var landed by remember { mutableStateOf(false) }
    val lastId = list.lastOrNull()?.id
    LaunchedEffect(lastId, rows.size) {
        if (rows.isEmpty() || searching) return@LaunchedEffect
        if (!landed) {
            kotlinx.coroutines.delay(100)
            repeat(5) {
                state.scrollToItem(0)
                kotlinx.coroutines.delay(160)
            }
            landed = true
            return@LaunchedEffect
        }
        if (atBottom) state.scrollToItem(0)
    }

    // 底部可见 = 最小 index 在视口（reverseLayout 里 0 在最底）
    LaunchedEffect(state) {
        snapshotFlow {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            first <= 1
        }.collect { bottom ->
            atBottom = bottom
            if (bottom) unread = 0
        }
    }

    LaunchedEffect(state) {
        var prev = 0
        snapshotFlow { state.layoutInfo.viewportSize.height }.collect { h ->
            if (h > 0 && prev > 0 && h < prev - 4 && armedPin) {
                armedPin = false
                repeat(3) {
                    state.scrollToItem(0)
                    kotlinx.coroutines.delay(110)
                }
            }
            if (h > 0) prev = h
        }
    }

    LaunchedEffect(showStickers) {
        if (!showStickers) return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) { runCatching { api.stickers() }.getOrNull() }
        if (fresh != null) stickers = fresh
    }


    fun track(
        kind: String,
        preview: String,
        dur: Int = 0,
        name: String = "",
        quote: Quote? = null,
        send: () -> JSONObject,
    ) {
        val key = "p" + System.nanoTime()
        pending.add(Pending(key, kind, preview, dur, name))
        retries[key] = send
        if (quote != null) quotes[key] = quote
        Thread {
            val r = runCatching { send() }.getOrNull()
            val ok = r?.optBoolean("ok") == true || r?.has("messages") == true
            ui.post {
                if (ok) {
                    // 文件发送成功不回 id，其余类型经 SSE 回传
                    if (kind == "file") {
                        val i = pending.indexOfFirst { it.key == key }
                        if (i >= 0) pending[i] = pending[i].copy(sending = false, sent = true)
                    } else {
                        pending.removeAll { it.key == key }
                    }
                    retries.remove(key)
                    // 引用发送成功才清空；失败保留，重试时继续带同一条引用
                    val q = quotes.remove(key)
                    if (q != null && pendingQuote?.id == q.id) pendingQuote = null
                } else {
                    val reason = r?.optString("reason").orEmpty().ifBlank {
                        if (kind == "voice") "语音没发出去" else "网络不通，点重试再发一次"
                    }
                    if (kind == "file") note = reason
                    val i = pending.indexOfFirst { it.key == key }
                    if (i >= 0) pending[i] = pending[i].copy(sending = false, error = reason)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun retry(p: Pending) {
        val f = retries[p.key] ?: return
        val i = pending.indexOfFirst { it.key == p.key }
        if (i >= 0) pending[i] = p.copy(sending = true, error = "")
        Thread {
            val r = runCatching { f() }.getOrNull()
            val ok = r?.optBoolean("ok") == true || r?.has("messages") == true
            ui.post {
                if (ok) {
                    if (p.kind == "file") {
                        val i2 = pending.indexOfFirst { it.key == p.key }
                        if (i2 >= 0) pending[i2] = pending[i2].copy(sending = false, sent = true)
                    } else {
                        pending.removeAll { it.key == p.key }
                    }
                    retries.remove(p.key)
                    val q = quotes.remove(p.key)
                    if (q != null && pendingQuote?.id == q.id) pendingQuote = null
                } else {
                    val i2 = pending.indexOfFirst { it.key == p.key }
                    if (i2 >= 0) pending[i2] = p.copy(sending = false, error = r?.optString("reason").orEmpty().ifBlank { "网络不通，点重试再发一次" })
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun botDisplayName(): String = Live.botName.ifBlank { title.ifBlank { "Ta" } }

    /** 长按菜单「引用这条」：把消息变成快照放进输入区预览条。 */
    fun onQuoteMessage(m: Message) {
        val q = QuoteRules.snapshot(m) ?: return
        pendingQuote = q
        note = ""
        showStickers = false
    }

    /**
     * 点击引用块跳到被引用的消息（web focusQuoted 同流程）：
     * ①本地按 id 找 → 滚动 + 1.4s 高亮；
     * ②找不到 → GET api/messages/locate?id= 反查 → 从当前最老往前翻页（上限 200 页）；
     * ③仍找不到 → 提示「那条消息不在当前加载的记录里」。
     */
    fun jumpToQuote(q: Quote) {
        scope.launch {
            fun rowsNow(): List<Row> = buildRows(Live.messages.toList(), "", emptyList())
            fun findIndex(): Int = rowsNow().indexOfFirst { it is Row.Item && it.m.id == q.id }

            var idx = findIndex()
            if (idx < 0) {
                val loc = withContext(Dispatchers.IO) { runCatching { api.locate(q.id) }.getOrNull() }
                if (loc?.optBoolean("ok") != true) {
                    note = QuoteRules.locateFailure(loc?.optString("reason").orEmpty())
                    return@launch
                }
                val locT = loc.optJSONObject("message")?.optLong("t", 0L) ?: 0L
                val oldest = Live.messages.firstOrNull()?.t ?: 0L
                // 目标 t 比当前最老还新却搜不到：多半已被过期清理，不再翻页
                val mayPage = !(locT > 0 && oldest > 0 && locT >= oldest)
                var pages = 0
                while (mayPage && findIndex() < 0 && Live.hasMore && pages < 200) {
                    Live.loadOlder()
                    pages++
                    var waited = 0
                    while (Live.loadingOlder && waited < 300) {
                        kotlinx.coroutines.delay(20)
                        waited++
                    }
                }
                idx = findIndex()
                if (idx < 0) {
                    note = QuoteRules.MSG_NOT_LOADED
                    return@launch
                }
            }
            val rs = rowsNow()
            val target = rs.indexOfFirst { it is Row.Item && it.m.id == q.id }
            if (target < 0) {
                note = QuoteRules.MSG_NOT_LOADED
                return@launch
            }
            // reverseLayout：index 0 在最底
            state.animateScrollToItem(rs.size - 1 - target)
            flashId = q.id
            kotlinx.coroutines.delay(1400)
            if (flashId == q.id) flashId = ""
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        note = "正在发文件…"
        Thread {
            val out = ArrayList<FileSend>()
            var tooBig = 0
            var empty = 0
            for (u in uris) {
                val f = readFileSend(ctx, u) ?: continue
                if (f.dataUri.isEmpty()) { empty++; continue }
                // 请求体 32MB、单文件 data ≥30MB 服务端静默丢弃；base64 会膨胀，按约 24MB 本地拦截
                if (f.dataUri.length > 24 * 1024 * 1024) { tooBig++; continue }
                out.add(f)
            }
            ui.post {
                note = ""
                when {
                    tooBig > 0 -> note = "超过传输上限（约 24MB），发不了。拆开发或换小的。"
                    empty > 0 && out.isEmpty() -> note = "这是空文件"
                    out.isEmpty() -> note = "文件读不出来"
                    else -> {
                        if (empty > 0) note = "这是空文件"
                        fileAsk = out
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        note = "正在发图…"
        Thread {
            // 图片本地限制 10MB（spec §1.5）
            val size = runCatching {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            if (size > 10L * 1024 * 1024) {
                ui.post { note = "图片太大了（最多 10MB）" }
                return@Thread
            }
            val ready = runCatching { prepareImage(ctx, uri) }.getOrNull()
            if (ready == null) {
                ui.post { note = "这张图读不出来" }
                return@Thread
            }
            ui.post {
                note = ""
                val q = pendingQuote
                track("image", "[图片]", quote = q) { api.sendImage(ready.first, ready.second, quote = q) }
            }
        }.apply { isDaemon = true }.start()
    }

    // 发文件前确认（web：把这些文件发给 Ta？）
    fileAsk?.let { files ->
        AlertDialog(
            onDismissRequest = { fileAsk = null },
            title = { Text("把这些文件发给 Ta？") },
            text = { Text(files.joinToString("\n") { it.name }) },
            confirmButton = {
                TextButton(onClick = {
                    fileAsk = null
                    val names = files.joinToString("、") { it.name }.take(80)
                    val q = pendingQuote
                    track("file", "[文件] $names", name = names, quote = q) { api.sendFiles(files, q) }
                }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { fileAsk = null }) { Text("算了") } },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (searching) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜文字 / 表情 / 图片说明") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { searching = false; query = "" }) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 连点 6 次头像打开调试面板
                val avatar = remember { mutableStateOf<Bitmap?>(null) }
                var avatarTaps by remember { mutableStateOf(0) }
                var showDebug by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val bytes = withContext(Dispatchers.IO) { runCatching { api.fetchBytes("api/avatar") }.getOrNull() }
                    val b = bytes?.let { decodeBytes(it, 96) }
                    avatar.value = b
                }
                Box {
                    if (avatar.value != null) {
                        Image(
                            bitmap = avatar.value!!.asImageBitmap(),
                            contentDescription = "Ta 的头像",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    avatarTaps++
                                    if (avatarTaps == 5) note = "再点一下打开调试面板"
                                    else if (avatarTaps >= 6) {
                                        avatarTaps = 0
                                        showDebug = true
                                    }
                                },
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(36.dp).clip(CircleShape).clickable {
                                avatarTaps++
                                if (avatarTaps == 5) note = "再点一下打开调试面板"
                                else if (avatarTaps >= 6) {
                                    avatarTaps = 0
                                    showDebug = true
                                }
                            },
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Ta", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                if (showDebug) {
                    DebugDialog(
                        api,
                        onClose = {
                            showDebug = false
                            avatarTaps = 0
                            if (note == "再点一下打开调试面板") note = ""
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Live.botName.ifBlank { title.ifBlank { "AuraLife" } },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (unread > 99) "99+" else unread.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                recording -> "正在录音…再按一下发出去"
                                typing -> "${Live.botName.ifBlank { title.ifBlank { "Ta" } }} 正在输入…"
                                Live.connectFailed -> Reconnect.MSG_CONNECT_FAILED
                                Live.ticketFailed -> "实时连接凭证获取失败，正在重试…"
                                online -> "已连接"
                                else -> "连接断了，正在自动重连…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (recording) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                        // 断线/「连接失败」时给一个可见的手动重试入口
                        if (!online) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "重试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { Live.retryNow() }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (note.isNotEmpty()) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(end = 4.dp))
                }
                IconButton(onClick = { searching = true }) {
                    Icon(Icons.Outlined.Search, contentDescription = "搜索")
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                // reverseLayout：0 在最底，旧消息往顶部追加
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // rows 时间正序，倒序喂入：index 0=最新在最底
                items(rows.asReversed(), key = { it.key }) { row ->
                    when (row) {
                        is Row.Sep -> DayDivider(row.label)
                        is Row.Item -> Bubble(
                            api = api,
                            m = row.m,
                            downloaded = downloadedDocs,
                            botName = botDisplayName(),
                            flash = flashId == row.m.id,
                            canQuote = Reconnect.supportsQuote(api.serverVersion),
                            onQuote = { onQuoteMessage(it) },
                            onJumpQuote = { jumpToQuote(it) },
                        )
                        is Row.Fail -> FailBubble(row.p, onRetry = { retry(row.p) }, onDrop = {
                            pending.removeAll { it.key == row.p.key }
                            retries.remove(row.p.key)
                        })
                    }
                }
                if (!searching) {
                    item(key = "older") {
                        OlderHeader(hasMore = Live.hasMore, loading = Live.loadingOlder) {
                            Live.loadOlder()
                        }
                    }
                }
            }
            if (searching && rows.isEmpty()) {
                Text(
                    if (query.isBlank()) "输入关键词开始搜" else "没搜到",
                    Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
            }
            if ((!atBottom && rows.isNotEmpty()) || unread > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clickable {
                            unread = 0
                            atBottom = true
                            scope.launch { state.animateScrollToItem(0) }
                        },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = "回到底部", modifier = Modifier.size(16.dp))
                        if (unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(if (unread > 99) "99+" else unread.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // 输入区上方的引用预览条（v1.91.78）
        pendingQuote?.let { q ->
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    QuoteRules.quoteLine(q, botDisplayName()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { pendingQuote = null }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消引用", modifier = Modifier.size(16.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (showStickers) {
                    showStickers = false
                } else {
                    keyboard?.hide()
                    focus.clearFocus()
                    armedPin = atBottom
                    showStickers = true
                }
            }) {
                Icon(
                    imageVector = if (showStickers) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = "表情",
                )
            }
            IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "发图")
            }
            IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "发文件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { v ->
                    input = v
                    val on = v.isNotBlank()
                    if (on != typingSent) {
                        typingSent = on
                        Thread { runCatching { api.typing(on) } }.start()
                    }
                },
                placeholder = { Text("说点什么…") },
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (it.isFocused) {
                        showStickers = false
                        armedPin = atBottom
                    }
                },
                maxLines = 4,
            )
            IconButton(onClick = {
                if (recording) {
                    val got = recorder.stopAndRead()
                    recording = false
                    if (got == null) {
                        note = "太短了，没发出去"
                    } else {
                        note = ""
                        val q = pendingQuote
                        track("voice", "[语音 ${got.second}s]", got.second, quote = q) { api.sendVoice(got.first, "audio/wav", got.second, q) }
                    }
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                } else if (recorder.start()) {
                    recording = true
                    note = ""
                } else {
                    note = "麦克风打不开"
                }
            }) {
                Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, contentDescription = "语音")
            }
            IconButton(
                enabled = input.isNotBlank(),
                onClick = {
                    val t = input.trim()
                    input = ""
                    prefs.draft = ""
                    val q = pendingQuote
                    track("text", t, quote = q) { api.send(t, q) }
                },
            ) { Text("发送") }
        }

        StickerPanel(
            visible = showStickers,
            api = api,
            stickers = stickers,
            onPick = { s ->
                val q = pendingQuote
                track("sticker", "[表情] ${s.name}".trim(), quote = q) { api.sendSticker(s.md5, q) }
            },
        )
    }
}

private fun buildRows(list: List<Message>, query: String, pending: List<Pending>): List<Row> {
    val q = query.trim()
    val hit = if (q.isEmpty()) list else list.filter {
        it.text.contains(q, true) || it.name.contains(q, true) || it.caption.contains(q, true) ||
            (it.kind == "image" && "图片".contains(q)) || (it.kind == "voice" && "语音".contains(q))
    }
    val out = ArrayList<Row>(hit.size + 8)
    var lastDay = ""
    for (m in hit) {
        val d = dayKey.format(Date(if (m.t > 0) m.t else System.currentTimeMillis()))
        if (d != lastDay) {
            lastDay = d
            out.add(Row.Sep(dayLabel(m.t), "sep:" + d))
        }
        out.add(Row.Item(m))
    }
    if (q.isEmpty()) {
        pending.forEach { out.add(Row.Fail(it)) }
    }
    return out
}

private fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = if (t > 0) t else now.timeInMillis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayMs = 24 * 60 * 60 * 1000L
    val today = now.clone() as Calendar
    today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)
    val todayStart = today.timeInMillis
    val tStart = then.timeInMillis - (then.get(Calendar.HOUR_OF_DAY) * 3600_000L + then.get(Calendar.MINUTE) * 60_000L + then.get(Calendar.SECOND) * 1000L + then.get(Calendar.MILLISECOND))
    return when {
        tStart >= todayStart -> "今天"
        tStart >= todayStart - dayMs -> "昨天"
        sameYear -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(then.timeInMillis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(then.timeInMillis))
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FailBubble(p: Pending, onRetry: () -> Unit, onDrop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (p.sent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.kind == "file") {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (p.kind == "file") p.name.ifBlank { p.text } else p.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                p.sent -> Text(
                    "已发送",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                p.sending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("发送中…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                else -> {
                    Text(
                        p.error.ifBlank { "没发出去" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row {
                        TextButton(onClick = onRetry) { Text("重试", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = onDrop) { Text("扔掉", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerPanel(
    visible: Boolean,
    api: AuraApi,
    stickers: List<Sticker>,
    onPick: (Sticker) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            if (stickers.isEmpty()) {
                Text(
                    "表情库还是空的，去设置里传几张",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(stickers, key = { it.md5 }) { s ->
                        StickerCell(api, s, Modifier.aspectRatio(1f)) { onPick(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerCell(api: AuraApi, s: Sticker, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val key = "sticker:" + s.md5
    var bmp by remember(key) { mutableStateOf(ImageCache.get(key)) }
    LaunchedEffect(key) {
        if (bmp != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { api.fetchBytes(api.stickerUrl(s.md5))?.let { decodeBytes(it, 360) } }
        if (b != null) {
            ImageCache.put(key, b)
            bmp = b
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = s.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp)) }
    }
}

@Composable
private fun LoadedBitmap(key: String, load: suspend () -> Bitmap?, modifier: Modifier) {
    var bmp by remember(key) { mutableStateOf(ImageCache.get(key)) }
    LaunchedEffect(key) {
        if (bmp != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { load() }
        if (b != null) {
            ImageCache.put(key, b)
            bmp = b
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

@Composable
private fun Bubble(
    api: AuraApi,
    m: Message,
    downloaded: MutableMap<String, File>,
    botName: String = "Ta",
    flash: Boolean = false,
    canQuote: Boolean = false,
    onQuote: (Message) -> Unit = {},
    onJumpQuote: (Quote) -> Unit = {},
) {
    val mine = m.who == "user"
    val rowMod = Modifier.fillMaxWidth()
    val arrangement = if (mine) Arrangement.End else Arrangement.Start
    val ctx = LocalContext.current
    var menu by remember(m.id) { mutableStateOf(false) }
    var tip by remember(m.id) { mutableStateOf("") }

    fun copyText() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("AuraLife", m.text.ifBlank { m.caption }))
        tip = "已复制"
    }

    if (m.kind == "sticker" && m.sticker.isNotEmpty()) {
        // 表情先落盘缓存，GIF 走动画解码，静态图回退位图
        val file = remember(m.sticker) { File(ctx.cacheDir, "stk-" + m.sticker.take(32)) }
        var ready by remember(m.sticker) { mutableStateOf(file.exists() && file.length() > 0) }
        LaunchedEffect(m.sticker) {
            if (ready) return@LaunchedEffect
            val ok = withContext(Dispatchers.IO) {
                if (file.exists() && file.length() > 0) return@withContext true
                val bytes = api.fetchBytes(api.stickerUrl(m.sticker)) ?: return@withContext false
                runCatching { file.writeBytes(bytes) }.isSuccess
            }
            if (ok) ready = true
        }
        if (ready) {
            val gif = isGif(file)
            val flashMod = if (flash) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            } else {
                Modifier
            }
            Box {
                Row(rowMod, horizontalArrangement = arrangement) {
                    Column(Modifier.widthIn(max = 240.dp)) {
                        m.quote?.let { QuoteBlock(it, botName) { onJumpQuote(it) } }
                        if (gif) {
                            AnimatedImage(
                                file,
                                Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(flashMod)
                                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
                            )
                        } else {
                            LoadedBitmap(
                                key = "sticker:" + m.sticker,
                                load = { decodeBytes(file.readBytes(), 360) },
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(flashMod)
                                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
                            )
                        }
                    }
                }
                BubbleMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    onCopy = { menu = false; copyText() },
                    onQuote = if (canQuote) { { menu = false; onQuote(m) } } else null,
                )
            }
        }
        return
    }

    if (m.kind == "image" && (m.mediaId.isNotEmpty() || m.image.isNotEmpty() || m.mediaExpired)) {
        var open by remember(m.id) { mutableStateOf(false) }
        // 列表只解缩略图并缓存，避免滚动时按全尺寸重解
        var thumb by remember(m.id) { mutableStateOf<Bitmap?>(null) }
        var big by remember(m.id) { mutableStateOf<Bitmap?>(null) }
        var gone by remember(m.id) { mutableStateOf(m.mediaExpired) }
        LaunchedEffect(m.id) {
            if (m.mediaExpired) return@LaunchedEffect
            val key = "img:" + m.id
            ImageCache.get(key)?.let { thumb = it; return@LaunchedEffect }
            val b = withContext(Dispatchers.IO) {
                if (m.mediaId.isNotEmpty()) Media.bitmap(ctx, api, m.mediaId, THUMB_SIDE)
                else decodeInline(m.image, THUMB_SIDE)
            }
            if (b != null) ImageCache.put(key, b)
            thumb = b
            if (b == null) gone = true
        }
        // 点开大图时才解全尺寸
        LaunchedEffect(open) {
            if (!open || big != null || m.mediaExpired) return@LaunchedEffect
            big = withContext(Dispatchers.IO) {
                if (m.mediaId.isNotEmpty()) Media.bitmap(ctx, api, m.mediaId, BIG_SIDE)
                else decodeInline(m.image, BIG_SIDE)
            }
        }
        val flashMod = if (flash) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier
        }
        Box {
            Row(rowMod, horizontalArrangement = arrangement) {
                Column(Modifier.widthIn(max = 260.dp)) {
                    m.quote?.let { QuoteBlock(it, botName) { onJumpQuote(it) } }
                    when {
                        thumb != null -> {
                            // GIF 用动画解码，<API28 回退静态
                            val gifFile = m.mediaId.takeIf { it.isNotEmpty() }?.let { Media.cached(ctx, it) }?.takeIf { isGif(it) }
                            if (gifFile != null) {
                                AnimatedImage(
                                    gifFile,
                                    Modifier
                                        .widthIn(min = 120.dp, max = 240.dp)
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .then(flashMod)
                                        .combinedClickable(onClick = { open = true }, onLongClick = { menu = true }),
                                )
                            } else {
                                Image(
                                    bitmap = thumb!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .widthIn(min = 120.dp, max = 240.dp)
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .then(flashMod)
                                        .combinedClickable(onClick = { open = true }, onLongClick = { menu = true }),
                                )
                            }
                        }
                        // 服务端按 keepDays 清理后媒体失效
                        gone -> GoneBubble(mine)
                    }
                }
            }
            BubbleMenu(
                expanded = menu,
                onDismiss = { menu = false },
                onCopy = null,
                onQuote = if (canQuote) { { menu = false; onQuote(m) } } else null,
                onSaveImage = thumb?.let { fallback ->
                    {
                        menu = false
                        Thread {
                            // 另存用全尺寸图，不用缩略图
                            val src = if (m.mediaId.isNotEmpty()) Media.bitmap(ctx, api, m.mediaId, BIG_SIDE)
                            else decodeInline(m.image, BIG_SIDE)
                            val ok = saveImage(ctx, src ?: fallback, "auralife-" + m.id.take(8))
                            ui_tip(tip = { s -> tip = s }, ok)
                        }.apply { isDaemon = true }.start()
                    }
                },
            )
            if (tip.isNotEmpty()) {
                Text(tip, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(4.dp))
            }
        }
        if (open && thumb != null) {
            Dialog(
                onDismissRequest = { open = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xF20B0D10))
                        .clickable { open = false },
                    contentAlignment = Alignment.Center,
                ) {
                    val gifFile = m.mediaId.takeIf { it.isNotEmpty() }?.let { Media.cached(ctx, it) }?.takeIf { isGif(it) }
                    if (gifFile != null) {
                        AnimatedImage(gifFile)
                    } else {
                        Image(
                            bitmap = (big ?: thumb)!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
        return
    }

    Row(rowMod, horizontalArrangement = arrangement) {
        val isPlaying = playingVoiceId.value == m.id
        var gone by remember(m.id) { mutableStateOf(m.mediaExpired) }
        var downloading by remember(m.id) { mutableStateOf(false) }
        var progress by remember(m.id) { mutableStateOf(0f) }
        var posSec by remember(m.id) { mutableStateOf(0) }
        // 仅当前播放消息才复位，避免清掉下一条状态
        val reset = {
            if (playingVoiceId.value == m.id) playingVoiceId.value = ""
            progress = 0f
            posSec = 0
            downloading = false
        }
        val onProgress = { cur: Int, total: Int ->
            progress = if (total > 0) (cur.toFloat() / total).coerceIn(0f, 1f) else 0f
            posSec = cur / 1000
        }
        // 语音需先下载到本地
        val onPlay = {
            when {
                isPlaying -> {
                    VoicePlayer.stop()
                    reset()
                }
                m.mediaId.isNotEmpty() -> {
                    playingVoiceId.value = m.id
                    progress = 0f
                    posSec = 0
                    if (Media.cached(ctx, m.mediaId) == null) downloading = true
                    Thread {
                        val f = Media.fetch(ctx, api, m.mediaId)
                        if (f == null) {
                            gone = true
                            reset()
                        } else {
                            downloading = false
                            if (!VoicePlayer.playFile(f, onDone = reset, onProgress = onProgress)) {
                                reset()
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
                m.voice.isNotEmpty() -> {
                    playingVoiceId.value = m.id
                    progress = 0f
                    posSec = 0
                    VoicePlayer.play(ctx, m.voice, onDone = reset, onProgress = onProgress)
                }
                else -> gone = true
            }
        }
        Column(
            Modifier
                .then(
                    if (m.kind == "voice") Modifier.widthIn(min = 130.dp, max = 260.dp)
                    else Modifier.widthIn(max = 300.dp),
                )
                .background(
                    when {
                        flash -> MaterialTheme.colorScheme.tertiaryContainer
                        mine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(12.dp),
                )
                .then(
                    if (m.kind == "voice") {
                        Modifier.combinedClickable(onClick = { onPlay() }, onLongClick = { menu = true })
                    } else {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menu = true },
                        )
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            m.quote?.let { QuoteBlock(it, botName) { onJumpQuote(it) } }
            when (m.kind) {
                "sticker" -> Text(m.caption.ifBlank { m.name.ifBlank { "[表情]" } })
                "image" -> Text(if (mine) "[图片]" else "[Ta 发来一张图]")
                "file" -> {
                    // 未下载显示「下载」，下载后变「打开」
                    var docBusy by remember(m.id) { mutableStateOf(false) }
                    val docScope = rememberCoroutineScope()
                    Column {
                        Text("📎 " + m.fileName.ifBlank { "文件" } + "（" + fmtSize(m.bytes) + "）")
                        val local = downloaded[m.mediaId]
                        TextButton(
                            enabled = !docBusy && m.mediaId.isNotEmpty(),
                            onClick = {
                                val existing = local ?: Media.cached(ctx, m.mediaId)
                                val name = m.fileName.ifBlank { "file-" + m.id.take(8) }
                                val mime = guessMime(m.fileName)
                                if (existing != null) {
                                    Thread { openDoc(ctx, existing, name, mime) }.apply { isDaemon = true }.start()
                                } else {
                                    docBusy = true
                                    docScope.launch {
                                        val f = withContext(Dispatchers.IO) {
                                            val ff = Media.fetch(ctx, api, m.mediaId)
                                            if (ff != null) openDoc(ctx, ff, name, mime)
                                            ff
                                        }
                                        docBusy = false
                                        if (f != null) downloaded[m.mediaId] = f
                                        else tip = "下载失败（文件可能过期了）"
                                    }
                                }
                            },
                        ) {
                            Text(
                                when {
                                    docBusy -> "下载中…"
                                    m.mediaId.isNotEmpty() && (local != null || Media.cached(ctx, m.mediaId) != null) -> "打开"
                                    else -> "下载"
                                },
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                "voice" -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        VoiceBars(progress = if (isPlaying) progress else 0f, playing = isPlaying)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                gone -> "[语音已过期]"
                                downloading -> "下载中…"
                                isPlaying -> "${posSec.coerceAtLeast(0)}″ / ${m.dur}″"
                                m.dur > 0 -> "${m.dur}″"
                                else -> "语音"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 服务端 ASR 转写后同 id 重发，带 heard
                    if (m.heard.isNotBlank()) {
                        Text(
                            "听成了：${m.heard}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                else -> Text(m.text)
            }
            Text(
                clock.format(Date(if (m.t > 0) m.t else System.currentTimeMillis())),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End),
            )
            if (tip.isNotEmpty()) {
                Text(tip, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Box {
            BubbleMenu(
                expanded = menu,
                onDismiss = { menu = false },
                onQuote = if (canQuote) { { menu = false; onQuote(m) } } else null,
                onCopy = if (m.kind == "text") {
                    { menu = false; copyText() }
                } else null,
                onSaveImage = if (m.kind == "image") {
                    {
                        menu = false
                        Thread {
                            val bmp = if (m.mediaId.isNotEmpty()) {
                                api.mediaBytes(m.mediaId)?.let { decodeBytes(it, BIG_SIDE) }
                            } else {
                                decodeInline(m.image, BIG_SIDE)
                            }
                            val ok = bmp != null && saveImage(ctx, bmp, "auralife-" + m.id.take(8))
                            ui_tip(tip = { s -> tip = s }, ok)
                        }.apply { isDaemon = true }.start()
                    }
                } else null,
                // 收进表情库：把聊天里的图存成表情（web 同功能）
                onSticker = if (m.kind == "image") {
                    {
                        menu = false
                        Thread {
                            val bytes = if (m.mediaId.isNotEmpty()) {
                                api.mediaBytes(m.mediaId)
                            } else {
                                runCatching { Base64.decode(m.image, Base64.DEFAULT) }.getOrNull()
                            }
                            val ok = bytes != null && runCatching {
                                SettingsApi.uploadSticker(api, Base64.encodeToString(bytes, Base64.NO_WRAP), "来自聊天").optBoolean("ok")
                            }.getOrDefault(false)
                            ui_tip(tip = { s -> tip = s }, ok)
                        }.apply { isDaemon = true }.start()
                    }
                } else null,
                onSaveVoice = if (m.kind == "voice") {
                    {
                        menu = false
                        Thread {
                            val f = when {
                                m.mediaId.isNotEmpty() -> Media.fetch(ctx, api, m.mediaId)
                                m.voice.isNotEmpty() -> writeTempWav(ctx, m.voice, m.id)
                                else -> null
                            }
                            val ok = f != null && saveFile(ctx, f, "auralife-" + m.id.take(8) + ".wav", "audio/wav")
                            ui_tip(tip = { s -> tip = s }, ok)
                        }.apply { isDaemon = true }.start()
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun BubbleMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onQuote: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onSaveImage: (() -> Unit)? = null,
    onSaveVoice: (() -> Unit)? = null,
    onSticker: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onQuote != null) DropdownMenuItem(text = { Text("引用这条") }, onClick = onQuote)
        if (onCopy != null) DropdownMenuItem(text = { Text("复制文字") }, onClick = onCopy)
        if (onSaveImage != null) DropdownMenuItem(text = { Text("保存图片") }, onClick = onSaveImage)
        if (onSticker != null) DropdownMenuItem(text = { Text("收进表情库") }, onClick = onSticker)
        if (onSaveVoice != null) DropdownMenuItem(text = { Text("保存语音") }, onClick = onSaveVoice)
        if (onQuote == null && onCopy == null && onSaveImage == null && onSaveVoice == null && onSticker == null) {
            DropdownMenuItem(text = { Text("长按没有可用操作") }, onClick = onDismiss, enabled = false)
        }
    }
}

/**
 * 气泡里的引用块：左侧竖线 + 引用人 + 快照（单行省略），点击跳到被引用的消息。
 * 显示直接用服务端返回的 quote.text；引用人 bot 显示 Ta 的名字，其余显示「你」。
 */
@Composable
private fun QuoteBlock(q: Quote, botName: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(26.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                QuoteRules.quoteName(q, botName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                q.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 统一抛回主线程更新提示
private fun ui_tip(tip: (String) -> Unit, ok: Boolean) {
    Handler(Looper.getMainLooper()).post { tip(if (ok) "已保存" else "保存失败") }
}

// 读文件头判断 GIF
private fun isGif(f: File): Boolean = runCatching {
    val b = ByteArray(3)
    var n = 0
    f.inputStream().use { s -> while (n < 3) { val r = s.read(b, n, 3 - n); if (r < 0) break; n += r } }
    n == 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
}.getOrDefault(false)

// API 28+ 用 ImageDecoder 播 GIF，旧系统回退静态帧
@Composable
private fun AnimatedImage(f: File, modifier: Modifier = Modifier.fillMaxSize().padding(16.dp)) {
    if (Build.VERSION.SDK_INT >= 28) {
        val ctx = LocalContext.current
        val drawable = remember(f) {
            runCatching {
                val src = android.graphics.ImageDecoder.createSource(f)
                val d = android.graphics.ImageDecoder.decodeDrawable(src)
                d as? android.graphics.drawable.AnimatedImageDrawable
            }.getOrNull()
        }
        if (drawable != null) {
            LaunchedEffect(drawable) { drawable.start() }
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = modifier,
                factory = { android.widget.ImageView(it).apply { setImageDrawable(drawable) } },
            )
            return
        }
    }
    val bmp = remember(f) { decodeBytes(runCatching { f.readBytes() }.getOrNull() ?: byteArrayOf(), 1080) }
    if (bmp != null) Image(bmp.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
}

// 存进 Downloads/AuraLife 并用系统应用打开
private fun openDoc(ctx: Context, src: File, name: String, mime: String): Boolean = runCatching {
    val uri: android.net.Uri?
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AuraLife")
        }
        uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AuraLife").apply { mkdirs() }
        val dst = File(dir, name)
        src.copyTo(dst, overwrite = true)
        uri = android.net.Uri.fromFile(dst)
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        setDataAndType(uri, mime)
    }
    ctx.startActivity(intent)
    true
}.getOrDefault(false)

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "markdown", "log", "conf", "ini", "yml", "yaml" -> "text/plain"
    "json" -> "application/json"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "zip" -> "application/zip"
    "gz" -> "application/gzip"
    "7z" -> "application/x-7z-compressed"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "apk" -> "application/vnd.android.package-archive"
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv" -> "application/octet-stream"
    else -> "application/octet-stream"
}

private fun saveImage(ctx: Context, bmp: Bitmap, name: String): Boolean = runCatching {    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AuraLife")
        }
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
    true
}.getOrDefault(false)

// 存进 Music/AuraLife，API<29 直接写公共目录
private fun saveFile(ctx: Context, src: File, name: String, mime: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AuraLife")
            put(MediaStore.Audio.Media.IS_MUSIC, true)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
        true
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AuraLife").apply { mkdirs() }
        src.copyTo(File(dir, name), overwrite = true)
        true
    }
}.getOrDefault(false)

// 老协议内联语音先落临时文件
private fun writeTempWav(ctx: Context, b64: String, id: String): File? = runCatching {
    val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    File(ctx.cacheDir, "voice-" + id.take(8) + ".wav").apply { writeBytes(raw) }
}.getOrNull()

@Composable
private fun VoiceBars(progress: Float, playing: Boolean) {
    val heights = listOf(6, 12, 18, 10, 15, 8)
    val lit = (progress.coerceIn(0f, 1f) * heights.size).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (playing && i < lit) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    ),
            )
        }
    }
}

@Composable
private fun GoneBubble(mine: Boolean) {
    Text(
        if (mine) "图片已经过期了" else "图片已经过期了",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Gray,
        modifier = Modifier
            .background(
                if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun OlderHeader(hasMore: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            hasMore -> TextButton(onClick = onClick) {
                Text("查看更早的记录", style = MaterialTheme.typography.bodySmall)
            }
            else -> Text("没有更早的记录", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

private data class DebugAction(
    val key: String,
    val label: String,
    val module: String,
    val action: String,
    val def: String = "{}",
    val burn: Boolean = false,
    val danger: Boolean = false,
)

private val debugGroups: List<Pair<String, List<DebugAction>>> = listOf(
    "记忆" to listOf(
        DebugAction("mem-list", "列事实", "memory", "list"),
        DebugAction("mem-seed", "种子", "memory", "seed", """{"facts":[{"text":"测试事实：主人最近喜欢喝热水","conf":0.7,"tags":["debug"]}]}"""),
        DebugAction("mem-forget", "忘掉一条", "memory", "forget", """{"id":""}"""),
        DebugAction("mem-consolidate", "整理对话", "memory", "consolidate", burn = true),
        DebugAction("mem-replay", "记忆重放", "memory", "replay", burn = true),
        DebugAction("mem-clear", "清空记忆", "memory", "clear", danger = true),
    ),
    "内分泌" to listOf(
        DebugAction("chem-set", "设置", "chemistry", "set", """{"dopamine":50,"reason":"debug"}"""),
        DebugAction("chem-reset", "重置", "chemistry", "reset"),
        DebugAction("chem-event", "事件", "chemistry", "event", """{"dopamine":5}"""),
    ),
    "体力" to listOf(
        DebugAction("energy-set", "设置", "energy", "set", """{"level":100}"""),
        DebugAction("energy-reset", "重置", "energy", "reset"),
        DebugAction("energy-spend", "消耗", "energy", "spend"),
    ),
    "执念" to listOf(
        DebugAction("wish-list", "列执念", "wishes", "list"),
        DebugAction("wish-add", "加一条", "wishes", "add", """{"text":"debug 执念"}"""),
        DebugAction("wish-resolve", "了结一条", "wishes", "resolve", "{}"),
        DebugAction("wish-clear", "清空执念", "wishes", "clear", danger = true),
    ),
    "自我" to listOf(
        DebugAction("self-list", "列自我", "self", "list"),
        DebugAction("self-set", "设置", "self", "set", """{"field":"traits","entries":[{"label":"debug","conf":0.6,"src":"manual"}]}"""),
        DebugAction("self-remove", "移除一条", "self", "remove", """{"field":"traits","label":""}"""),
        DebugAction("self-reset", "重置", "self", "reset"),
    ),
    "身份" to listOf(
        DebugAction("idn-set", "设置", "identity", "set", """{"name":"","ownerName":"","body":""}"""),
        DebugAction("idn-reset", "重置", "identity", "reset"),
    ),
    "闹钟" to listOf(
        DebugAction("alarm-list", "列闹钟", "alarm", "list"),
        DebugAction("alarm-set", "设闹钟", "alarm", "set", """{"in_minutes":1,"reason":"debug 闹钟"}"""),
        DebugAction("alarm-cancel", "取消一个", "alarm", "cancel", """{"id":""}"""),
        DebugAction("alarm-clear", "清空闹钟", "alarm", "clear", danger = true),
    ),
    "聊天" to listOf(
        DebugAction("chat-inject", "注入消息", "chat", "inject", """{"who":"user","text":"debug 假消息"}"""),
        DebugAction("chat-prune", "裁剪记录", "chat", "prune", danger = true),
    ),
    "跑一轮" to listOf(
        DebugAction("run-drive", "叫Ta一次", "", "drive", burn = true),
        DebugAction("run-diary", "让Ta写日记", "", "diary", burn = true),
        DebugAction("run-social", "跑一轮社交", "", "social", burn = true),
    ),
)

@Composable
private fun DebugDialog(api: AuraApi, onClose: () -> Unit) {
    var out by remember { mutableStateOf("（操作结果会显示在这里）") }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("{}") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun execute(a: DebugAction) {
        if (busy) return
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (parsed == null) {
            out = "参数不是合法 JSON，改好再执行"
            return
        }
        // 这些动作必须带定位参数，否则空参发过去只会得到「没找到」
        val required = when (a.key) {
            "mem-forget" -> listOf("id")
            "wish-resolve" -> listOf("id", "text")
            "self-remove" -> listOf("label")
            "alarm-cancel" -> listOf("id")
            else -> emptyList()
        }
        if (required.isNotEmpty() && required.none { parsed.optString(it).isNotBlank() }) {
            out = "先填参数：${required.joinToString(" 或 ")} 至少填一个"
            return
        }
        busy = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    if (a.module.isEmpty()) {
                        parsed.put("what", a.action)
                        api.call("api/test", parsed, "POST")
                    } else {
                        parsed.put("module", a.module).put("action", a.action)
                        api.call("api/debug", parsed, "POST")
                    }
                }
            }.getOrNull()
            busy = false
            out = (r ?: JSONObject()).toString(2).take(4000)
        }
    }

    fun onClick(a: DebugAction) {
        when {
            a.danger -> {
                // 危险动作两击确认，3 秒未点自动回退
                if (confirmKey == a.key) {
                    confirmKey = null
                    body = a.def
                    execute(a)
                } else {
                    confirmKey = a.key
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (confirmKey == a.key) confirmKey = null
                    }
                }
            }
            expanded == a.key -> expanded = null
            else -> {
                expanded = a.key
                body = a.def
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("调试面板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                Text(
                    "只有标「（调模型）」的动作会消耗模型额度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    debugGroups.forEach { (title, actions) ->
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        actions.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { a ->
                                    val confirming = confirmKey == a.key
                                    TextButton(
                                        onClick = { onClick(a) },
                                        enabled = !busy,
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = if (confirming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        ),
                                    ) {
                                        Text(
                                            if (confirming) "再点一下确认"
                                            else a.label + if (a.burn) "（调模型）" else "",
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                        if (expanded != null) {
                            val a = actions.firstOrNull { it.key == expanded }
                            if (a != null) {
                                OutlinedTextField(
                                    value = body,
                                    onValueChange = { body = it },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text("参数（JSON）") },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { execute(a) }, enabled = !busy) { Text("执行", fontSize = 12.sp) }
                                    TextButton(onClick = { expanded = null }, enabled = !busy) { Text("收起", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                if (busy) Text("正在执行…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = out,
                    onValueChange = { out = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 200.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    readOnly = true,
                )
            }
        }
    }
}
