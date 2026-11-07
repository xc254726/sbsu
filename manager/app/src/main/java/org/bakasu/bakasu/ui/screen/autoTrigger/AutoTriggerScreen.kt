package org.bakasu.bakasu.ui.screen.autoTrigger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.trigger.TriggerEngine
import org.bakasu.bakasu.data.trigger.TriggerService
import org.bakasu.bakasu.ui.navigation.LocalNavigator

@Composable
fun AutoTriggerScreen() {
    val ctx = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf(TriggerEngine.load(ctx)) }
    var running by remember { mutableStateOf(TriggerService.running) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigator.pop() }) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Text("自动触发", style = MaterialTheme.typography.titleLarge)
        }

        Section("状态") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (running) "监听中" else "未启用",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "后台服务自动检测前台应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = { cfg = cfg.copy(enabled = it) },
                )
            }
        }

        Section("触发条件") {
            OutlinedTextField(
                value = cfg.appPkg,
                onValueChange = { cfg = cfg.copy(appPkg = it.trim()) },
                label = { Text("目标应用包名（留空=任意应用）") },
                placeholder = { Text("com.tencent.tmgp.codev") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("进入应用后等待（秒）", modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = cfg.waitSeconds.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.take(2).toIntOrNull() ?: 1
                        cfg = cfg.copy(waitSeconds = n.coerceIn(1, 30))
                    },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        Section("执行动作") {
            OutlinedTextField(
                value = cfg.scriptPath,
                onValueChange = { cfg = cfg.copy(scriptPath = it) },
                label = { Text("脚本路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = cfg.inputText,
                onValueChange = { cfg = cfg.copy(inputText = it) },
                label = { Text("自动输入（英文/数字，每行回车）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            Text(
                text = "脚本执行后，把这里的内容逐行敲进当前输入框",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        message?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        TriggerEngine.save(ctx, cfg)
                        if (cfg.enabled) {
                            TriggerService.start(ctx)
                        } else {
                            TriggerService.stop(ctx)
                        }
                    }
                    kotlinx.coroutines.delay(1200)
                    running = TriggerService.running
                    busy = false
                    message = if (cfg.enabled) {
                        if (running) "已启用，后台监听中" else "启动失败，请看日志"
                    } else {
                        "已停止"
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (busy) "处理中…" else "保存并启用")
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        val sb = StringBuilder()
                        if (cfg.scriptPath.isNotBlank()) {
                            val out = Shell.cmd("sh '${cfg.scriptPath}' < /dev/null 2>&1").exec()
                            sb.append(out.out.joinToString("\n"))
                        }
                        cfg.inputText.lines().forEach { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty()) {
                                Shell.cmd("input text '${line.replace(" ", "%s")}'").exec()
                                Shell.cmd("input keyevent 66").exec()
                            }
                        }
                        sb.toString().ifBlank { "已执行（无输出）" }
                    }
                    busy = false
                    message = "测试结果：$r"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("立即测试一次")
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    scope.launch {
                        logText = withContext(Dispatchers.IO) { TriggerEngine.readLog(ctx) }
                        logOpen = true
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("查看运行日志")
            }
            TextButton(
                onClick = {
                    TriggerEngine.clearLog(ctx)
                    message = "日志已清空"
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("清空日志")
            }
        }

        Text(
            text = "说明：进入指定应用后开始计时，满 N 秒自动执行一次；离开应用后重新计时。脚本和输入以 root 执行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }

    if (logOpen) {
        AlertDialog(
            onDismissRequest = { logOpen = false },
            title = { Text("运行日志") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = logText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { logOpen = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}
