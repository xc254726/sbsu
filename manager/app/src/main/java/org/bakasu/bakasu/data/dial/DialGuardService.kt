package org.bakasu.bakasu.data.dial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 把进程钉住的前台服务，让拨号广播永远有进程可投。
 *
 * ## 为什么非它不可
 * 从最近任务里划掉 App 后，ColorOS 用的是自家 o-stop 而不是系统 force-stop：
 *
 *     Force stopping com.sb.manager appid=10368 user=0: o-stop(40)
 *     Killing 25690:com.sb.manager (adj 400): stop com.sb.manager due to o-stop(40)
 *
 * 这一下会做两件事：给包打上 `stopped=true`，并且**按 cgroup 把它下面的进程全杀掉**。
 * stopped 的包收不到任何静态广播，所以拨号盘的 `SECRET_CODE` 被直接丢弃，接收器
 * 一次都进不来 —— 这正是「只有把面具挂在后台时拨号才管用」的原因。
 *
 * 之前试过在 root 侧起守护脚本定时把 stopped 清掉，但那个守护挂在 App 的 cgroup 里，
 * 会被 o-stop 连坐杀掉；改成开机脚本起、脱离 cgroup 又受 SELinux 限制干不了活。
 * 绕了一圈，最稳的是**根本不让它被强停**：前台服务是系统认可的保活方式，
 * ColorOS 划掉任务时也会把它留在「后台运行中」。
 *
 * 代价是一条常驻通知（优先度最低，不响不亮）。
 */
class DialGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("拨号守护运行中")
            .setContentText("划掉界面后，拨 *#*#密令#*#* 仍会以 root 执行脚本")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
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

    /** 被杀也请系统尽量把服务拉回来 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        private const val CHANNEL_ID = "dial_guard"
        private const val NOTIFICATION_ID = 0x5b01

        fun start(context: Context) {
            val intent = Intent(context, DialGuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DialGuardService::class.java)) }
        }
    }
}
