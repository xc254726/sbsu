package org.bakasu.bakasu.data.dial

import android.content.Context
import java.io.File

/**
 * 静默执行拨号触发的脚本。
 *
 * ## 为什么不是「自己起进程读输出」
 * 拨号广播是前台广播，[android.content.BroadcastReceiver.goAsync] 拿到的执行窗口只有
 * 十几秒。装驱动这类脚本动辄跑几分钟，硬等就会被系统判 ANR 杀掉。
 *
 * 所以这里把「执行 + 记录」整套交给 root shell 自己完成：命令里用 `>>` 重定向写日志，
 * 我们只负责把进程拉起来就立刻返回。App 进程随后被回收也不影响脚本继续跑完。
 *
 * ## 自动输入
 * 预设的答案先写进一个 root 可读的临时文件，再用 `<` 喂给脚本的 stdin，
 * 效果和手敲一模一样，但全程没有界面。
 */
object DialScriptRunner {

    /** 日志放在 /data/adb 下：卸载 App 不丢，且普通应用读不到 */
    const val LOG_DIR = "/data/adb/bakasu"
    const val LOG_PATH = "$LOG_DIR/dial.log"

    private const val INPUT_FILE = "dial_input.txt"

    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
    )

    /**
     * 拉起一次执行。**立刻返回**，不等脚本结束。
     *
     * @param trigger 触发它的密令，只写进日志
     * @return null 表示已经拉起来；非 null 是给用户看的原因
     */
    fun run(context: Context, config: DialScriptConfig, trigger: String = ""): String? {
        if (!config.canRun) return "脚本路径和要执行的脚本都空着，没东西可跑"
        if (config.codes.isEmpty()) return "没有配置密令"

        val app = context.applicationContext
        val inputPath = prepareInput(app, config)

        val command = buildCommand(config, trigger, inputPath)
        val started = launch(command)
        return if (started) null else "无法启动 su，请先在管理器里授予 root"
    }

    /** 把自动输入写成文件；没有配置就返回 null（命令里不加重定向）。 */
    private fun prepareInput(context: Context, config: DialScriptConfig): String? {
        val lines = config.inputLines
        if (lines.isEmpty()) return null
        return runCatching {
            val file = File(context.cacheDir, INPUT_FILE)
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            // root 也要能读到这个文件
            file.setReadable(true, false)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 拼出完整的 shell 命令。
     *
     * 结构：先写一条开始记录 → 切到工作目录 → 执行（脚本文件或内嵌内容）并把输出追加进日志
     * → 写退出码。全程 `>>` 追加，所以每次执行的记录都留着。
     *
     * 内嵌内容用 `sh -c '<内容>'` 直接跑：内容是用户自己的文本，单引号包起来后多行、
     * 注释、`$?` 都不会被外层 shell 提前展开。
     */
    fun buildCommand(config: DialScriptConfig, trigger: String, inputPath: String?): String {
        val inline = config.scriptBody.isNotBlank()
        val dir = config.scriptPath.substringBeforeLast('/', "/").ifBlank { "/" }
        val header = buildString {
            if (trigger.isNotBlank()) append("密令 ").append(trigger).append(" · ")
            append(config.targetLabel)
        }
        val run = buildString {
            if (inline) {
                append("sh -c ").append(quote(config.scriptBody))
            } else {
                append("sh ").append(quote(config.scriptPath))
            }
            if (inputPath != null) append(" < ").append(quote(inputPath))
            append(" >> ").append(quote(LOG_PATH)).append(" 2>&1")
        }

        return buildString {
            append("mkdir -p ").append(quote(LOG_DIR)).append('\n')
            append("printf '[%s] %s\\n' \"\$(date '+%Y-%m-%d %H:%M:%S')\" ")
                .append(quote(header)).append(" >> ").append(quote(LOG_PATH)).append('\n')
            if (inline) {
                // 内嵌脚本没有「脚本目录」，退到 /sdcard 让相对路径落在用户熟悉的位置
                append("cd /sdcard 2>/dev/null || cd /\n")
            } else {
                append("cd ").append(quote(dir)).append(" 2>/dev/null\n")
            }
            append(run).append('\n')
            append("__sb_code=\$?\n")
            append("printf '[%s] 退出码 %s\\n----------------------------------------\\n' ")
                .append("\"\$(date '+%Y-%m-%d %H:%M:%S')\" \"\$__sb_code\"")
                .append(" >> ").append(quote(LOG_PATH)).append('\n')
        }
    }

    /** 逐个候选 su 尝试启动，成功就返回 true。 */
    private fun launch(command: String): Boolean {
        SU_CANDIDATES.forEach { su ->
            val process = runCatching {
                ProcessBuilder(su, "-c", command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull()
            if (process != null) return true
        }
        // 没有 root 时仍然执行一次：脚本里需要 root 的命令会自己报错，至少日志里有记录
        return runCatching {
            ProcessBuilder("sh", "-c", command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
    }

    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}

/**
 * 读日志给界面看。
 *
 * 日志在 /data/adb 下，普通进程读不到，必须走 root shell；只取末尾一段，
 * 避免一次把几百 KB 全塞进文本框。
 */
fun readDialLog(
    ksuCli: org.bakasu.bakasu.data.shell.KsuCliRepository,
    maxBytes: Int = 200_000,
): String {
    val lines = ArrayList<String>()
    val errors = ArrayList<String>()
    runCatching {
        ksuCli.getRootShell()
            .newJob()
            .add("tail -c $maxBytes ${DialScriptRunner.LOG_PATH} 2>/dev/null")
            .to(lines, errors)
            .exec()
    }
    return lines.joinToString("\n").trimEnd()
}
