package org.bakasu.bakasu.data.trigger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动触发配置 / 日志
 */
object TriggerEngine {

    private const val PREFS = "sb_auto_trigger"
    private const val K_ENABLED = "enabled"
    private const val K_PKG = "pkg"
    private const val K_HOLD = "hold"
    private const val K_SCRIPT = "script"
    private const val K_INPUT = "input"

    data class Config(
        val enabled: Boolean = false,
        val appPkg: String = "",
        val holdSeconds: Int = 2,
        val scriptPath: String = "/data/VT内核.sh",
        val inputText: String = "",
    )

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = p.getBoolean(K_ENABLED, false),
            appPkg = p.getString(K_PKG, "") ?: "",
            holdSeconds = p.getInt(K_HOLD, 2),
            scriptPath = p.getString(K_SCRIPT, "/data/VT内核.sh") ?: "",
            inputText = p.getString(K_INPUT, "") ?: "",
        )
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putString(K_PKG, cfg.appPkg)
            .putInt(K_HOLD, cfg.holdSeconds)
            .putString(K_SCRIPT, cfg.scriptPath)
            .putString(K_INPUT, cfg.inputText)
            .apply()
    }

    /* ---------------- 日志 ---------------- */

    private fun logFile(ctx: Context): File = File(ctx.filesDir, "trigger.log")

    @Synchronized
    fun log(ctx: Context, msg: String) {
        val f = logFile(ctx)
        try {
            if (f.exists() && f.length() > 200_000) f.delete()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {
        }
    }

    fun readLog(ctx: Context): String {
        val f = logFile(ctx)
        if (!f.exists()) return "（暂无日志）"
        return try {
            f.readLines().takeLast(120).joinToString("\n").ifBlank { "（暂无日志）" }
        } catch (e: Exception) {
            "（读取失败：${e.message}）"
        }
    }

    fun clearLog(ctx: Context) {
        try {
            logFile(ctx).delete()
        } catch (_: Exception) {
        }
    }
}
