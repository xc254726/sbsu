package org.bakasu.bakasu.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.bakasu.bakasu.data.dial.DialGuardService
import org.bakasu.bakasu.data.dial.DialScriptStore

/**
 * 开机把拨号守护的前台服务拉起来。
 *
 * 前台服务不会自己跨重启存活，所以每次开机补一次。只有「后台守护」开着时才动，
 * 关掉的话就什么都不做，不打扰用户。
 */
class DialBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val config = DialScriptStore(context.applicationContext).read()
        if (!config.keepAlive) return
        DialGuardService.start(context.applicationContext)
        Log.i(TAG, "开机拉起拨号守护")
    }

    private companion object {
        private const val TAG = "sb-dial"
    }
}
