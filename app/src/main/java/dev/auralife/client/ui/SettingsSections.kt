package dev.auralife.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.SettingsApi
import dev.auralife.client.net.VoicePlayer
import dev.auralife.client.net.decodeBytes
import dev.auralife.client.net.prepareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File


// 接口统一回 {ok, reason|message}，失败取 reason
internal fun msgOf(r: JSONObject?): String = when {
    r == null -> "没连上服务端"
    r.optBoolean("ok") -> r.optString("message").ifBlank { "好了" }
    else -> r.optString("reason").ifBlank { r.optString("message").ifBlank { "没成" } }
}

@Composable
internal fun LoadJson(key: String, loader: suspend () -> JSONObject?, content: @Composable (JSONObject) -> Unit) {
    var j by remember(key) { mutableStateOf<JSONObject?>(null) }
    var err by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val r = runCatching { loader() }
        j = r.getOrNull()
        if (r.isFailure) err = r.exceptionOrNull()?.message ?: "拉不到数据"
    }
    when {
        err != null -> Err(err!!)
        j == null -> LoadingRow()
        else -> content(j!!)
    }
}

private val EDGE_VOICES = listOf(
    "zh-CN-XiaoxiaoNeural" to "晓晓 · 温暖女声",
    "zh-CN-XiaoyiNeural" to "晓伊 · 活泼女声",
    "zh-CN-XiaochenNeural" to "晓辰 · 清亮女声",
    "zh-CN-XiaohanNeural" to "晓涵 · 知性女声",
    "zh-CN-XiaomengNeural" to "晓梦 · 少女",
    "zh-CN-XiaomoNeural" to "晓墨 · 低柔女声",
    "zh-CN-YunxiNeural" to "云希 · 少年",
    "zh-CN-YunjianNeural" to "云健 · 浑厚男声",
    "zh-CN-YunxiaNeural" to "云夏 · 少年",
    "zh-CN-YunyangNeural" to "云扬 · 播报男声",
    "zh-CN-liaoning-XiaobeiNeural" to "晓北 · 东北女声",
    "zh-CN-shaanxi-XiaoniNeural" to "晓妮 · 陕西女声",
    "zh-HK-HiuMaanNeural" to "曉曼 · 粤语女声",
    "zh-HK-WanLungNeural" to "雲龍 · 粤语男声",
    "zh-TW-HsiaoChenNeural" to "曉臻 · 台湾女声",
    "zh-TW-YunJheNeural" to "雲哲 · 台湾男声",
)


@Composable
internal fun MasterSection(cfg: CfgState) {
    Panel("开关") {
        Toggle("总开关", cfg.bool("enabled", true), hint = "关掉后 Ta 休眠：不回复、不搭话、不整理记忆；再打开会从刚才的状态继续", onChange = { cfg.set("enabled", it) })
        Toggle("语音合成（TTS）", cfg.bool("voice.enabled", false), hint = "Ta 说出来的声音", onChange = { cfg.set("voice.enabled", it) })
        Toggle("语音识别（STT）", cfg.str("voice.asr") != "off", hint = "Ta 的耳朵：听你发来的语音条", onChange = { on ->
            cfg.set("voice.asr", if (on) "dsh" else "off")
        })
        Toggle("生图", cfg.bool("imageGen.enabled", false), hint = "Ta 想画就画", onChange = { cfg.set("imageGen.enabled", it) })
        Toggle("提示音", cfg.bool("sound.enabled", true), hint = "来消息的提示音效", onChange = { cfg.set("sound.enabled", it) })
    }
}


