package org.bakasu.bakasu.data.trigger

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 自动触发引擎
 *
 * 配置：/data/adb/sb_trigger.conf         (root)
 * 输入：/data/adb/sb_input.txt           (root，脚本执行后逐行 input text)
 * 守护：/data/adb/service.d/sb_trigger.sh (KernelSU 开机自动执行)
 * 日志：/data/adb/sb_trigger.log
 */
object TriggerEngine {

    private const val CONF = "/data/adb/sb_trigger.conf"
    private const val INPUT_FILE = "/data/adb/sb_input.txt"
    private const val STATE_CNT = "/data/adb/sb_trigger.cnt"
    private const val STATE_LAST = "/data/adb/sb_trigger.last"
    private const val DAEMON = "/data/adb/service.d/sb_trigger.sh"
    const val LOG_FILE = "/data/adb/sb_trigger.log"

    private const val PREFS = "sb_auto_trigger"
    private const val K_ENABLED = "enabled"
    private const val K_PKG = "pkg"
    private const val K_LABEL = "label"
    private const val K_PRESS = "press"
    private const val K_INTERVAL = "interval"
    private const val K_SCRIPT = "script"
    private const val K_INPUT = "input"

    data class Config(
        val enabled: Boolean = false,
        val appPkg: String = "",
        val appLabel: String = "",
        val pressCount: Int = 2,
        val intervalSec: Int = 1,
        val scriptPath: String = "/sdcard/sb/trigger.sh",
        val inputText: String = "",
    )

