package org.bakasu.bakasu.ui.screen.hide

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.koin.compose.koinInject

private const val PKG = "com.rue.manager"
private const val ROOT_DIR = "/data/adb/rue"
private const val CONF = "$ROOT_DIR/watch.conf"
private const val WATCH = "$ROOT_DIR/watch.sh"
private const val SUP = "$ROOT_DIR/supervisor.sh"
private const val BOOT = "/data/adb/service.d/rue-watch.sh"
private const val APK = "$ROOT_DIR/rue.apk"

private fun runRootShell(ksuCli: KsuCliRepository, script: String): String {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    runCatching {
        ksuCli.getRootShell().newJob().add(script).to(out, err).exec()
    }
    return (out + err).joinToString("\n")
}

/** 目录路径归一化：去换行/制表、单引号、反斜杠，合并重复斜杠，去掉结尾斜杠。 */
private fun normalizeDir(raw: String): String {
    var p = raw.replace(Regex("[\\r\\n\\t]"), "")
        .filter { it != '\'' && it != '\\' }
    p = p.replace(Regex("/{2,}"), "/")
    if (p.length > 1) p = p.trimEnd('/')
    return p.take(128)
}

/** 文件夹名：不允许斜杠和空白，避免拼出意料之外的路径。 */
private fun normalizeName(raw: String): String =
    raw.replace(Regex("[\\r\\n\\t]"), "")
        .filter { it != '\'' && it != '\\' && it != '/' }
        .take(64)

/** 最终监听路径 = 目录 + 名称。 */
private fun triggerPath(dir: String, name: String): String {
    val d = normalizeDir(dir)
    val n = normalizeName(name).trim()
    if (d.isEmpty() || n.isEmpty()) return ""
    return if (d == "/") "/$n" else "$d/$n"
}

private fun parentOf(path: String): String {
    val t = path.trimEnd('/')
    if (t.isEmpty()) return "/"
    val idx = t.lastIndexOf('/')
    return if (idx <= 0) "/" else t.substring(0, idx)
}

private fun listDirs(ksuCli: KsuCliRepository, path: String): List<String> =
    runRootShell(ksuCli, "ls -1p '$path' 2>/dev/null")
        .lineSequence()
        .map { it.trim() }
        .filter { it.endsWith("/") && it.length > 1 }
        .map { it.dropLast(1) }
        .filter { it.isNotEmpty() && !it.contains("/") }
        .sorted()
        .toList()

private fun assetBase64(context: Context, name: String): String =
    Base64.encodeToString(
        context.assets.open("hide/$name").use { it.readBytes() },
        Base64.NO_WRAP,
    )

