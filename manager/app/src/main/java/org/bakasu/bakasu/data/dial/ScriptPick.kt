package org.bakasu.bakasu.data.dial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「从隐藏终端选脚本」的一次性结果。
 *
 * 导航参数是 @Serializable 的 Route，塞不了回调；文件管理器选完把路径放这里，
 * 拨号脚本页观察这个状态取走 —— 取走即清空，避免下次误用。
 *
 * 必须是 Compose 的 state，不能用普通字段：Compose 导航是在同一个 Activity 里
 * 换 composable，pop 回来时 Activity 一直处于 RESUMED，`ON_RESUME` 不会再来一次，
 * 靠生命周期回调取值的写法会永远收不到。
 */
object ScriptPick {
    var path: String? by mutableStateOf(null)
        private set

    fun post(value: String) {
        path = value
    }

    fun take(): String? {
        val picked = path
        path = null
        return picked
    }
}
