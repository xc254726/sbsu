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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    var folder by remember { mutableStateOf("rue") }
    var enabled by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var pid by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

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
                Triple(conf, state.contains("hidden=true"), running)
            }
            if (res.first.isNotBlank()) folder = res.first
            enabled = res.first.isNotBlank()
            hidden = res.second
            pid = res.third
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun enable() {
        val name = folder.trim()
        if (name.isEmpty()) {
            message = "先填一个文件夹名字"
            return
        }
        scope.launch {
            busy = true
            message = ""
            val result = withContext(Dispatchers.IO) {
                runRootShell(ksuCli, buildEnableScript(context, name))
            }
            message = if (result.contains("ENABLED")) "已开启：管理器已隐藏，等待文件夹触发" else result.take(300)
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
                    "开启后管理器会从桌面、应用管理、电池统计里消失，同时后台守护会监听内部存储里的指定文件夹；" +
                        "只要建出那个文件夹，就会自动把 rue 装回来并打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "注意：隐藏后管理器界面一旦被系统清掉就打不开了，只能靠文件夹恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it.filter { c -> !c.isWhitespace() && c != '/' }.take(32) },
            label = { Text("监听文件夹名") },
            supportingText = { Text("例：填 rue，就会监听 /sdcard/rue") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用隐藏与监听", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            busy -> "处理中…"
                            enabled && hidden -> "隐藏中 · 守护进程 ${if (pid.isBlank()) "未运行" else "PID $pid"}"
                            enabled -> "监听已配置，管理器当前可见"
                            else -> "未启用"
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
                } else {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on -> if (on) enable() else disable() },
                    )
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
            "恢复方法：在内部存储根目录新建文件夹「$folder」，几秒后 rue 会自动出现在桌面并打开。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun buildEnableScript(context: Context, folder: String): String {
    val watchB64 = assetBase64(context, "watch.sh")
    val bootB64 = assetBase64(context, "boot.sh")
    return buildString {
        append("mkdir -p $ROOT_DIR /data/adb/service.d\n")
        append("APKSRC=\$(pm path $PKG | head -1 | sed 's/package://')\n")
        append("if [ -n \"\$APKSRC\" ]; then cp -f \"\$APKSRC\" $APK; chmod 644 $APK; fi\n")
        append("echo '$folder' > $CONF\n")
        append("echo '$watchB64' | base64 -d > $WATCH\n")
        append("chmod 755 $WATCH\n")
        append("echo '$bootB64' | base64 -d > $BOOT\n")
        append("chmod 755 $BOOT\n")
        append("pgrep -f /data/adb/rue/watch.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
        append("setsid $WATCH >/dev/null 2>&1 &\n")
        append("sleep 1\n")
        append("pm hide $PKG\n")
        append("am force-stop com.android.settings >/dev/null 2>&1\n")
        append("echo ENABLED\n")
    }
}

private fun buildDisableScript(): String = buildString {
    append("pgrep -f /data/adb/rue/watch.sh | while read p; do kill -9 \$p 2>/dev/null; done\n")
    append("rm -f $BOOT $CONF\n")
    append("pm unhide $PKG\n")
    append("am force-stop com.android.settings >/dev/null 2>&1\n")
    append("echo DISABLED\n")
}
