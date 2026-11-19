package dev.auralife.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Live
import dev.auralife.client.net.SettingsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject


@Composable
fun SpaceScreen(api: AuraApi, owner: Boolean = false, subject: String = "") {
    var tick by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        err = null
        val r = runCatching { withContext(Dispatchers.IO) { SettingsApi.diary(api) } }
        if (r.isSuccess) data = r.getOrNull() else err = r.exceptionOrNull()?.message ?: "拉不到空间"
    }

    // 空间有新动态时原地刷新：azone 事件由 Live 在 UI 线程递增序号，避免 SSE 线程直接写 Compose
    LaunchedEffect(Live.azoneSeq) {
        if (Live.azoneSeq > 0) tick++
    }

    val entries = (data?.opt("entries") as? JSONArray ?: JSONArray())
    val stats = data?.optJSONObject("stats") ?: JSONObject()

    Column(Modifier.fillMaxSize()) {
        PageHeader("空间") {
            val total = stats.optInt("total", entries.length())
            val today = stats.optInt("today", 0)
            Text("共 $total 篇 · 今天 $today 篇", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Note(note)
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            if (err != null) item { Text(err!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (data != null && entries.length() == 0) item { EmptyText("还没有动态。") }
            items((0 until entries.length()).toList()) { i ->
                val e = entries.optJSONObject(i) ?: return@items
                EntryCard(
                    api = api,
                    entry = e,
                    owner = owner,
                    subject = subject,
                    onChanged = { tick++ },
                    onNote = { note = it },
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    api: AuraApi,
    entry: JSONObject,
    owner: Boolean,
    subject: String,
    onChanged: () -> Unit,
    onNote: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val id = entry.optString("id")
    val comments = entry.opt("comments") as? JSONArray ?: JSONArray()
    val drafts = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }

    fun sendComment() {
        val text = drafts[id].orEmpty().trim()
        if (text.isEmpty() || busy) return
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { SettingsApi.diaryComment(api, id, text) } }.getOrNull()
            busy = false
            // v1.91.78：服务端如实返回 ok:false+reason；网络异常时不当作成功
            if (r?.optBoolean("ok") == true) {
                drafts[id] = ""
                onChanged()
            } else {
                onNote(r?.optString("reason").orEmpty().ifBlank { r?.optString("message").orEmpty() }.ifBlank { "没发出去" })
            }
        }
    }

    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtWhen(entry.optLong("at")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.optString("visibility", "all") == "self") {
                Text("  仅主人可见", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(entry.optString("text"), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        if (comments.length() == 0) {
            EmptyText("还没有评论")
        } else {
            for (ci in 0 until comments.length()) {
                val c = comments.optJSONObject(ci) ?: continue
                CommentRow(
                    api = api,
                    comment = c,
                    entryId = id,
                    replyId = "",
                    commentId = c.optString("id"),
                    owner = owner,
                    subject = subject,
                    indent = 0,
                    onChanged = onChanged,
                    onNote = onNote,
                )
                val replies = c.opt("replies") as? JSONArray ?: JSONArray()
                for (ri in 0 until replies.length()) {
                    val rep = replies.optJSONObject(ri) ?: continue
                    CommentRow(
                        api = api,
                        comment = rep,
                        entryId = id,
                        replyId = rep.optString("id"),
                        commentId = c.optString("id"),
                        owner = owner,
                        subject = subject,
                        indent = 1,
                        onChanged = onChanged,
                        onNote = onNote,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = drafts[id].orEmpty(),
                onValueChange = { drafts[id] = it },
                placeholder = { Text("说点什么…", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendComment() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { sendComment() }, enabled = !busy) { Text("评论", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun CommentRow(
    api: AuraApi,
    comment: JSONObject,
    entryId: String,
    replyId: String,
    commentId: String = "",
    owner: Boolean,
    subject: String,
    indent: Int,
    onChanged: () -> Unit,
    onNote: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val from = comment.optString("from")
    val mine = subject.isNotBlank() && from == subject
    var replying by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun sendReply() {
        val text = replyText.trim()
        if (text.isEmpty() || busy) return
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { SettingsApi.diaryReply(api, entryId, commentId, text) } }.getOrNull()
            busy = false
            // v1.91.78：服务端如实返回 ok:false+reason；网络异常时不当作成功
            if (r?.optBoolean("ok") == true) {
                replyText = ""
                replying = false
                onChanged()
            } else {
                onNote(r?.optString("reason").orEmpty().ifBlank { r?.optString("message").orEmpty() }.ifBlank { "没发出去" })
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = if (indent > 0) 20.dp else 0.dp, top = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                comment.optString("name").ifBlank { from },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(" ${comment.optString("text")}", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("  " + fmtStamp(comment.optLong("at")), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (indent == 0) {
                IconButton(onClick = { replying = !replying }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = "回复")
                }
            }
        }
        if (replying) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    placeholder = { Text("回复…", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendReply() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Button(onClick = { sendReply() }, enabled = !busy) { Text("回复", fontSize = 13.sp) }
            }
        }
    }
}


@Composable
fun MoodScreen(api: AuraApi, owner: Boolean = false) {
    var life by remember { mutableStateOf<JSONObject?>(null) }
    var organs by remember { mutableStateOf<JSONArray?>(null) }
    var cfg by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var organDetail by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        err = null
        val m = runCatching { withContext(Dispatchers.IO) { SettingsApi.life(api) } }
        if (m.isSuccess) life = m.getOrNull() else err = m.exceptionOrNull()?.message ?: "拉不到生命状态"
        val o = runCatching { withContext(Dispatchers.IO) { SettingsApi.organs(api) } }.getOrNull()
        organs = (o?.opt("organs") as? JSONArray) ?: (o?.opt("list") as? JSONArray) ?: JSONArray()
        val c = runCatching { withContext(Dispatchers.IO) { SettingsApi.config(api) } }.getOrNull()
        cfg = c?.optJSONObject("config")
    }

    val chem = life?.optJSONObject("chemistry") ?: JSONObject()
    val dop = chem.optDouble("dopamine", 0.0)
    val cor = chem.optDouble("cortisol", 0.0)
    val ser = chem.optDouble("serotonin", 0.0)
    val hist = chem.optJSONArray("history") ?: JSONArray()
    val energy = life?.optJSONObject("energy") ?: JSONObject()
    val wishes = life?.optJSONArray("wishes") ?: JSONArray()
    val alarms = life?.optJSONArray("alarms") ?: JSONArray()
    val idn = life?.optJSONObject("identity") ?: JSONObject()
    val self = life?.optJSONObject("self") ?: JSONObject()
    val mdl = life?.optJSONObject("model") ?: JSONObject()
    val ctx = life?.optJSONObject("context") ?: JSONObject()
    val drive = life?.optJSONObject("drive") ?: JSONObject()
    val boot = life?.optJSONObject("boot") ?: JSONObject()
    val rhythm = drive.optJSONObject("rhythm") ?: JSONObject()
    val assess = drive.optJSONObject("lastAssess")
    val selfCount =
        (self.optJSONArray("traits")?.length() ?: 0) + (self.optJSONArray("values")?.length() ?: 0) + (self.optJSONArray("boundaries")?.length() ?: 0)
    val openWishes = (0 until wishes.length()).count {
        wishes.optJSONObject(it)?.optString("status", "open") == "open"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item { PageHeader("生命") { } }
        if (err != null) item { Text(err!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }

        item {
            Panel("状态") {
                val at = boot.optLong("at", 0)
                InfoLine("启动时间", if (at > 0) fmtStamp(at) else "—")
                InfoLine(
                    "模型",
                    mdl.optString("provider").ifBlank { "—" } + " / " + mdl.optString("model").ifBlank { "—" } +
                        (if (mdl.optBoolean("self", false)) "（Ta 自己选的）" else ""),
                )
                InfoLine("思考", mdl.optString("reasoningEffort", "low"))
                InfoLine(
                    "上下文",
                    numStr(ctx.optDouble("ratio", 0.0)) + "%（估算 " + ctx.optLong("tokens", 0) + "/" + ctx.optLong("window", 0) + "）",
                )
                InfoLine("当前作息", rhythm.optString("quietFrom", "02:00") + "-" + rhythm.optString("quietTo", "07:00"))
            }
        }

        item {
            val cells = listOf(
                "多巴胺" to round0(dop),
                "皮质醇" to round0(cor),
                "血清素" to round0(ser),
                "体力" to numStr(energy.optDouble("level", 0.0)) + "/100",
                "执念" to "$openWishes 条",
                "器官" to "${organs?.length() ?: 0} 个",
                "自我" to "$selfCount 条",
                "冲动" to (if (assess?.optBoolean("awake", false) == true) "${assess.optInt("urge", 0)}" else "睡着"),
            )
            Panel("现在") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in cells.chunked(4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in row) {
                                Column(
                                    Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(c.second, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(c.first, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        item {
            Panel("内分泌变化记录") {
                if (hist.length() == 0) EmptyText("还没有记录。")
                else for (i in 0 until minOf(hist.length(), 20)) {
                    val h = hist.optJSONObject(i) ?: continue
                    val parts = ArrayList<String>()
                    val d = h.optDouble("dopamine", 0.0)
                    val c = h.optDouble("cortisol", 0.0)
                    val s = h.optDouble("serotonin", 0.0)
                    if (d != 0.0) parts.add("多巴胺 " + (if (d > 0) "↑" + round0(d) else "↓" + round0(-d)))
                    if (c != 0.0) parts.add("皮质醇 " + (if (c > 0) "↑" + round0(c) else "↓" + round0(-c)))
                    if (s != 0.0) parts.add("血清素 " + (if (s > 0) "↑" + round0(s) else "↓" + round0(-s)))
                    val reason = h.optString("reason")
                    val head = if (parts.isEmpty()) "— " + reason.ifBlank { "（没写原因）" }
                    else parts.joinToString(" · ") + if (reason.isNotBlank()) " — " + reason else ""
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(head, fontSize = 12.sp)
                        Text(fmtStamp(h.optLong("at", 0)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Panel("身体") {
                InfoLine("体力", numStr(energy.optDouble("level", 0.0)) + " / 100")
                InfoLine("作息", rhythm.optString("quietFrom", "02:00") + "-" + rhythm.optString("quietTo", "07:00") + " 睡觉")
                val at = assess?.optLong("at", 0) ?: 0
                val urge = assess?.optInt("urge", 0) ?: 0
                val topic = assess?.optString("topic").orEmpty()
                InfoLine(
                    "冲动",
                    if (assess != null) "${if (assess.optBoolean("awake")) "$urge 分" else "睡着"} · 最近评估 ${fmtStamp(at)}" + (if (topic.isNotBlank()) " · 想聊：$topic" else "")
                    else "还没评估过（Ta 想找你时会自己来）",
                )
            }
        }

        item {
            Panel("执念（Ta 惦记的事）") {
                if (wishes.length() == 0) EmptyText("还没有执念——执念由 Ta 自己长、自己了结，这里只是看看。")
                else for (i in 0 until minOf(wishes.length(), 8)) {
                    val w = wishes.optJSONObject(i) ?: continue
                    val open = w.optString("status", "open") == "open"
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(w.optString("text"), fontSize = 13.sp, color = if (open) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (open) "惦记中 · 上次拿来当由头 ${fmtStamp(w.optLong("lastUsedAt", 0))}"
                            else "已了结 · ${fmtStamp(w.optLong("lastUsedAt", 0))}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Panel("闹钟") {
                if (alarms.length() == 0) EmptyText("还没设闹钟——Ta 自己会设，到点自己醒。")
                else for (i in 0 until minOf(alarms.length(), 8)) {
                    val a = alarms.optJSONObject(i) ?: continue
                    val fired = a.optBoolean("fired", false)
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            a.optString("reason").ifBlank { "（没写原因）" } + (if (fired) "（已响过）" else ""),
                            fontSize = 13.sp,
                            color = if (fired) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        )
                        Text(fmtStamp(a.optLong("at", 0)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Panel("人格") {
                InfoLine("Ta 认为 Ta 是", idn.optString("name").ifBlank { cfg?.optString("botName").orEmpty() }.ifBlank { "AuraLife" })
                InfoLine("Ta 认为你是", idn.optString("ownerName").ifBlank { cfg?.optString("ownerName").orEmpty() }.ifBlank { "主人" })
                val upd = idn.optLong("updatedAt", 0)
                InfoLine("版本", if (upd > 0) "Ta 自己改的 · " + fmtStamp(upd) else "Ta 还没改过人格（出厂种子在用）")
                Spacer(Modifier.height(6.dp))
                Text(
                    if (idn.optString("body").isNotBlank()) "底色（Ta 自己定的）" else "底色（出厂种子）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    idn.optString("body").ifBlank { idn.optString("seedBody") }.ifBlank { "出厂没写底色" },
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("自我认识：$selfCount 条（第 ${self.optInt("version", 0)} 版）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (self.optLong("updatedAt", 0) > 0) {
                    Hint("上次长出新认识：" + fmtStamp(self.optLong("updatedAt", 0)))
                }
                listOf("性格" to "traits", "看重" to "values", "底线" to "boundaries").forEach { (label, field) ->
                    val arr = self.optJSONArray(field) ?: JSONArray()
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (arr.length() == 0) {
                        EmptyText("（还没有）")
                    } else {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            InfoLine(e.optString("label").ifBlank { "（没标签）" }, pct(e.optDouble("conf", 0.0)))
                        }
                    }
                }
            }
        }

        item {
            Panel("器官") {
                val list = organs ?: JSONArray()
                if (list.length() == 0) EmptyText("还没有器官——Ta 一出厂就自带一批，也可以自己长。")
                else for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val tripped = o.optString("status") == "tripped"
                    val source = if (o.optString("source") == "self-made") "Ta 自己写的" else "内置"
                    Row(
                        Modifier.fillMaxWidth().clickable { organDetail = o }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                o.optString("name").ifBlank { o.optString("id") },
                                fontSize = 13.sp,
                                color = if (tripped) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                            )
                            Text(
                                o.optString("id") + " · " + source + " · " + (if (tripped) "停用" else "运行中"),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    organDetail?.let { o -> OrganDetailDialog(o, onClose = { organDetail = null }) }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 230.dp),
        )
    }
}

@Composable
private fun OrganDetailDialog(o: JSONObject, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.optString("name").ifBlank { o.optString("id") }, style = MaterialTheme.typography.titleMedium)
                        Text(o.optString("id"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp),
                ) {
                    item {
                        Panel("状态") {
                            val tripped = o.optString("status") == "tripped"
                            InfoLine("状态", if (tripped) "停用（连续出错被自动熔断）" else "运行中")
                            InfoLine("来源", if (o.optString("source") == "self-made") "Ta 自己写的" else "内置")
                        }
                    }
                    item {
                        Panel("描述") {
                            Text(o.optString("description").ifBlank { "（没有描述）" }, fontSize = 13.sp)
                        }
                    }
                    item {
                        Panel("能力") {
                            val caps = o.optJSONArray("capabilities") ?: JSONArray()
                            if (caps.length() == 0) EmptyText("（没有声明能力）")
                            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until caps.length()) {
                                    val c = caps.optJSONObject(i) ?: continue
                                    Text(
                                        c.optString("name").ifBlank { c.optString("id") },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Panel("工具") {
                            val tools = o.optJSONArray("toolDefs") ?: JSONArray()
                            val caps = o.optJSONArray("capabilities") ?: JSONArray()
                            when {
                                tools.length() > 0 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (i in 0 until tools.length()) {
                                        val t = tools.optJSONObject(i) ?: continue
                                        Column {
                                            Text(t.optString("name"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            val params = t.optJSONObject("params") ?: t.optJSONObject("parameters")
                                            if (params != null) {
                                                Text(
                                                    params.toString(2),
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            } else if (t.optString("description").isNotBlank()) {
                                                Text(t.optString("description"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                caps.length() > 0 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (i in 0 until caps.length()) {
                                        val c = caps.optJSONObject(i) ?: continue
                                        Column {
                                            Text(c.optString("name").ifBlank { c.optString("id") }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            if (c.optString("description").isNotBlank()) {
                                                Text(c.optString("description"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                else -> EmptyText("这个器官没有注册工具。")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun round0(v: Double): String = v.toInt().toString()

private fun numStr(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.CHINA, "%.1f", v)
