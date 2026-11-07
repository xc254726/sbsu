package org.bakasu.bakasu.ui.screen.autoTrigger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.trigger.TriggerEngine
import org.bakasu.bakasu.ui.navigation.LocalNavigator

@Composable
fun AutoTriggerScreen() {
    val ctx = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf(TriggerEngine.load(ctx)) }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        running = withContext(Dispatchers.IO) { TriggerEngine.isRunning() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigator.pop() }) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Text("自动触发", style = MaterialTheme.typography.titleLarge)
        }

        // 状态
        Section("状态") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (running) "运行中" else "未启用",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "只在目标应用前台时触发",
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

        // 触发条件
        Section("触发条件") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("目标应用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (cfg.appPkg.isBlank()) {
                            "未选择"
                        } else {
                            "${cfg.appLabel.ifBlank { cfg.appPkg }}\n${cfg.appPkg}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { pickerOpen = true }) { Text("更换") }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连点次数（音量上键）", modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = cfg.pressCount.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.take(1).toIntOrNull() ?: 0
                        cfg = cfg.copy(pressCount = n.coerceIn(1, 9))
                    },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连点间隔（秒）", modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = cfg.intervalSec.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.take(1).toIntOrNull() ?: 0
                        cfg = cfg.copy(intervalSec = n.coerceIn(0, 9))
                    },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        // 执行动作
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
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            enabled = !busy,
            onClick = {
                if (cfg.appPkg.isBlank()) {
                    message = "请先选择目标应用"
                } else {
                    busy = true
                    message = null
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            TriggerEngine.save(ctx, cfg)
                            TriggerEngine.apply(ctx, cfg)
                        }
                        running = withContext(Dispatchers.IO) { TriggerEngine.isRunning() }
                        busy = false
                        message = err ?: if (cfg.enabled) "已保存，守护运行中" else "已保存，守护已停止"
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
                        TriggerEngine.save(ctx, cfg)
                        TriggerEngine.apply(ctx, cfg.copy(enabled = false))
                        TriggerEngine.testNow(cfg)
                    }
                    busy = false
                    message = "测试输出：$r"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("立即测试一次")
        }

        TextButton(
            onClick = {
                scope.launch {
                    logText = withContext(Dispatchers.IO) { TriggerEngine.readLog() }
                    logOpen = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("查看运行日志")
        }

        Text(
            text = "说明：音量键连点会顺带改变系统音量，触发后会自动按回去；" +
                "脚本和输入内容都以 root 身份执行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }

    if (pickerOpen) {
        val apps = remember(pickerOpen) { loadLaunchableApps(ctx) }
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择目标应用") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(apps) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    cfg = cfg.copy(appPkg = item.first, appLabel = item.second)
                                    pickerOpen = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(item.second, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                item.first,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("取消") }
            },
        )
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
                    Text(
                        text = logText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
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

private fun loadLaunchableApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val list = pm.queryIntentActivities(intent, 0)
    return list
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