@Composable
fun HideScreen() {
    val context = LocalContext.current
    val ksuCli: KsuCliRepository = koinInject()
    val scope = rememberCoroutineScope()

    var dir by remember { mutableStateOf("/sdcard") }
    var name by remember { mutableStateOf("rue") }
    var enabled by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var pid by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var browsing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                val conf = runRootShell(ksuCli, "cat $CONF 2>/dev/null").trim()
                val state = runRootShell(ksuCli, "dumpsys package $PKG | grep -m1 'hidden='")
                val running = runRootShell(
                    ksuCli,
                    "pgrep -f /data/adb/rue/watch.sh | head -1",
                ).trim()
                val present = runRootShell(
                    ksuCli,
                    "pm list packages | grep -c 'package:$PKG'",
                ).trim()
                Triple(conf, state.contains("hidden=true"), running to present)
            }
            val conf = res.first
            if (conf.isNotBlank()) {
                // 配置里存的是完整路径，拆回目录 + 名称
                val path = if (conf.startsWith("/")) conf else "/sdcard/$conf"
                val idx = path.lastIndexOf('/')
                dir = if (idx <= 0) "/" else path.substring(0, idx)
                name = path.substring(idx + 1).ifBlank { "rue" }
            }
            enabled = conf.isNotBlank()
            hidden = res.second
            pid = res.third.first
            installed = res.third.second != "0"
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun probe() {
        val target = triggerPath(dir, name)
        if (target.isEmpty()) {
            message = "目录和文件夹名都要填"
            return
        }
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runRootShell(ksuCli, "ls -ld '$target' 2>&1")
            }.trim()
            message = when {
                out.isEmpty() -> "还没触发：$target 不存在（建出来就会恢复）"
                out.contains("No such file") || out.contains("not found") ->
                    "还没触发：$target 不存在（建出来就会恢复）"
                else -> "已存在：$out".take(240)
            }
        }
    }

    fun enable() {
        val target = triggerPath(dir, name)
        if (target.isEmpty()) {
            message = "目录和文件夹名都要填"
            return
        }
        scope.launch {
            busy = true
            message = ""
            val result = withContext(Dispatchers.IO) {
                runRootShell(ksuCli, buildEnableScript(context, target))
            }
            message = when {
                result.contains("ENABLED_HIDE_ONLY") -> "已隐藏（备份失败，仅 pm hide）"
                result.contains("ENABLED") -> "已开始隐藏，等待 $target 出现"
                else -> result.take(300)
            }
            busy = false
            refresh()
        }
    }

    fun disable() {
        scope.launch {
            busy = true
            message = ""
            withContext(Dispatchers.IO) { runRootShell(ksuCli, buildDisableScript()) }
            message = "已关闭，管理器重新可见"
            busy = false
            refresh()
        }
    }

    if (confirming) {
        val target = triggerPath(dir, name)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("确认隐藏 rue") },
            text = {
                Text(
                    "隐藏后 rue 会立刻从桌面、应用管理、电池统计里消失，" +
                        "只能靠创建下面这个路径恢复：\n\n$target\n\n" +
                        "确认要开始吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    enable()
                }) { Text("开始隐藏") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            },
        )
    }

    if (browsing) {
        DirPickerDialog(
            startPath = normalizeDir(dir).ifBlank { "/sdcard" },
            onDismiss = { browsing = false },
            onPick = {
                dir = it
                browsing = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.TwoTone.VisibilityOff, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("隐藏 rue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "先选一个目录，再给它下面要触发的文件夹起个名，然后打开开关。" +
                        "管理器会从桌面、应用管理、电池统计里消失；只要「目录/名字」出现，" +
                        "就自动解除隐藏、静默装回并打开 rue。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "注意：开启后 APK 会从系统里移除（已备份到 /data/adb/rue/rue.apk），" +
                        "管理器立刻打不开，只能靠那个文件夹恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = dir,
            onValueChange = { dir = normalizeDir(it) },
            label = { Text("监听目录（可直接输入）") },
            placeholder = { Text("/storage/emulated/0/MT2") },
            supportingText = { Text("直接输入完整路径；也可以点右侧「选择」逐层点") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                TextButton(onClick = { browsing = true }, enabled = !busy) {
                    Text("选择")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = normalizeName(it) },
            label = { Text("文件夹名") },
            placeholder = { Text("rue") },
            supportingText = { Text("在这个目录下建同名文件夹就会触发恢复") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "触发路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    triggerPath(dir, name).ifBlank { "（未填写）" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { probe() }, enabled = !busy) {
                Text("检测路径")
            }
        }

        Spacer(Modifier.height(4.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (enabled) "隐藏已开启" else "开始隐藏",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            when {
                                busy -> "处理中…"
                                enabled && !installed -> "已移除 · 守护 ${if (pid.isBlank()) "未运行" else "PID $pid"}"
                                enabled && hidden -> "已隐藏（可见性受限）· 守护 ${if (pid.isBlank()) "未运行" else "PID $pid"}"
                                enabled -> "监听已配置，管理器当前可见"
                                else -> "填好上面两项，点下面保存"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (enabled) disable() else confirming = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (enabled) "立即恢复显示" else "保存并开始隐藏")
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "恢复方法：在「$dir」下新建文件夹「$name」，几秒内自动解除隐藏、静默装回并打开 rue，" +
                "随后隐藏自动关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DirPickerDialog(
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val ksuCli: KsuCliRepository = koinInject()
    var current by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(current) {
        loading = true
        entries = withContext(Dispatchers.IO) { listDirs(ksuCli, current) }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                current,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.height(360.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        TextButton(
                            onClick = { current = parentOf(current) },
                            enabled = current != "/",
                        ) {
                            Text(".. 上一级")
                        }
                    }
                    items(entries) { child ->
                        TextButton(
                            onClick = {
                                current = if (current == "/") "/$child" else "$current/$child"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(child, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!loading && entries.isEmpty()) {
                        item {
                            Text(
                                "（这个目录下没有子文件夹）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("选择此目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun buildEnableScript(context: Context, trigger: String): String {
    val watchB64 = assetBase64(context, "watch.sh")
    val supB64 = assetBase64(context, "supervisor.sh")
    val bootB64 = assetBase64(context, "boot.sh")
    return buildString {
        append("mkdir -p $ROOT_DIR /data/adb/service.d\n")
        append("APKSRC=\$(pm path $PKG | head -1 | sed 's/package://')\n")
        append("if [ -n \"\$APKSRC\" ]; then cp -f \"\$APKSRC\" $APK; chmod 644 $APK; fi\n")
        append("echo '$trigger' > $CONF\n")
        append("echo '$watchB64' | base64 -d > $WATCH\n")
        append("echo '$supB64' | base64 -d > $SUP\n")
        append("echo '$bootB64' | base64 -d > $BOOT\n")
        append("chmod 755 $WATCH $SUP $BOOT\n")
        append("pgrep -f /data/adb/rue/supervisor.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
        append("pgrep -f /data/adb/rue/watch.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
        append("setsid $SUP >/dev/null 2>&1 &\n")
        append("sleep 2\n")
        append("if [ -s $APK ]; then\n")
        append("  pm uninstall -k --user 0 $PKG >/dev/null 2>&1\n")
        append("  echo ENABLED\n")
        append("else\n")
        append("  echo 'no backup apk, fallback to pm hide'\n")
        append("  pm hide $PKG\n")
        append("  echo ENABLED_HIDE_ONLY\n")
        append("fi\n")
        append("am force-stop com.android.settings >/dev/null 2>&1\n")
    }
}

private fun buildDisableScript(): String = buildString {
    append("pgrep -f /data/adb/rue/supervisor.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
    append("pgrep -f /data/adb/rue/watch.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
    append("rm -f $BOOT $CONF\n")
    append("pm install-existing --user 0 $PKG >/dev/null 2>&1\n")
    append("pm unhide $PKG\n")
    append("am force-stop com.android.settings >/dev/null 2>&1\n")
    append("echo DISABLED\n")
}