    /* ---------------- 本地配置 ---------------- */

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = p.getBoolean(K_ENABLED, false),
            appPkg = p.getString(K_PKG, "") ?: "",
            appLabel = p.getString(K_LABEL, "") ?: "",
            pressCount = p.getInt(K_PRESS, 2),
            intervalSec = p.getInt(K_INTERVAL, 1),
            scriptPath = p.getString(K_SCRIPT, "/sdcard/sb/trigger.sh") ?: "/sdcard/sb/trigger.sh",
            inputText = p.getString(K_INPUT, "") ?: "",
        )
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putString(K_PKG, cfg.appPkg)
            .putString(K_LABEL, cfg.appLabel)
            .putInt(K_PRESS, cfg.pressCount)
            .putInt(K_INTERVAL, cfg.intervalSec)
            .putString(K_SCRIPT, cfg.scriptPath)
            .putString(K_INPUT, cfg.inputText)
            .apply()
    }

    /* ---------------- root ---------------- */

    private fun rootShell(): Shell = Shell.Builder.create().setTimeout(25).build()

    private fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun sh(cmd: String): Shell.Result = rootShell().newJob().add(cmd).exec()

    private fun out(r: Shell.Result): String = r.out.joinToString("\n").trim()

    /** 写配置 + 安装守护脚本，并启停 */
    fun apply(ctx: Context, cfg: Config): String? {
        val dir = File(ctx.cacheDir, "trigger").apply { mkdirs() }

        val conf = File(dir, "sb_trigger.conf")
        conf.writeText(
            buildString {
                appendLine("APP=\"${cfg.appPkg}\"")
                appendLine("PRESS=${cfg.pressCount.coerceIn(1, 9)}")
                appendLine("INTERVAL=${cfg.intervalSec.coerceIn(0, 9)}")
                appendLine("SCRIPT=\"${cfg.scriptPath}\"")
                appendLine("RESTORE=1")
            },
        )

        val input = File(dir, "sb_input.txt")
        input.writeText(cfg.inputText)

        val daemon = File(dir, "sb_trigger.sh")
        daemon.writeText(SCRIPT)

        val cmd = listOf(
            "mkdir -p /data/adb/service.d",
            "cp ${q(conf.absolutePath)} $CONF",
            "cp ${q(input.absolutePath)} $INPUT_FILE",
            "cp ${q(daemon.absolutePath)} $DAEMON",
            "chmod 755 $DAEMON",
            "chmod 600 $CONF $INPUT_FILE",
            "chown root:root $DAEMON $CONF $INPUT_FILE",
            "echo 0 > $STATE_CNT",
            "echo 0 > $STATE_LAST",
            ": > $LOG_FILE",
        ).joinToString(" && ")

        val r = sh("$cmd 2>&1")
        if (r.code != 0) return out(r).ifBlank { "写入失败" }

        if (!cfg.enabled) {
            stop()
            return null
        }
        return if (start()) null else "启动守护失败"
    }

    fun start(): Boolean {
        sh("pkill -f sb_trigger.sh 2>/dev/null; true")
        sh("nohup sh $DAEMON >/dev/null 2>&1 < /dev/null &")
        Thread.sleep(900)
        return isRunning()
    }

    fun stop() {
        sh("pkill -f sb_trigger.sh 2>/dev/null; true")
    }

    fun isRunning(): Boolean {
        val r = sh("ps -A -o PID,ARGS 2>/dev/null | grep '[s]b_trigger.sh' | wc -l")
        return r.code == 0 && (out(r).toIntOrNull() ?: 0) > 0
    }

    fun readLog(): String {
        val r = sh("tail -n 80 $LOG_FILE 2>/dev/null")
        return out(r).ifBlank { "（暂无日志）" }
    }

    /** 立即测试：不看前台应用，直接跑脚本 + 输入文字 */
    fun testNow(cfg: Config): String {
        val cmd = buildString {
            append("(")
            if (cfg.scriptPath.isNotBlank()) {
                append("if [ -f ${q(cfg.scriptPath)} ]; then sh ${q(cfg.scriptPath)} < $INPUT_FILE; ")
                append("else echo \"脚本不存在: ${cfg.scriptPath}\"; fi; ")
            }
            append("if [ -s $INPUT_FILE ]; then ")
            append("while IFS= read -r line; do [ -z \"\$line\" ] && continue; ")
            append("input text \"\$(echo \"\$line\" | sed 's/ /%s/g')\"; input keyevent 66; ")
            append("done < $INPUT_FILE; ")
            append("fi")
            append(") 2>&1")
        }
        return out(sh(cmd)).ifBlank { "已执行" }
    }

    /* ---------------- 守护脚本 ---------------- */

    private val SCRIPT: String = """
        |#!/system/bin/sh
        |CONF=/data/adb/sb_trigger.conf
        |INFILE=/data/adb/sb_input.txt
        |CNT=/data/adb/sb_trigger.cnt
        |LAST=/data/adb/sb_trigger.last
        |LOG=/data/adb/sb_trigger.log
        |
        |[ -f "@CONF" ] || exit 0
        |. "@CONF"
        |
        |echo "[@(date '+%m-%d %H:%M:%S')] daemon start APP=@APP PRESS=@PRESS INTERVAL=@INTERVAL" >> "@LOG"
        |
        |DEV=""
        |for d in /dev/input/event*; do
        |  if getevent -lp "@d" 2>/dev/null | grep -q KEY_VOLUMEUP; then DEV="@d"; break; fi
        |done
        |[ -n "@DEV" ] || { echo "no volume device" >> "@LOG"; exit 0; }
        |echo "dev=@DEV" >> "@LOG"
        |
        |fg_pkg() {
        |  p=@(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed -n 's/.*u0 \([^\/ ]*\)\/.*/\1/p' | head -1)
        |  if [ -z "@p" ]; then
        |    p=@(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | sed -n 's/.*u0 \([^\/ ]*\)\/.*/\1/p' | head -1)
        |  fi
        |  echo "@p"
        |}
        |
        |echo 0 > "@CNT"
        |echo 0 > "@LAST"
        |
        |getevent -l "@DEV" 2>/dev/null | while read -r l; do
        |  case "@l" in
        |    *KEY_VOLUMEUP*DOWN*)
        |      now=@(date +%s)
        |      cnt=@(cat "@CNT" 2>/dev/null || echo 0)
        |      last=@(cat "@LAST" 2>/dev/null || echo 0)
        |      if [ "@cnt" -gt 0 ] && [ @((now-last)) -le "@INTERVAL" ]; then
        |        cnt=@((cnt+1))
        |      else
        |        cnt=1
        |      fi
        |      echo "@now" > "@LAST"
        |      echo "@cnt" > "@CNT"
        |      if [ "@cnt" -ge "@PRESS" ]; then
        |        echo 0 > "@CNT"
        |        p=@(fg_pkg)
        |        echo "[@(date '+%m-%d %H:%M:%S')] trigger pkg=@p" >> "@LOG"
        |        if [ "@p" = "@APP" ]; then
        |          if [ -f "@SCRIPT" ]; then
        |            sh "@SCRIPT" < "@INFILE" >> "@LOG" 2>&1
        |          fi
        |          if [ -s "@INFILE" ]; then
        |            while IFS= read -r line; do
        |              [ -z "@line" ] && continue
        |              input text "@(echo "@line" | sed 's/ /%s/g')"
        |              input keyevent 66
        |            done < "@INFILE"
        |          fi
        |          if [ "@RESTORE" = "1" ]; then
        |            i=0
        |            while [ @i -lt "@PRESS" ]; do input keyevent 25; i=@((i+1)); done
        |          fi
        |        fi
        |      fi
        |      ;;
        |  esac
        |done
        |""".trimMargin().replace("@", "$")
}
