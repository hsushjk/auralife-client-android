package dev.auralife.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi

/**
 * 首次初始化向导（服务端 needSetup=true 时进入），对齐 web 六步向导的主干：
 * 先答应两件事（pledge）→ 起名字/称呼 → 设主人账号 → POST /api/setup → 再 /api/login 换 token。
 * 服务端 setup 不返回 token，所以这里必须再登录一次。
 */
@Composable
fun SetupScreen(
    prefs: Prefs,
    api: AuraApi,
    onDone: (AuraApi) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var pledge1 by remember { mutableStateOf(false) }
    var pledge2 by remember { mutableStateOf(false) }
    var botName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var alreadyOwner by remember { mutableStateOf(false) }
    val ui = android.os.Handler(android.os.Looper.getMainLooper())

    fun submit() {
        if (busy) return
        val u = user.trim()
        when {
            u.isBlank() -> {
                msg = "用户名不能空着"
                return
            }
            pass.length < 6 -> {
                msg = "密码至少 6 位"
                return
            }
            pass != pass2 -> {
                msg = "两次输入的密码不一样"
                return
            }
        }
        busy = true
        msg = ""
        Thread {
            var error: String? = null
            val r = runCatching { api.setup(u, pass, true, botName.trim(), ownerName.trim()) }
            r.exceptionOrNull()?.let { error = "连不上服务：${it.message}" }
            val j = r.getOrNull()
            if (error == null && j?.optBoolean("ok") != true) {
                val reason = j?.optString("reason").orEmpty().ifBlank { j?.optString("message").orEmpty() }
                error = reason.ifBlank { "没设成，再试一次" }
                if (reason.contains("已经有主人")) alreadyOwner = true
            }
            if (error == null) {
                // setup 成功后必须再登录换正式 token
                val lr = runCatching { api.login(u, pass) }
                val lj = lr.getOrNull()
                if (lr.exceptionOrNull() != null) {
                    error = "连不上服务：${lr.exceptionOrNull()?.message}"
                } else if (lj?.optBoolean("ok") == true) {
                    api.token = lj.optString("token")
                    prefs.base = api.base
                    prefs.token = api.token
                    prefs.user = lj.optString("subject")
                    prefs.name = lj.optString("name", lj.optString("subject"))
                    prefs.role = lj.optString("role")
                } else {
                    error = lj?.optString("reason").orEmpty().ifBlank {
                        lj?.optString("message").orEmpty().ifBlank { "设好了，但登录没成功" }
                    }
                }
            }
            ui.post {
                busy = false
                if (error == null) onDone(api) else msg = error!!
            }
        }.apply { isDaemon = true }.start()
    }

    val titles = listOf("开始之前，先答应两件事", "给 Ta 起个名字", "设个主人账号")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(titles[step.coerceIn(0, titles.size - 1)], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> {
                Text("这两件事是底线，答应了才能继续。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                CheckLine(pledge1, "我承诺：不用 Ta 开播、做内容来圈钱牟利") { pledge1 = it }
                CheckLine(pledge2, "我承诺：不放 Ta 去污染社区（到处评论、刷屏、骚扰别人）") { pledge2 = it }
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = pledge1 && pledge2,
                    onClick = { step = 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我答应，继续") }
            }
            1 -> {
                Text("先给 Ta 一个名字，再告诉 Ta 该怎么称呼你。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = botName,
                    onValueChange = { botName = it },
                    label = { Text("Ta 的名字") },
                    placeholder = { Text("不填就叫 AuraLife") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ownerName,
                    onValueChange = { ownerName = it },
                    label = { Text("Ta 该怎么称呼你") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { step = 0 }) { Text("上一步") }
                    Button(onClick = { step = 2 }, modifier = Modifier.weight(1f)) { Text("下一步") }
                }
            }
            else -> {
                Text("最后给主人设个账号（用户名 + 至少 6 位密码）。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pass2,
                    onValueChange = { pass2 = it },
                    label = { Text("再输一遍密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (alreadyOwner) {
                                TextButton(onClick = { onCancel() }) { Text("去登录") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(enabled = !busy, onClick = { step = 1 }) { Text("上一步") }
                    Button(
                        enabled = !busy,
                        onClick = { submit() },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "完成中…" else "完成") }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = { if (!busy) onCancel() }) { Text("换服务器 / 退出") }
    }
}

@Composable
private fun CheckLine(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
    }
}