package org.bakasu.bakasu.data.file

import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 交互式脚本会话。
 *
 * 与 [RootFileRepository.runScript] 不同，这里不会等脚本跑完才返回：stdout / stderr
 * 一旦有数据就回传（包括没有换行的提示语，例如「请输入 1-8：」），同时允许把界面
 * 上输入的内容写回 stdin，因此需要用户输入的分支脚本可以正常走完。
 */
class ScriptSession private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
) {
    @Volatile
    private var finished = false

    /** 把一行内容写进脚本的 stdin，由脚本自己决定怎么读。 */
    fun send(line: String) {
        if (finished) return
        runCatching {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    /** 结束会话：先关管道再杀进程，脚本会收到 EOF / 终止信号。 */
    fun stop() {
        finished = true
        runCatching { writer.close() }
        runCatching { process.destroy() }
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val SU_CANDIDATES = listOf(
            "su",
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
        )

        /**
         * 启动脚本并开始回传输出。
         *
         * @param onOutput 每次收到的原始片段，可能是半行提示语，调用方自行拼接
         * @param onFinished 进程退出时回调，参数为退出码，拿不到时为 -1
         * @return 启动失败（找不到 su / sh）时返回 null
         */
        fun start(
            scriptPath: String,
            args: String,
            onOutput: (String) -> Unit,
            onFinished: (Int) -> Unit,
        ): ScriptSession? {
            val directory = scriptPath.substringBeforeLast('/', "/").ifBlank { "/" }
            val command = buildString {
                append("cd ")
                append(quote(directory))
                append(" && exec sh ")
                append(quote(scriptPath))
                if (args.isNotBlank()) {
                    append(' ')
                    append(args)
                }
            }

            val process = launchRoot(command) ?: return null
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            val session = ScriptSession(process, writer)

            Thread {
                var code = -1
                try {
                    val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val chunk = StringBuilder(count)
                        chunk.appendRange(buffer, 0, count)
                        // 管道里还有数据就一起取走，减少 UI 刷新次数
                        while (reader.ready()) {
                            val more = reader.read(buffer)
                            if (more < 0) break
                            chunk.appendRange(buffer, 0, more)
                        }
                        val text = ANSI.replace(chunk.toString(), "")
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                        if (text.isNotEmpty()) onOutput(text)
                    }
                    code = runCatching { process.waitFor() }.getOrDefault(-1)
                } catch (_: Throwable) {
                    code = runCatching { process.waitFor() }.getOrDefault(-1)
                } finally {
                    session.finished = true
                    runCatching { writer.close() }
                    runCatching { process.destroy() }
                    onFinished(code)
                }
            }.apply { isDaemon = true }.start()

            return session
        }

        private fun launchRoot(command: String): Process? {
            SU_CANDIDATES.forEach { su ->
                val started = runCatching {
                    ProcessBuilder(su, "-c", command)
                        .redirectErrorStream(true)
                        .start()
                }.getOrNull()
                if (started != null) return started
            }
            // 没有 root 时退化成普通权限执行，脚本里需要 root 的命令会自己报错
            return runCatching {
                ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull()
        }

        private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
    }
}
