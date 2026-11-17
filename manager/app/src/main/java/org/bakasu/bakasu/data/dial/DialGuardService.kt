package org.bakasu.bakasu.data.dial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 保活服务：让进程一直在，拨号广播才有进程可投。
 *
 * ## 为什么非它不可
 * 从最近任务里划掉 App 后，ColorOS 用的是自家 o-stop 而不是系统 force-stop：
 *
 *     Force stopping com.sb.manager appid=10368 user=0: o-stop(40)
 *     Killing 25690:com.sb.manager (adj 400): stop com.sb.manager due to o-stop(40)
 *
 * 这一下会做两件事：给包打上 `stopped=true`，并且**按 cgroup 把它下面的进程全杀掉**。
 * 实测前台服务也挡不住这一刀（06:55:55 那次就是带着前台服务被杀掉的），所以本服务
 * 的定位是「把进程钉在内存里」，被杀之后由 root 守护在 2 秒内重新拉起 —— 也就是
 * 把「面具挂在后台」这个用户验证过能用的状态自动恢复出来。
 *
 * ## 两种启动方式
 * - App 自己在前台时：正常 `startForegroundService`，走前台服务（最稳，不会被随手回收）。
 * - root 守护用 `am start-service` 拉起时：进程刚被 o-stop 杀掉、App 处于后台，
 *   Android 12+ 不允许后台启动前台服务，`startForeground` 会抛
 *   `ForegroundServiceStartNotAllowedException`。这时就退化成普通后台服务，
 *   目的只是让进程存在、让清单里的 `DialCodeReceiver` 能收到广播。
 */
class DialGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteToForeground()
    }

    /** 试着升成前台服务；后台启动被系统拒绝时静默降级，绝不让进程因为异常退出 */
    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("拨号守护运行中")
            .setContentText("划掉界面后，拨 *#*#密令#*#* 仍会以 root 执行脚本")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        foreground = ok.isSuccess
        if (ok.isFailure) {
            Log.i(TAG, "后台启动，降级为普通服务（进程仍保活）")
        }
    }

    /** 被杀也请系统尽量把服务拉回来 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被 root 守护从后台拉起时，第一次 startForeground 会被拒；
        // 等用户下次打开 App，这里再试一次就能升成前台。
        if (!foreground) promoteToForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "拨号守护",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "sb-dial"
        private const val CHANNEL_ID = "dial_guard"
        private const val NOTIFICATION_ID = 0x5b01

        @Volatile
        private var foreground = false

        fun start(context: Context) {
            val intent = Intent(context, DialGuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DialGuardService::class.java)) }
        }
    }
}
