package dev.auralife.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.SettingsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayClock = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun fmtT(t: Long): String = if (t > 0) dayClock.format(Date(t)) else "—"

private fun srcLabel(src: String): String = when (src) {
    "told" -> "Ta 听说的"
    "inferred" -> "Ta 琢磨的"
    "self_did" -> "Ta 自己做的"
    "imagined" -> "Ta 想象的"
    "manual" -> "手动留的"
    else -> src.ifBlank { "?" }
}

@Composable
fun MemoryScreen(api: AuraApi) {
    var mem by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        err = null
        val r = runCatching { withContext(Dispatchers.IO) { SettingsApi.memory(api) } }
        if (r.isSuccess) mem = r.getOrNull() else err = r.exceptionOrNull()?.message ?: "拉不到记忆"
    }

    val stats = mem?.optJSONObject("stats")
    val tabs = listOf("事实", "事件", "联想")

    Column(Modifier.fillMaxSize()) {
        PageHeader("记忆") {
            Text(
                "事实 ${stats?.optInt("facts", 0) ?: 0} · 事件 ${stats?.optInt("episodes", 0) ?: 0} · 联想 ${stats?.optInt("edges", 0) ?: 0}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabStrip(tabs, tab) { tab = it }
        if (err != null) {
            Text(err!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp))
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> FactsTab(mem)
                1 -> EpisodesTab(mem)
                else -> EdgesTab(mem)
            }
        }
    }
}

@Composable
private fun FactsTab(mem: JSONObject?) {
    val arr = mem?.optJSONArray("facts")
    if (arr == null || arr.length() == 0) {
        EmptyHint("还没有事实——聊几句、一起做点事，Ta 就会记下来。")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        items((0 until arr.length()).map { arr.optJSONObject(it) }) { f ->
            val dead = f?.optLong("invalidAt", 0) ?: 0 > 0
            val arch = f?.optBoolean("archived", false) == true
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f?.optString("text").orEmpty(),
                        fontSize = 14.sp,
                        textDecoration = if (dead) TextDecoration.LineThrough else null,
                        color = if (dead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(0.dp))
                Text(
                    buildString {
                        append(srcLabel(f?.optString("src").orEmpty()))
                        append(" · 置信 " + (f?.optDouble("conf", 0.0)?.times(100))?.toInt() + "%")
                        if (arch) append(" · 硬档")
                        if (dead) append(" · 已失效")
                        val hits = f?.optInt("hits", 0) ?: 0
                        if (hits > 0) append(" · 想起过 $hits 次")
                        val t0 = f?.optLong("t0", 0) ?: 0
                        if (t0 > 0) append(" · 从 " + fmtT(t0) + " 起")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EpisodesTab(mem: JSONObject?) {
    val arr = mem?.optJSONArray("episodes")
    if (arr == null || arr.length() == 0) {
        EmptyHint("还没有事件——事件是每段聊天的摘要，后台每半小时整理一次。")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        items((0 until arr.length()).map { arr.optJSONObject(it) }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    e?.optString("summary").orEmpty().ifBlank { "（没摘要）" },
                    fontSize = 14.sp,
                )
                Text(
                    fmtT(e?.optLong("t0", 0) ?: 0) + " → " + fmtT(e?.optLong("t1", 0) ?: 0),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EdgesTab(mem: JSONObject?) {
    val arr = mem?.optJSONArray("edges")
    if (arr == null || arr.length() == 0) {
        EmptyHint("还没有联想——每天一次的记忆重放会把相关的事连成网。")
        return
    }
    val label = HashMap<String, String>()
    mem.optJSONArray("facts")?.let { fs ->
        for (i in 0 until fs.length()) {
            val o = fs.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotEmpty()) label[id] = o.optString("text").ifBlank { id }
        }
    }
    mem.optJSONArray("episodes")?.let { eps ->
        for (i in 0 until eps.length()) {
            val o = eps.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotEmpty()) label[id] = "事件：" + o.optString("summary").ifBlank { id }
        }
    }
    fun name(id: String): String = label[id] ?: id.take(8)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        items((0 until arr.length()).map { arr.optJSONObject(it) }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(name(e?.optString("a").orEmpty()) + " ↔ " + name(e?.optString("b").orEmpty()), fontSize = 13.sp)
                Text(
                    buildString {
                        append(
                            when (e?.optString("kind").orEmpty()) {
                                "evidence" -> "凭据"
                                "assoc" -> "关联"
                                else -> e?.optString("kind").orEmpty()
                            },
                        )
                        append(" · 强度 " + (Math.round((e?.optDouble("w", 0.0) ?: 0.0) * 100)) + "%")
                        val why = e?.optString("why").orEmpty()
                        if (why.isNotBlank()) append(" · " + why)
                        append(" · " + fmtT(e?.optLong("at", 0) ?: 0))
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
    }
}
