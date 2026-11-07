package org.bakasu.bakasu.data.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 自动触发后台服务
 *
 * 用 root 跑 getevent 实时读音量键，符合条件时执行脚本并输入文字。
 */
class TriggerService : Service() {

    companion object {
        const val CHANNEL_ID = "sb_trigger"
        const val NOTI_ID = 0x5B01

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, TriggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TriggerService::class.java))
        }
    }

    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null
    private var eventProc: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildNotification("监听中…"))
        running = true
        alive.set(true)
        TriggerEngine.log(this, "服务启动")
        worker = thread(name = "sb-trigger", isDaemon = true) { listenLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        alive.set(false)
        running = false
        try {
            eventProc?.destroy()
        } catch (_: Exception) {
        }
        TriggerEngine.log(this, "服务停止")
        super.onDestroy()
    }

    /* ---------------- 主循环 ---------------- */

    private fun listenLoop() {
        val dev = findVolumeDevice()
        if (dev == null) {
            TriggerEngine.log(this, "找不到音量键设备，退出")
            updateNotification("找不到音量设备")
            return
        }
        TriggerEngine.log(this, "监听 $dev")

        var cnt = 0
        var lastMs = 0L

        while (alive.get()) {
            val cfg = TriggerEngine.load(this)
            val p = startGetEvent(dev)
            if (p == null) {
                TriggerEngine.log(this, "getevent 启动失败")
                Thread.sleep(2000)
                continue
            }
            eventProc = p
            TriggerEngine.log(this, "getevent 已连接")
            try {
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line = reader.readLine()
                while (line != null && alive.get()) {
                    if (line.contains("KEY_VOLUMEUP") && line.contains("DOWN")) {
                        val now = System.currentTimeMillis()
                        val window = (cfg.intervalSec.coerceAtLeast(1)) * 1000L
                        cnt = if (cnt > 0 && now - lastMs <= window) cnt + 1 else 1
                        lastMs = now
                        TriggerEngine.log(this, "音量+ ×$cnt")
                        if (cnt >= cfg.pressCount.coerceAtLeast(1)) {
                            cnt = 0
                            onTriggered(cfg)
                        }
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                TriggerEngine.log(this, "读取事件异常: ${e.message}")
            } finally {
                try {
                    p.destroy()
                } catch (_: Exception) {
                }
            }
            if (alive.get()) Thread.sleep(800)
        }
    }

    private fun findVolumeDevice(): String? {
        val r = runRoot("for d in /dev/input/event*; do getevent -lp \$d 2>/dev/null | grep -q KEY_VOLUMEUP && echo \$d && break; done")
        return r.trim().lines().firstOrNull { it.startsWith("/dev/input/") }
    }

    private fun startGetEvent(dev: String): Process? = try {
        val b = ProcessBuilder("su", "-c", "getevent -l -q $dev")
        b.redirectErrorStream(true)
        b.start()
    } catch (e: Exception) {
        TriggerEngine.log(this, "启动 getevent 失败: ${e.message}")
        null
    }

    /* ---------------- 触发动作 ---------------- */

    private fun onTriggered(cfg: TriggerEngine.Config) {
        try {
            val fg = foregroundPackage()
            TriggerEngine.log(this, "触发！前台=$fg 目标=${cfg.appPkg.ifBlank { "(未限制)" }}")
            if (cfg.appPkg.isNotBlank() && !fg.equals(cfg.appPkg, ignoreCase = true)) {
                TriggerEngine.log(this, "前台不匹配，跳过")
                return
            }

            if (cfg.scriptPath.isNotBlank()) {
                val out = runRoot("sh '${cfg.scriptPath.replace("'", "'\\''")}' < /dev/null 2>&1")
                TriggerEngine.log(this, "脚本输出: ${out.take(300)}")
            }

            cfg.inputText.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) {
                    runRoot("input text '${line.replace(" ", "%s").replace("'", "")}'")
                    runRoot("input keyevent 66")
                }
            }

            repeat(cfg.pressCount) { runRoot("input keyevent 25") }
            updateNotification("已触发 · 前台 ${fg.substringAfterLast('.')}")
        } catch (e: Exception) {
            TriggerEngine.log(this, "触发异常: ${e.message}")
        }
    }

    private fun foregroundPackage(): String {
        val r = runRoot("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        val m = Regex("u0 ([A-Za-z0-9_.]+)/").find(r)
        if (m != null) return m.groupValues[1]
        val r2 = runRoot("dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'")
        val m2 = Regex("u0 ([A-Za-z0-9_.]+)/").find(r2)
        return m2?.groupValues?.get(1) ?: ""
    }

    private fun runRoot(cmd: String): String = try {
        val r = Shell.cmd(cmd).exec()
        r.out.joinToString("\n").trim()
    } catch (e: Exception) {
        ""
    }

    /* ---------------- 通知 ---------------- */

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "自动触发", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("sb 自动触发")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }
}
