package org.bakasu.bakasu.ui.screen.dialscript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.dial.DialScriptConfig
import org.bakasu.bakasu.data.dial.DialScriptRunner
import org.bakasu.bakasu.data.dial.DialScriptStore
import org.bakasu.bakasu.data.dial.readDialLog
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.adaptiveScaffoldWindowInsets
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.koin.compose.koinInject

/**
 * 拨号脚本配置页。
 *
 * 配好脚本路径和密令后，在拨号盘拨 `*#*#密令#*#*` 就会以 root 静默执行脚本，
 * 预设答案自动喂进去 —— 全程没有界面、没有通知。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialScriptScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val ksuCli = koinInject<KsuCliRepository>()
    val scope = rememberCoroutineScope()

    val store = remember { DialScriptStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    // 密令输入框保留用户原始输入，否则逗号会被规范化吃掉，没法接着输第二个
    var codesRaw by remember { mutableStateOf(config.codes.joinToString(",")) }

    var showLog by remember { mutableStateOf(false) }
    var logReload by remember { mutableStateOf(0) }
    var logText by remember { mutableStateOf("") }
    var loadingLog by remember { mutableStateOf(false) }

    fun update(next: DialScriptConfig) {
        config = next
        store.write(next)
    }

    LaunchedEffect(showLog, logReload) {
        if (!showLog) return@LaunchedEffect
        loadingLog = true
        logText = withContext(Dispatchers.IO) { readDialLog(ksuCli) }
        loadingLog = false
    }

    Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            TopAppBar(
                title = { Text(text = "拨号脚本", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { AppBackButton(onClick = { navigator.pop() }) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "在拨号盘拨 *#*#密令#*#*，即以 root 身份静默执行脚本。全程不弹窗，" +
                    "预设答案按顺序自动输入，结果写进日志。脚本可以是磁盘上的文件，" +
                    "也可以直接写在下面的输入框里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "启用拨号触发", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (config.enabled) "已启用" else "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { update(config.copy(enabled = it)) },
                    )
                }
            }

            SectionCard {
                Field(
                    label = "密令（多个用逗号分隔）",
                    value = codesRaw,
                    onValueChange = {
                        codesRaw = it
                        update(config.copy(codes = DialScriptStore.normalizeCodes(it)))
                    },
                    placeholder = DialScriptConfig.DEFAULT_CODE,
                    numberOnly = true,
                )
                if (config.codes.isNotEmpty()) {
                    Text(
                        text = "当前可用：" + config.codes.joinToString("  /  ") { "*#*#$it#*#*" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }

            SectionCard {
                Field(
                    label = "脚本路径（可留空）",
                    value = config.scriptPath,
                    onValueChange = { update(config.copy(scriptPath = it)) },
                    placeholder = "/sdcard/Download/run.sh",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = "要执行的脚本",
                    value = config.scriptBody,
                    onValueChange = { update(config.copy(scriptBody = it)) },
                    placeholder = "#!/system/bin/sh\n" +
                        "input tap 540 1800\n" +
                        "sleep 1\n" +
                        "input text 123456",
                    multiline = true,
                    multilineHeight = 168.dp,
                )
                Text(
                    text = "两处填一处就行：只写这里就不需要外部脚本文件；" +
                        "路径和内容都填时以「要执行的脚本」为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            SectionCard {
                Field(
                    label = "自动输入（一行一个答案）",
                    value = config.autoInput,
                    onValueChange = { update(config.copy(autoInput = it)) },
                    placeholder = "3",
                    multiline = true,
                )
                Text(
                    text = "按顺序喂给脚本的 stdin：一行一个答案，不需要输入就留空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val error = DialScriptRunner.run(context, config, trigger = "手动")
                        scope.launch {
                            snackbarHost.showReplacingSnackbar(error ?: "已开始执行，跑完看日志")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("立即执行一次")
                }
                OutlinedButton(
                    onClick = {
                        showLog = true
                        logReload++
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("查看日志")
                }
            }

            Text(
                text = "日志：${DialScriptRunner.LOG_PATH}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            Text(
                text = "第一次用请先点一次「立即执行一次」，在弹出的授权框里允许 root。" +
                    "授权过之后拨号才能在后台静默执行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("执行日志") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = when {
                            loadingLog -> "读取中…"
                            logText.isBlank() -> "还没有日志。脚本执行过一次之后这里才会有内容。"
                            else -> logText
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { logReload++ }) { Text("刷新") }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    numberOnly: Boolean = false,
    multiline: Boolean = false,
    multilineHeight: Dp = 112.dp,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = !multiline,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (multiline) Modifier.height(multilineHeight) else Modifier),
        textStyle = if (multiline) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        },
        keyboardOptions = if (numberOnly) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
    )
}