@Composable
internal fun AppearanceSection(cfg: CfgState, theme: String, onTheme: (String) -> Unit, onNote: (String) -> Unit, showBackground: Boolean = true) {
    val ctx = LocalContext.current
    Panel("外观") {
        PickRow("主题", theme, ThemeMode.entries.map { it.key to it.label }) { onTheme(it) }
        Hint("仅 亚克力/毛玻璃 支持背景图")
        if (showBackground) {
            val file = File(ctx.filesDir, "backdrop.jpg")
            EntryImageRow(
                label = "背景图",
                round = false,
                note = onNote,
                loadBytes = { file.takeIf { it.exists() && it.length() > 0 }?.readBytes() },
                upload = { (b64, _) ->
                    file.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    Backdrop.reload(ctx)
                    "存好了"
                },
                clear = {
                    file.delete()
                    Backdrop.reload(ctx)
                    "已清除"
                },
            )
        }
    }
}

@Composable
private fun EntryImageRow(
    label: String,
    round: Boolean,
    note: (String) -> Unit,
    loadBytes: suspend () -> ByteArray?,
    upload: suspend (prepared: Pair<String, String>) -> String,
    clear: suspend () -> String,
) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var bytes by remember(tick) { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        bytes = withContext(Dispatchers.IO) { runCatching { loadBytes() }.getOrNull() }
    }

    fun doUpload(uri: android.net.Uri) {
        scope.launch {
            note("上传中…")
            val prepared = withContext(Dispatchers.IO) { runCatching { prepareImage(ctx, uri) }.getOrNull() }
            if (prepared == null) {
                note("这张图读不出来")
            } else {
                val msg = withContext(Dispatchers.IO) { runCatching { upload(prepared) }.getOrNull() }
                note(msg ?: "没存上")
                tick++
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) doUpload(uri)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
        val bmp = bytes?.let { decodeBytes(it, 320) }
        Box(
            Modifier
                .then(if (round) Modifier.size(56.dp) else Modifier.width(84.dp).height(56.dp))
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (round) "选张图" else "上传", fontSize = 13.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    val msg = withContext(Dispatchers.IO) { runCatching { clear() }.getOrNull() }
                    note(msg ?: "好了")
                    tick++
                }
            }) { Text(if (round) "用默认图标" else "清除", fontSize = 13.sp) }
        }
    }
}


@Composable
internal fun ModelSection(api: AuraApi, cfg: CfgState) {
    Panel("模型") {
        LoadJson("models", loader = { withContext(Dispatchers.IO) { runCatching { SettingsApi.models(api) }.getOrNull() } }) { j ->
            val arr = j.optJSONArray("models") ?: JSONArray()
            val opts = mutableListOf("" to "默认（跟随 DSH）")
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val prov = m.optString("provider")
                val id = m.optString("model")
                opts += (prov + "/" + id) to (m.optString("name").ifBlank { id } + " · " + prov)
            }
            val prov = cfg.str("model.provider")
            val cur = if (prov.isBlank()) "" else prov + "/" + cfg.str("model.model")
            PickRow("模型", cur, opts) { v ->
                if (v.isBlank()) {
                    cfg.set("model.provider", "")
                    cfg.set("model.model", "")
                } else {
                    val i = v.indexOf('/')
                    if (i > 0) {
                        cfg.set("model.provider", v.substring(0, i))
                        cfg.set("model.model", v.substring(i + 1))
                    }
                }
            }
        }
        PickRow(
            "思考强度",
            cfg.str("model.reasoningEffort", "low"),
            listOf("off" to "关", "low" to "低", "high" to "高", "max" to "满", "custom" to "自定义"),
        ) { cfg.set("model.reasoningEffort", it) }
        if (cfg.str("model.reasoningEffort", "low") == "custom") {
            CfgText("自定义强度（按平台文档填）", "model.reasoningEffortCustom", cfg)
        }
    }
}


@Composable
internal fun ReplySection(cfg: CfgState) {
    Panel("回复节奏") {
        CfgText("停顿几秒再回", "merge.waitSec", cfg, numeric = true, decimals = true)
        CfgText("你在打字时先等几秒", "merge.waitTypingSec", cfg, numeric = true)
    }
}


