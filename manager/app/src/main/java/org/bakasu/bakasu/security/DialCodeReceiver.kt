package org.bakasu.bakasu.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.telecom.TelecomManager
import android.util.Log
import org.bakasu.bakasu.data.dial.DialScriptRunner
import org.bakasu.bakasu.data.dial.DialScriptStore

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

        Log.i(TAG, "密令匹配，开始执行脚本")
        // run() 拉起进程就返回，不做任何等待：前台广播的执行窗口只有十几秒
        val error = DialScriptRunner.run(app, config, trigger = dialed)
        if (error != null) Log.w(TAG, "执行失败：$error")
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
    }
}
