package org.bakasu.bakasu.ui.screen.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.ui.navigation.LocalNavigator

private const val ALIAS = "org.bakasu.bakasu.ui.LauncherAlias"
private const val COMPONENT = "com.sb.manager/org.bakasu.bakasu.ui.MainActivity"
private const val DIR = "/data/adb/sb"
private const val WORK = "/data/adb/sb/work"
private const val PREFS = "sb_stealth"

object Stealth {
    fun load(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun iconHidden(ctx: Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx.packageName, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (e: Exception) { false }

    fun setIconHidden(ctx: Context, hidden: Boolean): Boolean = try {
        val st = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx.packageName, ALIAS), st, PackageManager.DONT_KILL_APP)
        true
    } catch (e: Exception) { false }

    fun sh(cmd: String): String = try {
        Shell.cmd(cmd).exec().out.joinToString("\n").trim()
    } catch (e: Exception) { "ERR: ${e.message}" }

    /** 部署脚本 + 开机自启 + 立即启动 */
    fun enable(ctx: Context, pkgs: String, mode: String, count: Int, win: Int, hold: Int, deep: Boolean): String {
        val p = load(ctx)
        p.edit().putBoolean("enabled", true).putString("pkgs", pkgs).putString("mode", mode)
            .putInt("count", count).putInt("win", win).putInt("hold", hold)
            .putBoolean("deep", deep).apply()

        val scripts = File(ctx.filesDir, "watch.sh")
        ctx.assets.open("watch.sh").use { i -> scripts.outputStream().use { o -> i.copyTo(o) } }
        // 把当前 APK 也放进去（深隐时用来重新安装）
        val apkPath = ctx.applicationInfo.sourceDir
        val args = "$pkgs $mode $count $win $hold $WORK $COMPONENT ${if (deep) 1 else 0} $DIR/sb.apk"

        val cmd = listOf(
            "mkdir -p $DIR $WORK",
            "cp '${scripts.absolutePath}' $DIR/watch.sh",
            "cp '$apkPath' $DIR/sb.apk",
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
        val out = sh(cmd)
        Thread.sleep(800)
        return out.ifBlank { "已启动" }
    }

    fun disable(): String {
        sh("touch $WORK/stop; sleep 0.3; pkill -f 'sb/watch.sh' 2>/dev/null; rm -f /data/adb/service.d/sb-watch.sh; true")
        return "已停止"
    }

    fun deepHide(on: Boolean): String = if (on) {
        sh("pm hide com.sb.manager 2>&1")
    } else {
        sh("pm unhide com.sb.manager 2>&1")
    }

    fun readLog(): String {
        val out = sh("tail -n 60 $WORK/watch.log 2>/dev/null")
        return out.ifBlank { "（暂无日志）" }
    }
}

@Composable
fun StealthScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Stealth.load(ctx) }

    var pkgs by remember { mutableStateOf(prefs.getString("pkgs", "") ?: "") }
    var mode by remember { mutableStateOf(prefs.getString("mode", "long") ?: "long") }
    var count by remember { mutableStateOf(prefs.getInt("count", 2).toString()) }
    var hold by remember { mutableStateOf(prefs.getInt("hold", 2000).toString()) }
    var hidden by remember { mutableStateOf(Stealth.iconHidden(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.pop() }) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Text("隐藏与呼出", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "在指定应用内用音量键手势拉起面具；隐藏后桌面没有图标。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = pkgs, onValueChange = { pkgs = it },
            label = { Text("目标应用包名（多个用逗号分隔）") },
            placeholder = { Text("com.tencent.tmgp.codev") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("触发方式", modifier = Modifier.weight(1f))
            FilterChip(selected = mode == "long", onClick = { mode = "long" }, label = { Text("长按") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == "click", onClick = { mode = "click" }, label = { Text("连按") })
        }

        if (mode == "click") {
            OutlinedTextField(
                value = count, onValueChange = { count = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("连按次数（音量键）") }, singleLine = true,
                modifier = Modifier.width(200.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } else {
            OutlinedTextField(
                value = hold, onValueChange = { hold = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("长按毫秒（如 2000 = 2 秒）") }, singleLine = true,
                modifier = Modifier.width(240.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("隐藏桌面图标")
                Text("关掉后桌面和抽屉里看不到", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = hidden, onCheckedChange = {
                hidden = it
                Stealth.setIconHidden(ctx, it)
                msg = if (it) "图标已隐藏，用音量手势呼出" else "图标已恢复"
            })
        }

        msg?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                if (pkgs.isBlank()) { msg = "请先填目标应用包名"; return@Button }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        Stealth.enable(ctx, pkgs.replace(" ", ""), mode,
                            count.toIntOrNull() ?: 2, 800, hold.toIntOrNull() ?: 2000, false)
                    }
                    busy = false
                    msg = "已启用：$r"
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (busy) "处理中…" else "保存并启用监听") }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Stealth.disable() }
                    msg = r
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("停止监听") }

        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                scope.launch {
                    logText = withContext(Dispatchers.IO) { Stealth.readLog() }
                    logOpen = true
                }
            }, modifier = Modifier.weight(1f)) { Text("查看监听日志") }
            TextButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Stealth.sh("id") }
                    msg = if (r.contains("uid=0")) "Root 正常 ✅" else "Root 异常：$r"
                }
            }, modifier = Modifier.weight(1f)) { Text("检查 Root") }
        }

        Text(
            "说明：监听只在目标应用前台时开启，离开自动关闭；命中一次后自动退出，下次隐藏时重新启动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (logOpen) {
        AlertDialog(
            onDismissRequest = { logOpen = false },
            title = { Text("监听日志") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(logText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { logOpen = false }) { Text("关闭") } },
        )
    }
}
