package org.bakasu.bakasu.ui.screen.stealth

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private const val ALIAS = "org.bakasu.bakasu.ui.LauncherAlias"
private const val COMP = "com.sb.manager/org.bakasu.bakasu.ui.MainActivity"
private const val DIR = "/data/adb/sb"
private const val WORK = "/data/adb/sb/work"

object Stealth {
    fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("sb_stealth", 0)

    fun sh(cmd: String): String = try {
        Shell.cmd(cmd).exec().out.joinToString("\n").trim()
    } catch (e: Exception) { "ERR: ${e.message}" }

    fun rootOk(): Boolean = sh("id").contains("uid=0")

    fun logRunning(): Boolean = sh("cat $WORK/watch.pid 2>/dev/null").isNotBlank()

    fun isHiddenIcon(ctx: android.content.Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(android.content.ComponentName(ctx.packageName, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (e: Exception) { false }

    fun setHide(ctx: android.content.Context, hide: Boolean, deep: Boolean): String {
        return if (deep) {
            sh(if (hide) "pm hide ${ctx.packageName} 2>&1" else "pm unhide ${ctx.packageName} 2>&1")
        } else {
            try {
                val st = if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                ctx.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(ctx.packageName, ALIAS), st, PackageManager.DONT_KILL_APP)
                "ok"
            } catch (e: Exception) { "ERR: ${e.message}" }
        }
    }

    fun save(ctx: android.content.Context, pkgs: String, mode: String, count: Int, win: Int, hold: Int, deep: Boolean, enable: Boolean): String {
        prefs(ctx).edit().putString("pkgs", pkgs).putString("mode", mode)
            .putInt("count", count).putInt("win", win).putInt("hold", hold)
            .putBoolean("deep", deep).putBoolean("enabled", enable).apply()
        if (!enable) return "已保存（未启用监听）"

        val script = java.io.File(ctx.filesDir, "watch.sh")
        ctx.assets.open("watch.sh").use { i -> script.outputStream().use { o -> i.copyTo(o) } }
        val args = "$pkgs $mode $count $win $hold $WORK $COMP ${if (deep) 1 else 0} $DIR/sb.apk"
        val cmd = listOf(
            "mkdir -p $DIR $WORK",
            "cp '${script.absolutePath}' $DIR/watch.sh",
            "cp '${ctx.applicationInfo.sourceDir}' $DIR/sb.apk",
            "chmod 700 $DIR $WORK $DIR/watch.sh",
            "chmod 600 $DIR/sb.apk",
            "printf '%s' '$args' > $DIR/args",
            "chmod 600 $DIR/args",
            "mkdir -p /data/adb/service.d",
            "printf '#!/system/bin/sh\\n[ -f $DIR/args ] || exit 0\\nsetsid sh $DIR/watch.sh \$(cat $DIR/args) >$WORK/watch.log 2>&1 &\\n' > /data/adb/service.d/sb-watch.sh",
            "chmod 700 /data/adb/service.d/sb-watch.sh",
            "rm -f $WORK/stop",
            "pkill -f 'sb/watch.sh' 2>/dev/null; true",
            "setsid sh $DIR/watch.sh $args >$WORK/watch.log 2>&1 &",
        ).joinToString(" ; ")
        return sh(cmd).ifBlank { "已启动" }
    }

    fun stop(): String = sh("touch $WORK/stop; sleep 0.3; pkill -f 'sb/watch.sh' 2>/dev/null; rm -f /data/adb/service.d/sb-watch.sh; true").ifBlank { "已停止" }

    fun log(): String = sh("tail -n 80 $WORK/watch.log 2>/dev/null").ifBlank { "（暂无日志）" }
}

@Composable
fun StealthScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val p = remember { Stealth.prefs(ctx) }

    var rootOk by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(false) }
    var enableCall by remember { mutableStateOf(p.getBoolean("enabled", false)) }
    var mode by remember { mutableStateOf(p.getString("mode", "long") ?: "long") }
    var hold by remember { mutableStateOf(p.getInt("hold", 1500)) }
    var count by remember { mutableStateOf(p.getInt("count", 2)) }
    var deep by remember { mutableStateOf(p.getBoolean("deep", true)) }
    var selected by remember { mutableStateOf(p.getString("pkgs", "") ?: "") }
    var msg by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootOk = Stealth.rootOk()
            running = Stealth.logRunning()
            hide = Stealth.isHiddenIcon(ctx) || !Stealth.sh("pm list packages ${ctx.packageName}").contains(ctx.packageName)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.pop() }) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Text("隐藏与呼出", style = MaterialTheme.typography.titleLarge)
        }

        Text("root：${if (rootOk) "可用" else "不可用"}    监听：${if (running) "运行中" else "未运行"}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("隐藏面具", modifier = Modifier.weight(1f))
                    Switch(checked = hide, onCheckedChange = {
                        hide = it
                        scope.launch {
                            msg = withContext(Dispatchers.IO) { Stealth.setHide(ctx, it, deep) }
                        }
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("开启后离开面具即从系统中隐藏；在选中的应用里按音量键可再次唤出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("呼出方式", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用音量键呼出", modifier = Modifier.weight(1f))
                    Switch(checked = enableCall, onCheckedChange = { enableCall = it })
                }
                Text("只在选中的应用里生效；离开该应用后自动停止监听",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("手势：", modifier = Modifier.width(70.dp))
                    FilterChip(selected = mode == "long", onClick = { mode = "long" }, label = { Text("长按") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = mode == "click", onClick = { mode = "click" }, label = { Text("连点") })
                }

                if (mode == "long") {
                    Spacer(Modifier.height(10.dp))
                    Text("长按时长", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1500 to "1.5s", 2000 to "2.0s", 3000 to "3.0s", 5000 to "5.0s").forEach { (ms, label) ->
                            FilterChip(selected = hold == ms, onClick = { hold = ms }, label = { Text(label) })
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("连点次数", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (selected.isBlank()) "未选择（不会响应）" else "已选 ${selected.split(",").filter { it.isNotBlank() }.size} 个：$selected",
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
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        if (hide) Stealth.setHide(ctx, true, deep)
                        Stealth.save(ctx, selected.replace(" ", ""), mode, count, 800, hold, deep, enableCall)
                    }
                    running = withContext(Dispatchers.IO) { Stealth.logRunning() }
                    busy = false
                    msg = r
                    if (hide) nav.pop()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (busy) "处理中…" else "保存并应用") }

        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                scope.launch {
                    logText = withContext(Dispatchers.IO) { Stealth.log() }
                    logOpen = true
                }
            }, modifier = Modifier.weight(1f)) { Text("查看监听日志") }
            TextButton(onClick = {
                scope.launch {
                    msg = withContext(Dispatchers.IO) { Stealth.stop() }
                    running = false
                }
            }, modifier = Modifier.weight(1f)) { Text("停止监听") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (picker) {
        val apps = remember(picker) { loadApps(ctx) }
        var tmp by remember(picker) { mutableStateOf(selected.split(",").filter { it.isNotBlank() }.toMutableSet()) }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("选择应用") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps) { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (tmp.contains(pkg)) tmp.remove(pkg) else tmp.add(pkg)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = tmp.contains(pkg), onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = tmp.joinToString(","); picker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("取消") } },
        )
    }

    if (logOpen) {
        AlertDialog(
            onDismissRequest = { logOpen = false },
            title = { Text("监听日志") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(logText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            } },
            confirmButton = { TextButton(onClick = { logOpen = false }) { Text("关闭") } },
        )
    }
}

private fun loadApps(ctx: android.content.Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
