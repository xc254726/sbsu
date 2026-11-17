package org.bakasu.bakasu.data.dial

/**
 * 「从隐藏终端选脚本」的一次性结果。
 *
 * 导航参数是 @Serializable 的 Route，塞不了回调；文件管理器选完把路径放这里，
 * 拨号脚本页回到前台时取走 —— 取走即清空，避免下次误用。
 */
object ScriptPick {
    var path: String? = null

    fun take(): String? {
        val picked = path
        path = null
        return picked
    }
}
