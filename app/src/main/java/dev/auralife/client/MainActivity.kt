package dev.auralife.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import dev.auralife.client.data.Prefs
import dev.auralife.client.net.AuraApi
import dev.auralife.client.net.Live
import dev.auralife.client.net.SettingsApi
import dev.auralife.client.ui.AppTheme
import dev.auralife.client.ui.Backdrop
import dev.auralife.client.ui.ChatScreen
import dev.auralife.client.ui.ConnectScreen
import dev.auralife.client.ui.LogsScreen
import dev.auralife.client.ui.MemoryScreen
import dev.auralife.client.ui.MoodScreen
import dev.auralife.client.ui.NeedSetupScreen
import dev.auralife.client.ui.SettingsScreen
import dev.auralife.client.ui.SpaceScreen
import dev.auralife.client.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
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
        LiveService.clearMessages(this)
    }

    override fun onPause() {
        Live.visible = false
        super.onPause()
    }
}

private data class Tab(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("chat", "聊天", Icons.Outlined.ChatBubbleOutline),
    Tab("space", "空间", Icons.Outlined.Article),
    Tab("mood", "生命", Icons.Outlined.MonitorHeart),
    Tab("memory", "记忆", Icons.Outlined.AutoAwesome),
    Tab("settings", "设置", Icons.Outlined.Settings),
    Tab("logs", "日志", Icons.Outlined.ReceiptLong),
)

private fun ownerOnly(key: String) = key == "mood" || key == "memory" || key == "logs"

@Composable
private fun AppRoot(prefs: Prefs, resume: Boolean, onThemeChange: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var api by remember { mutableStateOf(if (resume) AuraApi(prefs.base, prefs.token).also { it.rebuild() } else null) }
    var screen by remember { mutableStateOf("chat") }
    var role by remember { mutableStateOf(prefs.role) }
    var subject by remember { mutableStateOf("") }
    var needSetup by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        api?.onAuthFail = {
            prefs.token = ""
            Live.clear()
            Backdrop.clear()
            ctx.stopService(android.content.Intent(ctx, LiveService::class.java))
            screen = "chat"
            api = null
        }
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

    if (needSetup) {
        NeedSetupScreen(prefs.base, onRetry = {
            needSetup = false
            val a = api ?: return@NeedSetupScreen
            val st = runCatching { SettingsApi.stateMe(a) }.getOrNull()
            needSetup = st?.optBoolean("needSetup", false) ?: false
        }, onLogout = {
            prefs.token = ""
            Live.clear()
            ctx.stopService(android.content.Intent(ctx, LiveService::class.java))
            api = null
            needSetup = false
        })
        return
    }

    if (api == null) {
        ConnectScreen(prefs, onConnected = { connected ->
            api = connected
            Backdrop.load(ctx)
            LiveService.start(ctx)
        })
        return
    }
    val owner = role == "owner"
    if (!owner && ownerOnly(screen)) screen = "chat"
    val logout = {
        Live.clear()
        Backdrop.clear()
        ctx.stopService(android.content.Intent(ctx, LiveService::class.java))
        screen = "chat"
        api = null
    }
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
                        onLogout = logout,
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
}