@Composable
internal fun VoiceSection(api: AuraApi, cfg: CfgState, onNote: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("晚安啦，今天也辛苦了，明天见～") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val provider = cfg.str("voice.provider", "edge")

    Panel("语音（TTS）") {
        Toggle("发语音（TTS）", cfg.bool("voice.enabled"), onChange = { cfg.set("voice.enabled", it) })
        PickRow(
            "引擎",
            provider,
            listOf(
                "edge" to "微软 edge-tts",
                "windows" to "Windows 自带（仅 Windows）",
                "custom" to "自定义接口",
            ),
        ) { cfg.set("voice.provider", it) }

        when (provider) {
            "edge" -> {
                var edgeTick by remember { mutableStateOf(0) }
                LoadJson("edge-$edgeTick", loader = {
                    withContext(Dispatchers.IO) { runCatching { SettingsApi.edgeInstalled(api) }.getOrNull() }
                }) { j ->
                    val installed = j?.optBoolean("installed") == true
                    InfoRow("edge-tts 状态", if (installed) "已经装好了" else "还没装")
                    if (!installed) {
                        TextButton(onClick = {
                            scope.launch {
                                busy = true
                                val r = withContext(Dispatchers.IO) { runCatching { SettingsApi.edgeInstall(api) }.getOrNull() }
                                busy = false
                                note = if (r?.optBoolean("ok") == true) "装好了" else (r?.optString("reason").orEmpty().ifBlank { "没装上" })
                                onNote(note)
                                edgeTick++
                            }
                        }) { Text("安装", fontSize = 13.sp) }
                    }
                }
                PickRow("音色", cfg.str("voice.voice"), EDGE_VOICES) { cfg.set("voice.voice", it) }
                PickRow("语速", cfg.str("voice.rate", "+0%"), listOf("-30%" to "-30%", "-15%" to "-15%", "+0%" to "+0%", "+15%" to "+15%", "+30%" to "+30%")) {
                    cfg.set("voice.rate", it)
                }
            }
            "windows" -> {
                CfgText("音色", "voice.voice", cfg, hint = "Windows 里装的语音名，例如 Microsoft Huihui Desktop")
                PickRow("语速", cfg.str("voice.rate", "+0%"), listOf("-30%" to "-30%", "-15%" to "-15%", "+0%" to "+0%", "+15%" to "+15%", "+30%" to "+30%")) {
                    cfg.set("voice.rate", it)
                }
            }
            "custom" -> {
                Hint("引擎走自定义接口：在服务端数据目录的 local/tts.mjs 里接你自己的 TTS 服务，参数和服务地址都在那里配。")
                Hint("详细写法看项目里的 Interface.md；改完脚本保存即生效。")
            }
        }

        OutlinedTextField(
            text,
            { text = it },
            label = { Text("试听") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        TextButton(enabled = !busy && text.isNotBlank(), onClick = {
            scope.launch {
                busy = true
                note = "合成中…"
                val r = withContext(Dispatchers.IO) { runCatching { SettingsApi.tts(api, text.trim()) }.getOrNull() }
                busy = false
                if (r?.optBoolean("ok") == true && r.optString("data").isNotBlank()) {
                    note = "试听一下"
                    VoicePlayer.play(ctx, r.optString("data"))
                } else {
                    note = r?.optString("reason").orEmpty().ifBlank { "合成不出来" }
                }
                onNote(note)
            }
        }) { Text("生成", fontSize = 13.sp) }
        Hint("先保存设置再试听；试听不影响真正发消息的开关")
        Hint(note)
    }
}


@Composable
internal fun SttSection(api: AuraApi, cfg: CfgState) {
    val cur = cfg.str("voice.asr", "off")
    Panel("听语音（STT）") {
        PickRow(
            "识别引擎",
            cur,
            listOf(
                "off" to "关",
                "dsh" to "DSH 自带",
                "custom" to "自定义接口",
            ),
        ) { cfg.set("voice.asr", it) }
        when (cur) {
            "dsh" -> {
                Hint("识别走 DSH 桌面版自带能力，本地推理、不用填地址；只在 DSH 桌面版里可用。")
                LoadJson("asr-status", loader = { withContext(Dispatchers.IO) { runCatching { SettingsApi.status(api) }.getOrNull() } }) { j ->
                    val a = j.optJSONObject("asr")
                    val state = when {
                        a == null || !a.optBoolean("host", false) -> "宿主没有提供语音识别（桌面版要启用语音输入）"
                        else -> {
                            val arr = a.optJSONArray("providers")
                            if (arr != null && arr.length() > 0) {
                                "可用：" + (0 until arr.length()).joinToString("、") { arr.optString(it) }
                            } else "宿主有服务，但识别器还没注册"
                        }
                    }
                    InfoRow("识别服务", state)
                }
                CfgText("识别语言", "voice.asrLanguage", cfg, hint = "zh / en，留空自动")
            }
            "custom" -> {
                Hint("识别走自定义接口：在服务端数据目录的 local/asr.mjs 里接你自己的 ASR 服务（收 16k wav，回文字）。")
                Hint("详细写法看项目里的 Interface.md；改完脚本保存即生效。")
            }
        }
    }
}


@Composable
internal fun ImageGenSection(cfg: CfgState) {
    val provider = cfg.str("imageGen.provider", "code")
    Panel("生图") {
        Toggle("允许画图", cfg.bool("imageGen.enabled"), onChange = { cfg.set("imageGen.enabled", it) })
        PickRow(
            "画法",
            provider,
            listOf(
                "code" to "代码画",
                "custom" to "自定义接口",
            ),
        ) { cfg.set("imageGen.provider", it) }
        when (provider) {
            "code" -> Hint("代码画：Ta 会直接写一段绘图代码把图画出来，不用连接任何服务。")
            "custom" -> {
                Hint("生图走自定义接口：在服务端数据目录的 local/image.mjs 里接你自己的出图服务（SD WebUI / ComfyUI / 任何 HTTP 接口）。")
                Hint("详细写法看项目里的 Interface.md；改完脚本保存即生效。")
            }
        }
    }
}


@Composable
internal fun StickerSection(api: AuraApi, cfg: CfgState, onNote: (String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var confirmMd5 by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                note = "上传中…"
                for (uri in uris) {
                    val prepared = withContext(Dispatchers.IO) { runCatching { prepareImage(ctx, uri) }.getOrNull() } ?: continue
                    withContext(Dispatchers.IO) { runCatching { SettingsApi.uploadSticker(api, prepared.first, "") } }
                }
                note = "传完了"
                onNote(note)
                tick++
            }
        }
    }

    Panel("表情库") {
        Toggle("偷表情", cfg.bool("stickers.steal", true), hint = "Ta收到你发的图/表情，会自己挑着收进库") {
            cfg.set("stickers.steal", it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("选图（可多选）", fontSize = 13.sp)
            }
        }
        Hint("支持动图；点一下缩略图可以改名或删掉")
        LoadJson("stickers-$tick", loader = { withContext(Dispatchers.IO) { runCatching { SettingsApi.stickers(api) }.getOrNull() } }) { j ->
            val items = j.optJSONArray("items") ?: JSONArray()
            Text("${items.length()} 张", fontSize = 12.sp)
            var row = 0
            val shown = minOf(items.length(), 60)
            while (row * 5 < shown) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 5) {
                        val idx = row * 5 + col
                        if (idx >= shown) break
                        val s = items.optJSONObject(idx) ?: continue
                        val md5 = s.optString("md5")
                        var bmp by remember(md5) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(md5) {
                            val bytes = withContext(Dispatchers.IO) { runCatching { api.fetchBytes("api/sticker/" + md5) }.getOrNull() }
                            if (bytes != null) bmp = withContext(Dispatchers.IO) { decodeBytes(bytes, 160) }
                        }
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable { editing = s },
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), null, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
                row++
            }
        }
        Hint(note)
    }

    val ed = editing
    if (ed != null) {
        val md5 = ed.optString("md5")
        var name by remember(md5) { mutableStateOf(ed.optString("name")) }
        val tg = ed.optJSONArray("tags")
        var tags by remember(md5) { mutableStateOf(if (tg == null) "" else (0 until tg.length()).joinToString(",") { tg.optString(it) }) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("这张表情") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签（逗号隔开）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    scope.launch {
                        val list = tags.split(',', '，', ' ').map { it.trim() }.filter { it.isNotBlank() }
                        val r = withContext(Dispatchers.IO) {
                            runCatching { SettingsApi.renameSticker(api, md5, name.trim(), list) }.getOrNull()
                        }
                        note = msgOf(r)
                        onNote(note)
                        tick++
                    }
                }) { Text("存下") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMd5 = md5; editing = null }) {
                    Text("删掉", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (confirmMd5.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmMd5 = "" },
            title = { Text("删掉这张表情？") },
            confirmButton = {
                TextButton(onClick = {
                    val md5 = confirmMd5
                    confirmMd5 = ""
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { SettingsApi.deleteSticker(api, md5) } }
                        note = "删掉了"
                        tick++
                    }
                }) { Text("删掉") }
            },
            dismissButton = { TextButton(onClick = { confirmMd5 = "" }) { Text("算了") } },
        )
    }
}


