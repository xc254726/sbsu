package org.bakasu.bakasu.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.bakasu.bakasu.data.dial.DialScriptRunner
import org.bakasu.bakasu.data.dial.DialScriptStore
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.koin.core.context.GlobalContext

/**
 * 拨号密令入口。
 *
 * 拨 `*#*#密令#*#*` 后系统会发一条 `SECRET_CODE` 广播，这里比对密令，
 * 命中就以 root 静默执行配置好的脚本。
 *
 * 清单里**只声明了 scheme、不写 host**，所以能收到所有密令；具体哪个密令有效
 * 由设置里的列表决定，匹配不上就静默忽略 —— 这样改密令不用改清单。
 */
class DialCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return

        // 🔒 先验发送方：SECRET_CODE 不是受保护广播，任何应用都能伪造一条
        if (!senderTrusted(context)) {
            Log.w(TAG, "密令广播来自非拨号盘发送方，已忽略")
            return
        }

        val dialed = intent.data?.host ?: return
        val app = context.applicationContext
        val config = DialScriptStore(app).read()
        if (!config.enabled) return
        if (dialed !in config.codes) {
            // 密令内容不进日志，只记长度
            Log.i(TAG, "收到未匹配的密令（内容已隐去，长度 ${dialed.length}）")
            return
        }

        // 实测一次拨号会来两条一模一样的广播（不同 subscriber），不去重就等于脚本被并发跑两遍
        val now = SystemClock.elapsedRealtime()
        synchronized(FIRE_LOCK) {
            if (dialed == lastCode && now - lastFireAt < DEDUPE_MS) {
                Log.i(TAG, "同一次拨号的重复广播，已忽略")
                return
            }
            lastCode = dialed
            lastFireAt = now
        }

        Log.i(TAG, "密令匹配，开始执行脚本")

        // 拿 root 要走授权流程，第一次可能等用户在弹窗里点允许；这里给 6 秒窗口，
        // 超时就先放广播走（前台广播超过 10 秒会 ANR），线程留着继续等
        val pending = goAsync()
        val ksuCli = runCatching { GlobalContext.get().get<KsuCliRepository>() }.getOrNull()
        if (ksuCli == null) {
            Log.w(TAG, "执行失败：Koin 还没初始化")
            pending.finish()
            return
        }

        val done = CountDownLatch(1)
        Thread({
            try {
                val error = DialScriptRunner.run(app, ksuCli, config, trigger = dialed)
                if (error != null) Log.w(TAG, "执行失败：$error")
            } catch (t: Throwable) {
                Log.w(TAG, "执行异常", t)
            } finally {
                done.countDown()
            }
        }, "sb-dial-run").start()

        runCatching { done.await(6, TimeUnit.SECONDS) }
        pending.finish()
    }

    /**
     * 发送方可信吗？只认「拨号盘那一路」。
     *
     * · 系统 / 电话进程 / shell / 自己 → 放行
     * · 其余 uid：必须能查出包身份，且是本机默认拨号器、或者是系统预装应用
     * · 查不出包身份的一律拒绝
     *
     * API < 34 拿不到发送方信息，退回放行 —— 宁可有这个已知缺口，也不能让正主拨不进来。
     */
    private fun senderTrusted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        var uid = -1
        var fromPackage: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            uid = runCatching { sentFromUid }.getOrDefault(-1)
            fromPackage = runCatching { sentFromPackage }.getOrNull()
        }
        if (uid < 0) return true
        // 1000=system，1001=phone，2000=shell，自己的 uid
        if (uid == 1000 || uid == 1001 || uid == 2000 || uid == Process.myUid()) return true

        val pkgs = LinkedHashSet<String>()
        fromPackage?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
        runCatching { context.packageManager.getPackagesForUid(uid) }
            .getOrNull()?.forEach { pkgs.add(it) }
        if (pkgs.isEmpty()) return false

        // ① 本机默认拨号器
        val dialer = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        if (dialer != null && pkgs.contains(dialer)) return true

        // ② 预装 / 系统应用（覆盖厂商自己的电话组件发广播的机型）
        return pkgs.any { pkg ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }
    }

    companion object {
        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
        private const val TAG = "sb-dial"

        /** 同一条密令在这段时间内只认第一次，后面的是系统重复投递 */
        private const val DEDUPE_MS = 3_000L

        private val FIRE_LOCK = Any()
        private var lastCode: String? = null
        private var lastFireAt = 0L
    }
}
