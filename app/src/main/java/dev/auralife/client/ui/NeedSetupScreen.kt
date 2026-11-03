package dev.auralife.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NeedSetupScreen(base: String, onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ta 还没睡醒", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("服务端还没完成初始化向导（起名字、选人设、设账号）。")
                Spacer(Modifier.height(8.dp))
                Text("用浏览器打开下面的地址，把向导走完再回来：")
                Spacer(Modifier.height(8.dp))
                Text(base, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("好了，再查一次") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLogout) { Text("换服务器 / 退出") }
    }
}
