package dev.auralife.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.auralife.client.data.LocalLogout
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Live
import dev.auralife.client.net.Reconnect
import dev.auralife.client.net.SettingsApi
import dev.auralife.client.ui.AppTheme
import dev.auralife.client.ui.Backdrop
import dev.auralife.client.ui.ChatScreen
import dev.auralife.client.ui.ConnectScreen
import dev.auralife.client.ui.LogsScreen
import dev.auralife.client.ui.MemoryScreen
import dev.auralife.client.ui.MoodScreen
import dev.auralife.client.ui.SettingsScreen
import dev.auralife.client.ui.SetupScreen
import dev.auralife.client.ui.SpaceScreen
import dev.auralife.client.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val resume = prefs.base.isNotBlank() && prefs.token.isNotBlank()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 先标记前台，避免 Service 刚启动时的首次重连被当成后台（10s×3）处理
        Live.appForeground = true
        if (resume) LiveService.start(this)
        setContent {
            var themeKey by remember { mutableStateOf(prefs.theme) }
            AppTheme(ThemeMode.of(themeKey)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    AppRoot(prefs, resume, onThemeChange = { themeKey = it })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Live.visible = true
        Live.appForeground = true
        LiveService.clearMessages(this)
        // 回前台：断线/「连接失败」时立即重连一次，之后恢复 1s→30s 常规退避
        Live.onForeground()
    }

    override fun onPause() {
        Live.visible = false
        Live.appForeground = false
        // 退到后台：重连降级为 10s/次、最多 3 次
        Live.onBackground()
        super.onPause()
    }
}

private data class Tab(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("chat", "聊天", Icons.Outlined.ChatBubbleOutline),
    Tab("mood", "生命", Icons.Outlined.MonitorHeart),
    Tab("memory", "记忆", Icons.Outlined.AutoAwesome),
    Tab("space", "空间", Icons.Outlined.Article),
    Tab("settings", "设置", Icons.Outlined.Settings),
    Tab("logs", "日志", Icons.Outlined.ReceiptLong),
)

private fun ownerOnly(key: String) = key == "mood" || key == "memory" || key == "logs"

@Composable
private fun AppRoot(prefs: Prefs, resume: Boolean, onThemeChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val ui = remember { Handler(Looper.getMainLooper()) }
    var api by remember { mutableStateOf(if (resume) AuraApi.obtain(prefs.base, prefs.token).also { it.rebuild() } else null) }
    var screen by remember { mutableStateOf("chat") }
    var role by remember { mutableStateOf(prefs.role) }
    var subject by remember { mutableStateOf("") }
    var needSetup by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf("") }

    /**
     * AND-2：退出登录必须本地优先。
     * 立即清 token/登录态、停 Live 与前台的连接服务、回 ConnectScreen；
     * POST /api/logout 只做 best-effort（3~5s 超时，失败忽略），不阻塞界面。
     */
    val logout: () -> Unit = {
        val old = api
        LocalLogout.run(
            clearSession = {
                prefs.token = ""
                prefs.clearSession()
                Backdrop.clear()
                api = null
                screen = "chat"
                needSetup = false
            },
            stopLive = { Live.clear() },
            stopService = { LiveService.stop(ctx) },
            remoteBestEffort = {
                if (old != null && old.token.isNotBlank()) {
                    Thread { runCatching { old.logout() } }.apply { isDaemon = true }.start()
                }
            },
        )
    }

    // 登录态失效（真 401 / reason=「没登录」）→ 本地登出，不等网络
    androidx.compose.runtime.DisposableEffect(api) {
        val a = api
        val listener: () -> Unit = { ui.post { logout() } }
        a?.addAuthLostListener(listener)
        onDispose { a?.removeAuthLostListener(listener) }
    }

    LaunchedEffect(api) {
        val a = api ?: return@LaunchedEffect
        val st = withContext(Dispatchers.IO) { runCatching { SettingsApi.stateMe(a) }.getOrNull() }
        if (st?.optBoolean("ok") == true && st.optBoolean("needSetup", false)) {
            needSetup = true
            return@LaunchedEffect
        }
        val me = st?.optJSONObject("me")
        val r = me?.optString("role").orEmpty()
        if (r.isNotBlank()) {
            role = r
            prefs.role = r
        }
        subject = me?.optString("subject").orEmpty()
    }

    // 连接提示（连接恢复了 / rotate 等），在任何页面顶部飘一条，不挡操作
    LaunchedEffect(Live.notice) {
        val n = Live.notice
        if (n.text.isBlank()) return@LaunchedEffect
        banner = n.text
        delay(3_200)
        if (banner == n.text) banner = ""
    }
    LaunchedEffect(Live.rotateSeq) {
        if (Live.rotateSeq <= 0) return@LaunchedEffect
        if (screen == "chat") return@LaunchedEffect
        banner = Reconnect.MSG_ROTATE_OTHER
        delay(4_000)
        if (banner == Reconnect.MSG_ROTATE_OTHER) banner = ""
    }

    if (needSetup && api != null) {
        SetupScreen(
            prefs = prefs,
            api = api!!,
            onDone = { logged ->
                api = AuraApi.obtain(logged.base, logged.token)
                needSetup = false
                Backdrop.load(ctx)
                LiveService.start(ctx)
            },
            onCancel = { logout() },
        )
        return
    }

    if (api == null) {
        ConnectScreen(
            prefs = prefs,
            onConnected = { connected ->
                val shared = AuraApi.obtain(connected.base, connected.token)
                api = shared
                Backdrop.load(ctx)
                LiveService.start(ctx)
            },
            onNeedSetup = { pending ->
                // 服务端还没初始化：/api/state 的 needSetup=true，进入安卓端首次向导
                api = pending
                needSetup = true
            },
        )
        return
    }
    val owner = role == "owner"
    if (!owner && ownerOnly(screen)) screen = "chat"
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(modifier = Modifier.imePadding()) {
                    TABS.forEach { tab ->
                        if (ownerOnly(tab.key) && !owner) return@forEach
                        NavigationBarItem(
                            selected = screen == tab.key,
                            onClick = { screen = tab.key },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (screen) {
                    "chat" -> {
                        LaunchedEffect(Unit) { Backdrop.load(ctx) }
                        ChatScreen(
                            api!!,
                            prefs,
                        )
                    }
                    "space" -> SpaceScreen(api!!, owner = owner, subject = subject)
                    "mood" -> MoodScreen(api!!, owner = owner)
                    "memory" -> MemoryScreen(api!!)
                    "logs" -> LogsScreen(api!!)
                    "settings" -> SettingsScreen(
                        api = api!!,
                        prefs = prefs,
                        onThemeChange = onThemeChange,
                        onLogout = logout,
                    )
                }
            }
        }
        if (banner.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Text(banner, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}