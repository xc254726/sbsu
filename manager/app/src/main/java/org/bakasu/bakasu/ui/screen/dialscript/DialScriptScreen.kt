package org.bakasu.bakasu.ui.screen.dialscript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.dial.DialKeepAlive
import org.bakasu.bakasu.data.dial.DialScriptConfig
import org.bakasu.bakasu.data.dial.ScriptPick
import org.bakasu.bakasu.data.dial.DialScriptRunner
import org.bakasu.bakasu.data.dial.DialScriptStore
import org.bakasu.bakasu.data.dial.readDialLog
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.data.file.RootFileRepository
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.navigation.Route
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

    // 后台守护的实际状态：配置里开着但它可能没跑起来（没给 root、脚本被杀）
    var keepAliveAlive by remember { mutableStateOf(false) }
    var keepAliveBusy by remember { mutableStateOf(false) }

    var showLog by remember { mutableStateOf(false) }
    var logReload by remember { mutableStateOf(0) }
    var logText by remember { mutableStateOf("") }
    var loadingLog by remember { mutableStateOf(false) }

    fun update(next: DialScriptConfig) {
        config = next
        store.write(next)
    }

    val fileRepo = remember(ksuCli) { RootFileRepository(ksuCli) }

    // 从隐藏终端选完回来：把文件内容读进「要执行的脚本」，之后拨号就不再依赖那个文件还在不在
    val applyPicked = rememberUpdatedState<(String) -> Unit> { path ->
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching { fileRepo.readText(path, MAX_BODY_BYTES) }.getOrNull()
            }
            val name = path.substringAfterLast('/')
            when {
                preview == null -> snackbarHost.showReplacingSnackbar("读取失败：$path")
                preview.binary -> snackbarHost.showReplacingSnackbar("$name 是二进制文件，当不了脚本")
                preview.text.isBlank() -> snackbarHost.showReplacingSnackbar("$name 是空的")
                else -> {
                    update(config.copy(scriptBody = preview.text, scriptPath = ""))
                    val cut = if (preview.truncated) "（只取前 ${MAX_BODY_BYTES / 1024} KB）" else ""
                    snackbarHost.showReplacingSnackbar("已载入 $name$cut")
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ScriptPick.take()?.let { picked -> applyPicked.value(picked) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进页面就校验一次：开着就确保守护在跑，关着就确保它没在跑
    LaunchedEffect(config.keepAlive) {
        keepAliveBusy = true
        val alive = withContext(Dispatchers.IO) {
            runCatching {
                if (config.keepAlive) {
                    DialKeepAlive.start(ksuCli)
                    DialKeepAlive.isRunning(ksuCli)
                } else {
                    DialKeepAlive.stop(ksuCli)
                    false
                }
            }.getOrDefault(false)
        }
        keepAliveAlive = alive
        keepAliveBusy = false
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "后台守护", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when {
                                !config.keepAlive -> "已关闭 · 划掉后台就收不到拨号了"
                                keepAliveBusy -> "正在启动…"
                                keepAliveAlive -> "运行中 · 划掉后台仍可拨号"
                                else -> "未跑起来 · 请先授予 root"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.keepAlive,
                        onCheckedChange = { update(config.copy(keepAlive = it)) },
                    )
                }
                Text(
                    text = "从最近任务里划掉 App，系统会给包打上「已停止」标记，" +
                        "而静止的包收不到任何静态广播 —— 这就是「拨一次能用、之后再拨没反应」的原因。" +
                        "开着这个，root 侧每 15 秒清一次标记。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
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
                TextButton(onClick = { navigator.push(Route.ScriptPicker) }) {
                    Icon(
                        imageVector = Icons.TwoTone.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("从隐藏终端选择脚本", style = MaterialTheme.typography.labelLarge)
                }
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
                        // libsu 不允许在主线程起命令，这里扔到 IO
                        scope.launch {
                            val error = withContext(Dispatchers.IO) {
                                DialScriptRunner.run(context, ksuCli, config, trigger = "手动")
                            }
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

/** 选中文件后最多搬这么多字节进来，避免把几十 MB 的日志塞进输入框 */
private const val MAX_BODY_BYTES = 128 * 1024

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
