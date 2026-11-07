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
 * 逻辑：指定应用进入前台并持续 N 秒 → 执行脚本 → 输入文字
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
    private var watcher: Process? = null

    private var enterAt = 0L
    private var fired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildNotification("监听中…"))
        running = true
        alive.set(true)
        TriggerEngine.log(this, "服务启动")
        worker = thread(name = "sb-trigger", isDaemon = true) { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        alive.set(false)
        running = false
        try {
            watcher?.destroy()
        } catch (_: Exception) {
        }
        TriggerEngine.log(this, "服务停止")
        super.onDestroy()
    }

    /* ---------------- 主循环 ---------------- */

    private fun monitorLoop() {
        while (alive.get()) {
            val p = startWatcher()
            if (p == null) {
                TriggerEngine.log(this, "前台监听启动失败")
                Thread.sleep(2000)
                continue
            }
            watcher = p
            TriggerEngine.log(this, "开始监听前台应用")
            try {
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line = reader.readLine()
                while (line != null && alive.get()) {
                    val pkg = Regex("u0 ([A-Za-z0-9_.]+)/").find(line)?.groupValues?.get(1)
                    if (!pkg.isNullOrBlank()) handleForeground(pkg)
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                TriggerEngine.log(this, "监听异常: ${e.message}")
            } finally {
                try {
                    p.destroy()
                } catch (_: Exception) {
                }
            }
            if (alive.get()) Thread.sleep(1000)
        }
    }

    private fun startWatcher(): Process? = try {
        val b = ProcessBuilder(
            "su", "-c",
            "while true; do dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; sleep 1; done",
        )
        b.redirectErrorStream(true)
        b.start()
    } catch (e: Exception) {
        TriggerEngine.log(this, "启动失败: ${e.message}")
        null
    }

    private fun handleForeground(pkg: String) {
        val cfg = TriggerEngine.load(this)
        val target = cfg.appPkg
        if (target.isBlank()) return

        val now = System.currentTimeMillis()
        if (!pkg.equals(target, ignoreCase = true)) {
            if (enterAt != 0L) {
                TriggerEngine.log(this, "离开 $pkg")
            }
            enterAt = 0L
            fired = false
            return
        }

        if (enterAt == 0L) {
            enterAt = now
            fired = false
            TriggerEngine.log(this, "进入 $pkg，等待 ${cfg.waitSeconds} 秒")
            return
        }

        if (!fired && now - enterAt >= cfg.waitSeconds.coerceAtLeast(1) * 1000L) {
            fired = true
            TriggerEngine.log(this, "已等待 ${(now - enterAt) / 1000} 秒，执行脚本")
            onTriggered(cfg)
        }
    }

    /* ---------------- 触发动作 ---------------- */

    private fun onTriggered(cfg: TriggerEngine.Config) {
        try {
            if (cfg.scriptPath.isNotBlank()) {
                val out = runRoot("sh '${cfg.scriptPath.replace("'", "'\\''")}' < /dev/null 2>&1")
                TriggerEngine.log(this, "脚本输出: ${out.take(400)}")
            }
            cfg.inputText.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) {
                    runRoot("input text '${line.replace(" ", "%s").replace("'", "")}'")
                    runRoot("input keyevent 66")
                }
            }
            updateNotification("已执行 · ${pkgShort(cfg.appPkg)}")
        } catch (e: Exception) {
            TriggerEngine.log(this, "触发异常: ${e.message}")
        }
    }

    private fun pkgShort(p: String): String = p.substringAfterLast('.')

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
