package org.bakasu.bakasu.ui.screen.hideMask

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.ui.navigation.LocalNavigator

private const val STUB_PKG = "com.android.sysupdate"
private const val STUB_ACT = "com.android.sysupdate.SetupActivity"
private const val MANAGER_PKG = "com.sb.manager"
private const val MANAGER_ACT = "com.sb.manager/org.bakasu.bakasu.ui.MainActivity"
private const val CODE_FILE = "/data/local/tmp/sb_hide_code"

@Composable
fun HideMaskScreen() {
    val ctx = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("8899") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun sh(cmd: String): String = try {
        val r = Shell.cmd(cmd).exec()
        r.out.joinToString("\n").trim()
    } catch (e: Exception) {
        "ERR: ${e.message}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigator.pop() }) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Text("隐藏面具", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            text = "隐藏后：桌面和应用管理里都看不到 sb（小壳除外）。\n恢复方式：拨号盘输入密令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = code,
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(8)
                code = digits.ifEmpty { "" }
            },
            label = { Text("恢复密令（数字）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Text(
            text = "拨号时输入：*#*#" + code + "#*#*",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )

        message?.let {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        try {
                            val f = File(ctx.cacheDir, "stub.apk")
                            ctx.assets.open("stub.apk").use { input ->
                                f.outputStream().use { output -> input.copyTo(output) }
                            }
                            sh("cp '${f.absolutePath}' /data/local/tmp/stub.apk && pm install -r /data/local/tmp/stub.apk 2>&1")
                        } catch (e: Exception) {
                            "ERR: ${e.message}"
                        }
                    }
                    busy = false
                    message = if (r.contains("Success")) "小壳安装成功 ✅" else "安装结果：$r"
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("① 安装小壳（静默）")
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                try {
                    ctx.startActivity(
                        Intent().apply {
                            setClassName(STUB_PKG, STUB_ACT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                    message = "已打开小壳界面，请点「初始化」授权 Root，然后回来"
                } catch (e: Exception) {
                    message = "没找到小壳，请先点 ① 安装"
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("② 初始化小壳（授权 Root）")
        }

        Button(
            enabled = !busy,
            onClick = {
                if (code.isBlank()) {
                    message = "请先填写密令"
                } else {
                    busy = true
                    message = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            sh("echo '$code' > $CODE_FILE && chmod 644 $CODE_FILE")
                            sh("pm hide $MANAGER_PKG 2>&1")
                        }
                        busy = false
                        message = "隐藏结果：$r\n（如果成功，sb 会立刻从桌面消失，用密令恢复）"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("③ 隐藏面具")
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        sh("pm unhide $MANAGER_PKG 2>&1")
                        sh("am start -n $MANAGER_ACT")
                    }
                    message = "恢复结果：$r"
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("恢复面具（不拨号时用）")
        }

        Text(
            text = "⚠️ 隐藏前请确认：KernelSU 的「超级用户」里，系统组件（com.android.sysupdate）是开启状态，否则恢复会失败。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(24.dp))
    }
}