@Composable
internal fun HistorySection(cfg: CfgState) {
    Panel("聊天记录") {
        CfgText("保留天数", "history.keepDays", cfg, numeric = true, hint = "聊天记录、图片、语音一起按天清理")
        Hint(
            "聊完（空闲 2 分钟）后按每 100 条自动切成一个文件，一个文件就是界面的一页；" +
                "翻到顶可以点「查看更早的记录」取上一页。图片和语音单独存放，过期后服务器和浏览器缓存一起清。",
        )
    }
}


@Composable
internal fun LogsCfgSection(cfg: CfgState) {
    Panel("日志") {
        CfgText("占用空间（MB）", "logs.maxMB", cfg, numeric = true, decimals = true, hint = "日志不落盘")
    }
}


@Composable
internal fun WebSection(cfg: CfgState) {
    Panel("网页入口") {
        CfgText("监听地址", "web.host", cfg)
        CfgText("监听端口", "web.port", cfg, numeric = true)
    }
}


@Composable
internal fun GuestSection(api: AuraApi, onNote: (String) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var resetFor by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var createdPw by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun create(u: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { SettingsApi.addGuest(api, u, display.trim().ifBlank { u }, "") }.getOrNull()
            }
            if (r?.optBoolean("ok") != true) {
                note = msgOf(r)
                onNote(note)
                return@launch
            }
            // v1.91.78：客人只有「只聊天和空间」这一档，level 参数已取消（服务端也忽略）
            val pw = r.optString("password")
            if (pw.isNotBlank()) createdPw = u to pw
            note = if (pw.isNotBlank()) "创建好了，一次性密码：$pw" else "创建好了"
            onNote(note)
            username = ""
            display = ""
            tick++
        }
    }

    Panel("客人") {
        LoadJson("guests-$tick", loader = { withContext(Dispatchers.IO) { runCatching { SettingsApi.guests(api) }.getOrNull() } }) { j ->
            val list = j.optJSONArray("guests") ?: JSONArray()
            if (list.length() == 0) Text("还没有客人", fontSize = 12.sp)
            for (k in 0 until list.length()) {
                val g = list.optJSONObject(k) ?: continue
                val subject = g.optString("subject")
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${g.optString("displayName").ifBlank { g.optString("username") }}（${g.optString("username")}）", fontSize = 13.sp)
                        Text(
                            "只聊天和空间" + g.optString("note").let { if (it.isBlank()) "" else " · $it" },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        resetFor = subject
                        newPw = ""
                    }) { Text("重置密码", fontSize = 11.sp) }
                    TextButton(onClick = { confirm = subject }) { Text("删除", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(display, { display = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Hint("客人只能聊天和空间；密码只显示这一次。")
            TextButton(onClick = {
                val u = username.trim()
                if (u.isEmpty()) {
                    note = "先填个用户名"
                    onNote(note)
                } else {
                    create(u)
                }
            }) { Text("创建", fontSize = 13.sp) }
            Hint(note)
        }
    }

    createdPw?.let { (name, pw) ->
        AlertDialog(
            onDismissRequest = { createdPw = null },
            title = { Text("客人建好了") },
            text = { Text("创建好了，一次性密码：$pw\n\n这串密码只显示这一次，请抄下来交给 $name。") },
            confirmButton = { TextButton(onClick = { createdPw = null }) { Text("知道了") } },
        )
    }

    if (resetFor.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { resetFor = "" },
            title = { Text("重置密码") },
            text = {
                OutlinedTextField(
                    newPw,
                    { newPw = it },
                    label = { Text("新密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = newPw.length >= 6, onClick = {
                    val subject = resetFor
                    resetFor = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { SettingsApi.patchGuest(api, subject, JSONObject().put("password", newPw)) }.getOrNull()
                        }
                        onNote(msgOf(r))
                        tick++
                    }
                }) { Text("存下") }
            },
            dismissButton = { TextButton(onClick = { resetFor = "" }) { Text("算了") } },
        )
    }
    if (confirm.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text("删掉这个客人？") },
            text = { Text("删掉之后对方就登不上来了，聊天、记忆、日记也会一起清掉，删了就回不来。") },
            confirmButton = {
                TextButton(onClick = {
                    val subject = confirm
                    confirm = ""
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { SettingsApi.deleteGuest(api, subject) } }
                        onNote("删掉了")
                        tick++
                    }
                }) { Text("删掉") }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("算了") } },
        )
    }
}


