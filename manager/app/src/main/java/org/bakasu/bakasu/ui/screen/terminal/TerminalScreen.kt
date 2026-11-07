package org.bakasu.bakasu.ui.screen.terminal

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.ui.navigation.LocalNavigator

@Composable
fun TerminalScreen() {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var output by remember { mutableStateOf("sb 终端 · 以 root 身份执行\n") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val shell = remember { Shell.Builder.create().setTimeout(30).build() }

    LaunchedEffect(output) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    fun run() {
        val cmd = input.trim()
        if (cmd.isEmpty() || busy) return
        output += "\n$ $cmd\n"
        input = ""
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val r = shell.newJob().add(cmd).exec()
                    buildString {
                        val o = r.out.joinToString("\n").trimEnd()
                        val e = r.err.joinToString("\n").trimEnd()
                        if (o.isNotEmpty()) append(o)
                        if (e.isNotEmpty()) {
                            if (o.isNotEmpty()) append("\n")
                            append(e)
                        }
                    }.ifBlank { "(无输出)" }
                } catch (ex: Exception) {
                    "ERR: ${ex.message}"
                }
            }
            output += res + "\n"
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigator.pop() }) { Text("‹ 返回") }
            Spacer(Modifier.width(8.dp))
            Text("终端", style = MaterialTheme.typography.titleLarge)
        }

        Surface(
            color = Color(0xFF101014),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Text(
                text = output,
                color = Color(0xFF9AE6B4),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(10.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入命令，例如 id 或 ls /data/adb") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { run() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy,
                onClick = { run() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp),
            ) {
                Text(if (busy) "…" else "执行")
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { output = "sb 终端 · 以 root 身份执行\n" },
                modifier = Modifier.weight(1f),
            ) {
                Text("清屏")
            }
            TextButton(
                onClick = {
                    input = ""
                    busy = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            try {
                                val r = shell.newJob().add("id; uname -a").exec()
                                r.out.joinToString("\n").trim().ifBlank { "(无输出)" }
                            } catch (ex: Exception) {
                                "ERR: ${ex.message}"
                            }
                        }
                        output += "\n" + res + "\n"
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("检查 root")
            }
        }
    }
}
