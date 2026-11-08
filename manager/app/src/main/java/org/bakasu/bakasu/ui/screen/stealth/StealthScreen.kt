package org.bakasu.bakasu.ui.screen.stealth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.ui.navigation.LocalNavigator

private const val DIR = "/data/adb/sb"
private const val WORK = "/data/adb/sb/work"
private const val COMP = "com.sb.manager/org.bakasu.bakasu.ui.MainActivity"
private const val ALIAS = "org.bakasu.bakasu.ui.LauncherAlias"
private const val PREFS = "sb_stealth"

private fun sh(cmd: String): String = try {
    Shell.cmd(cmd).exec().out.joinToString("\n").trim()
} catch (e: Exception) {
    "ERR: ${e.message}"
}

private fun pkgsOf(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("pkgs", "") ?: ""

private fun isRunning(): Boolean {
    val out = sh("[ -f $WORK/watch.pid ] && kill -0 \"\$(cat $WORK/watch.pid 2>/dev/null)\" 2>/dev/null && echo RUNNING || echo STOPPED")
    return out.contains("RUNNING")
}

private fun isHiddenMask(ctx: Context): Boolean =
    !sh("pm list packages 2>/dev/null").lines().any { it.trim() == "package:${ctx.packageName}" }

/** 写配置 + 部署脚本 + 启停 */
private fun deploy(ctx: Context, hide: Boolean, enable: Boolean, pkgs: String, mode: String, count: Int, hold: Int): String {
    ctx.getSharedPreferences(PREFS, 0).edit()
        .putString("pkgs", pkgs).putString("mode", mode)
        .putInt("count", count).putInt("hold", hold)
        .putBoolean("hide", hide).putBoolean("enabled", enable).apply()

    if (!enable) {
        sh("touch $WORK/stop 2>/dev/null; sleep 0.5; P=\$(cat $WORK/watch.pid 2>/dev/null); [ -n \"\$P\" ] && kill -9 \$P 2>/dev/null; rm -f $WORK/watch.pid /data/adb/service.d/sb-watch.sh")
        return if (hide) hideMask(ctx) else ""
    }

    val script = java.io.File(ctx.filesDir, "watch.sh")
    try {
        ctx.assets.open("watch.sh").use { i -> script.outputStream().use { o -> i.copyTo(o) } }
    } catch (e: Exception) {
        return "读取内置脚本失败: ${e.message}"
    }

    val args = "$pkgs $mode $count 800 $hold $WORK $COMP 1 $DIR/sb.apk"
    val cmd = listOf(
        "mkdir -p $DIR $WORK /data/adb/service.d",
        "cp -f '${script.absolutePath}' $DIR/watch.sh",
        "cp -f '${ctx.applicationInfo.sourceDir}' $DIR/sb.apk",
        "chmod 700 $DIR $WORK $DIR/watch.sh",
        "chmod 600 $DIR/sb.apk",
        "printf '%s' '$args' > $DIR/args",
        "chmod 600 $DIR/args",
        "echo 1 > $WORK/deep_hide",
        "printf '#!/system/bin/sh\\n[ -f $DIR/args ] || exit 0\\nsetsid sh $DIR/watch.sh \$(cat $DIR/args) >$WORK/watch.log 2>&1 &\\n' > /data/adb/service.d/sb-watch.sh",
        "chmod 700 /data/adb/service.d/sb-watch.sh",
        "rm -f $WORK/stop",
        "pkill -f 'sb/watch.sh' 2>/dev/null; true",
        "setsid sh $DIR/watch.sh $args >$WORK/watch.log 2>&1 &",
    ).joinToString(" ; ")
    val out = sh(cmd)
    Thread.sleep(1200)
    val running = isRunning()
    val extra = if (hide) hideMask(ctx) else ""
    return (if (running) "监听已启动" else "启动失败\n" + sh("tail -n 10 $WORK/watch.log 2>/dev/null")) + (if (extra.isNotBlank()) "\n$extra" else "") + (if (out.isBlank()) "" else "")
}

private fun hideMask(ctx: Context): String {
    val r = sh("pm hide ${ctx.packageName} 2>&1")
    return if (r.isBlank() || !r.contains("Error")) "面具已隐藏，用音量手势呼出" else "隐藏失败：$r"
}

private fun stopAll(): String = sh(
    "touch $WORK/stop 2>/dev/null; sleep 0.5; P=\$(cat $WORK/watch.pid 2>/dev/null); [ -n \"\$P\" ] && kill -9 \$P 2>/dev/null; " +
        "for q in \$(ps -A -o PID,ARGS 2>/dev/null | awk '\$2 == \"sh\" && \$3 ~ /watch\\.sh\$/ {print \$1}'); do kill -9 \$q 2>/dev/null; done; " +
        "rm -f $WORK/watch.pid $WORK/stop /data/adb/service.d/sb-watch.sh"
).ifBlank { "已停止" }

@Composable
fun StealthScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences(PREFS, 0) }

    var rootOk by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(p.getBoolean("hide", false)) }
    var enableCall by remember { mutableStateOf(p.getBoolean("enabled", false)) }
    var mode by remember { mutableStateOf(p.getString("mode", "long") ?: "long") }
    var hold by remember { mutableStateOf(p.getInt("hold", 1500)) }
    var count by remember { mutableStateOf(p.getInt("count", 2)) }
    var selected by remember { mutableStateOf(pkgsOf(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootOk = sh("id").contains("uid=0")
            running = isRunning()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.pop() }) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Text("隐藏与呼出", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "root：${if (rootOk) "可用" else "不可用"}    监听：${if (running) "运行中" else "未运行"}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("隐藏面具", modifier = Modifier.weight(1f))
                    Switch(checked = hide, onCheckedChange = { hide = it })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "开启后离开面具即从系统中隐藏；在选中的应用里按音量键可再次唤出。保存后本页会立即关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("呼出方式", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用音量键呼出", modifier = Modifier.weight(1f))
                    Switch(checked = enableCall, onCheckedChange = { enableCall = it })
                }
                Text(
                    "只在选中的应用里生效；离开该应用后自动停止监听",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("手势：", fontSize = 13.sp, modifier = Modifier.width(56.dp))
                    FilterChip(selected = mode == "long", onClick = { mode = "long" }, label = { Text("长按") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = mode == "click", onClick = { mode = "click" }, label = { Text("连点") })
                }
                if (mode == "long") {
                    Spacer(Modifier.height(8.dp))
                    Text("长按时长", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1500 to "1.5s", 2000 to "2.0s", 3000 to "3.0s", 5000 to "5.0s").forEach { (ms, label) ->
                            FilterChip(selected = hold == ms, onClick = { hold = ms }, label = { Text(label) })
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("连点次数", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 3, 4).forEach { n ->
                            FilterChip(selected = count == n, onClick = { count = n }, label = { Text("${n}下") })
                        }
                    }
                }
            }
        }

        Text("生效的应用", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (selected.isBlank()) "未选择" else "已选 ${selected.split(",").count { it.isNotBlank() }} 个：$selected",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(onClick = { picker = true }) { Text("选择应用") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { selected = "" }) { Text("清空") }
                }
            }
        }

        msg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(
            enabled = !busy,
            onClick = {
                if (enableCall && selected.isBlank()) { msg = "请先选择生效的应用"; return@Button }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        deploy(ctx, hide, enableCall, selected.replace(" ", ""), mode, count, hold)
                    }
                    running = withContext(Dispatchers.IO) { isRunning() }
                    busy = false
                    msg = r
                    if (hide && r.contains("已隐藏")) nav.pop()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (busy) "处理中…" else "保存并应用") }

        TextButton(onClick = {
            scope.launch {
                msg = withContext(Dispatchers.IO) { stopAll() }
                running = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("停止监听") }

        Spacer(Modifier.height(24.dp))
    }

    if (picker) {
        val apps = remember(picker) { launchableApps(ctx) }
        var tmp by remember(picker) { mutableStateOf(selected.split(",").filter { it.isNotBlank() }.toMutableSet()) }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("选择应用") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps) { pair ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (tmp.contains(pair.first)) tmp.remove(pair.first) else tmp.add(pair.first)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = tmp.contains(pair.first), onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(pair.second, style = MaterialTheme.typography.bodyLarge)
                                Text(pair.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = tmp.joinToString(","); picker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("取消") } },
        )
    }
}

private fun launchableApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