private fun roleLabel(role: String): String = when (role) {
    "owner" -> "主人（owner）"
    "guest" -> "客人（guest）"
    "" -> "—"
    else -> role
}

@Composable
internal fun AccountSection(api: AuraApi, prefs: Prefs, me: JSONObject?, onLogout: () -> Unit) {
    var changing by remember { mutableStateOf(false) }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Panel("账号") {
        InfoRow("用户名", me?.optString("name").orEmpty().ifBlank { prefs.user })
        InfoRow("角色", roleLabel(me?.optString("role").orEmpty().ifBlank { prefs.role }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { changing = !changing }) { Text(if (changing) "取消" else "改密码") }
            // 本地优先：立即回登录页；服务端 /api/logout 由上层做后台 best-effort（失败忽略）
            TextButton(onClick = { onLogout() }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
        }
        if (changing) {
            OutlinedTextField(
                oldPw,
                { oldPw = it },
                label = { Text("当前密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                newPw,
                { newPw = it },
                label = { Text("新密码（至少 6 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            TextButton(enabled = newPw.length >= 6, onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { SettingsApi.changePassword(api, oldPw, newPw) }.getOrNull() }
                    note = if (r?.optBoolean("ok") == true) "密码改好了" else r?.optString("reason").orEmpty().ifBlank { "改密码失败" }
                }
            }) { Text("保存新密码", modifier = Modifier.padding(top = 2.dp)) }
            Hint(note)
        }
    }
}


@Composable
internal fun CacheSection(bytes: Long, onClear: () -> Unit) {
    Panel("本地缓存") {
        val mb = String.format("%.1f", bytes / 1024.0 / 1024.0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("图片/语音缓存：$mb MB（上限 300 MB，超了自动删最旧的）", fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "清空缓存") }
        }
    }
}
