package org.bakasu.bakasu.data.dial

import android.content.Context

/**
 * 拨号触发脚本的配置。
 *
 * 「多个密令对一份动作」：任意一个密令拨出去都执行同一份配置。
 * 动作有两种写法，填一处就行：
 * - [scriptPath] 指向磁盘上的 .sh 文件；
 * - [scriptBody] 直接把命令写在配置里，不依赖外部文件。
 * 两个都填时以 [scriptBody] 为准。
 */
data class DialScriptConfig(
    val enabled: Boolean = false,
    val scriptPath: String = "",
    /** 直接执行的脚本内容，支持多行、支持注释。 */
    val scriptBody: String = "",
    /** 自动输入：一行一条，按顺序喂给脚本的 stdin。 */
    val autoInput: String = "",
    val codes: List<String> = listOf(DEFAULT_CODE),
) {
    /**
     * 按行拆开，**保留空行** —— 有些脚本是「按回车继续」，空行本身就是要喂的内容。
     */
    val inputLines: List<String>
        get() = if (autoInput.isEmpty()) emptyList() else autoInput.split('\n')

    /** 有没有可执行的东西：两处都留空就不该触发。 */
    val canRun: Boolean
        get() = scriptBody.isNotBlank() || scriptPath.isNotBlank()

    /** 日志抬头里显示的内容，一眼能看出这次跑的是什么。 */
    val targetLabel: String
        get() = if (scriptBody.isNotBlank()) "内嵌脚本" else scriptPath

    companion object {
        const val DEFAULT_CODE = "70707"
    }
}

/**
 * 配置存储。
 *
 * 单独一个 prefs 文件，不塞进 SettingsRepository：这几项只有这个功能在用，
 * 混进去会让那边的接口膨胀一大圈。
 */
class DialScriptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): DialScriptConfig {
        val codes = normalizeCodes(prefs.getString(KEY_CODES, DialScriptConfig.DEFAULT_CODE) ?: "")
        return DialScriptConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            scriptPath = prefs.getString(KEY_SCRIPT, "") ?: "",
            scriptBody = prefs.getString(KEY_BODY, "") ?: "",
            autoInput = prefs.getString(KEY_INPUT, "") ?: "",
            codes = codes.ifEmpty { listOf(DialScriptConfig.DEFAULT_CODE) },
        )
    }

    fun write(config: DialScriptConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SCRIPT, config.scriptPath)
            .putString(KEY_BODY, config.scriptBody)
            .putString(KEY_INPUT, config.autoInput)
            .putString(KEY_CODES, config.codes.joinToString(","))
            .apply()
    }

    companion object {
        private const val NAME = "dial_script"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCRIPT = "script_path"
        private const val KEY_BODY = "script_body"
        private const val KEY_INPUT = "auto_input"
        private const val KEY_CODES = "codes"

        /** 密令最长 12 位，和系统拨号密码的长度上限对齐 */
        private const val MAX_CODE_LENGTH = 12

        /**
         * 把用户输入的密令文本整理成干净的数字列表。
         *
         * 允许用逗号、分号、空格、换行分隔；中英文标点都认，粘进来的杂字符直接丢掉。
         */
        fun normalizeCodes(raw: String): List<String> =
            raw.split(',', '，', ';', '；', '、', ' ', '\n', '\t', '\r')
                .map { part -> part.filter { it.isDigit() }.take(MAX_CODE_LENGTH) }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
