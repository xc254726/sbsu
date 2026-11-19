package org.bakasu.bakasu.data.settings

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 「隐藏 SELinux」在内核侧只能挂在开机流程里：运行期间打开会被内核拒绝（-EAGAIN，
 * 因为备份策略在开机完成后就被释放了），但管理器仍会把这个 1 落进 ksud 配置，
 * 靠下次开机由 ksu_feature_apply_forced() 应用。
 *
 * 这里把「已排队、待重启」记下来并绑定当前 boot_id，设置页据此如实提示，
 * 而不是让用户以为开关已经生效。设备重启后 boot_id 变化，标记自动失效。
 */
object SelinuxHidePendingStore {
    private const val PREFS = "sb_selinux_hide"
    private const val KEY_PENDING = "pending"
    private const val KEY_BOOT = "boot_id"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun bootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")

    /** 只有同一次开机内标记的才算待重启；boot_id 变了说明已经重启过。 */
    fun isPending(): Boolean {
        val store = prefs ?: return false
        if (!store.getBoolean(KEY_PENDING, false)) return false
        return store.getString(KEY_BOOT, "").orEmpty() == bootId()
    }

    fun markPending() {
        prefs?.edit()?.putBoolean(KEY_PENDING, true)?.putString(KEY_BOOT, bootId())?.apply()
    }

    fun clear() {
        prefs?.edit()?.putBoolean(KEY_PENDING, false)?.apply()
    }
}
