package org.bakasu.bakasu.data.dial

import java.util.ArrayList
import org.bakasu.bakasu.data.shell.KsuCliRepository

/**
 * 让 App 别被系统标成「已停止」，这样划掉后台之后拨号广播还能把进程拉起来。
 *
 * ## 为什么「第一次能跑，后面没反应」
 * 从最近任务里划掉 App 时，国内 ROM（realme / ColorOS 这套）往往会给包打上
 * 强制停止标记（`PackageUserState.stopped = true`）。**处于 stopped 状态的包收不到
 * 任何静态注册广播** —— 系统在投递时默认带 `FLAG_EXCLUDE_STOPPED_PACKAGES`，
 * 拨号盘发出的 `SECRET_CODE` 会被直接丢掉，接收器连一次都不会被调用。
 * 表现就是：划掉后台后拨一次还能跑（那时进程刚被杀、标志还没落），之后就彻底没反应。
 *
 * ## 怎么绕过去
 * App 自己没有任何 API 能清这个标志，但 shell / root 有：
 *
 *     cmd package unstop <包名> --user 0
 *
 * 所以在 `/data/adb/bakasu` 里放一个极小的守护脚本，每隔十几秒把这个标志清一次。
 * 守护进程用 `setsid` 起，脱离 App 的会话和进程组，App 被杀、后台被划掉它都还在。
 *
 * 顺带把它加进 Doze 白名单，省得系统在深度睡眠时把广播再压一层。
 */
object DialKeepAlive {

    const val SCRIPT_DIR = DialScriptRunner.LOG_DIR
    const val SCRIPT_PATH = "$SCRIPT_DIR/keepalive.sh"

    /** 轮询间隔（秒）。太长则划掉后台之后要等一会儿才生效，太短则白耗电 */
    private const val INTERVAL = 15

    private const val MARKER = "bakasu/keepalive.sh"

    /**
     * 守护在跑吗。
     *
     * `pgrep -f` 会把命令行里带这个路径的进程都算上 —— 正好只有守护自己，
     * 因为其它命令里出现的都是带引号的同一个字符串，不构成独立进程。
     */
    fun isRunning(ksuCli: KsuCliRepository): Boolean =
        exec(ksuCli, "pgrep -f $MARKER >/dev/null 2>&1 && echo sb-running || echo sb-idle")
            .any { it.trim() == "sb-running" }

    /** 起守护；已经在跑就直接返回。返回 null 表示成功。 */
    fun start(ksuCli: KsuCliRepository): String? = runCatching {
        exec(ksuCli, buildStartCommand())
        null
    }.getOrElse { error ->
        "启动守护失败：${error.message ?: error.javaClass.simpleName}"
    }

    /** 停守护。返回 null 表示成功。 */
    fun stop(ksuCli: KsuCliRepository): String? = runCatching {
        exec(ksuCli, "pkill -f $MARKER >/dev/null 2>&1; true")
        null
    }.getOrElse { error ->
        "停止守护失败：${error.message ?: error.javaClass.simpleName}"
    }

    /**
     * 守护脚本 + 白名单，一次做完。
     *
     * 脚本用 heredoc 写，内容里不出现单引号，避免和 shell 引号打架。
     *
     * 循环里先 `grep` 系统那份 package-restrictions.xml 判断有没有被停用，
     * 只有真的被停用了才去调 `cmd package unstop` —— `cmd` 每次都要拉起一个
     * app_process，十几秒跑一次太浪费，而 grep 一个小文件几乎不耗电。
     */
    private fun buildStartCommand(): String = buildString {
        append("mkdir -p '").append(SCRIPT_DIR).append("'\n")
        append("cat > '").append(SCRIPT_PATH).append("' <<'SB_KEEPALIVE'\n")
        append("#!/system/bin/sh\n")
        append("# 只为把包从「已停止」状态里拉回来：stopped 的包收不到静态广播\n")
        append("PKG=").append(PACKAGE).append('\n')
        append("XML=/data/system/users/0/package-restrictions.xml\n")
        append("while true; do\n")
        append("  if grep -F -q \"\$PKG\" \"\$XML\" 2>/dev/null; then\n")
        append("    cmd package unstop \"\$PKG\" --user 0 >/dev/null 2>&1\n")
        append("  fi\n")
        append("  sleep ").append(INTERVAL).append('\n')
        append("done\n")
        append("SB_KEEPALIVE\n")
        append("chmod 755 '").append(SCRIPT_PATH).append("'\n")
        // 顺手进 Doze 白名单，避免深度睡眠时广播被再压一层
        append("dumpsys deviceidle whitelist +").append(PACKAGE).append(" >/dev/null 2>&1\n")
        // 已经在跑就不再起第二个（两个循环会互相叠加，纯粹浪费电）
        append("pgrep -f ").append(MARKER).append(" >/dev/null 2>&1 || ")
        append("setsid sh '").append(SCRIPT_PATH).append("' </dev/null >/dev/null 2>&1 &\n")
    }

    private fun exec(ksuCli: KsuCliRepository, command: String): List<String> {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        ksuCli.getRootShell().newJob().add(command).to(out, err).exec()
        return out
    }

    private const val PACKAGE = "com.sb.manager"
}
