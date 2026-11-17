package org.bakasu.bakasu.data.dial

import java.util.ArrayList
import org.bakasu.bakasu.data.shell.KsuCliRepository

/**
 * 让包永远不处于「已停止」状态，这样划掉后台、甚至重启之后，拨号广播都能把进程拉起来。
 *
 * ## 为什么「第一次能跑，后面没反应」
 * 从最近任务里划掉 App 时，国内 ROM（realme / ColorOS 这套）往往会给包打上
 * 强制停止标记（`PackageUserState.stopped = true`）。**处于 stopped 状态的包收不到
 * 任何静态注册广播** —— 系统在投递时默认带 `FLAG_EXCLUDE_STOPPED_PACKAGES`，
 * 拨号盘发出的 `SECRET_CODE` 会被直接丢掉，接收器连一次都不会被调用。
 *
 * 更麻烦的是这个标记会**持久化到磁盘**（`package-restrictions.xml`），
 * 重启之后照样生效 —— 所以「重启后拨号没反应」和「划掉后台后没反应」是同一个原因。
 *
 * ## 怎么绕过去
 * App 自己没有任何 API 能清这个标志，但 shell / root 有：
 *
 *     cmd package unstop <包名> --user 0
 *
 * 于是放两个东西在 root 侧：
 *
 * 1. `/data/adb/bakasu/keepalive.sh` —— 每 2 秒看一眼是不是被停用了，是就清一次；
 * 2. `/data/adb/service.d/bakasu-dial.sh` —— 开机时由 KernelSU 以 root 执行，
 *    把上面那个守护重新拉起来（否则重启后守护自己也死了，等于没装）。
 *
 * 守护用 `setsid` 起，脱离 App 的会话和进程组，App 被杀、后台被划掉它都还在；
 * 开机那份由 ksud 直接起，不依赖 App。
 */
object DialKeepAlive {

    const val SCRIPT_DIR = DialScriptRunner.LOG_DIR
    const val SCRIPT_PATH = "$SCRIPT_DIR/keepalive.sh"

    /** KernelSU / Magisk 通用的开机脚本目录，late_start service 阶段以 root 执行 */
    private const val SERVICE_D_DIR = "/data/adb/service.d"
    private const val SERVICE_D_PATH = "$SERVICE_D_DIR/bakasu-dial.sh"

    /** 轮询间隔（秒）。2 秒是可靠性优先：划掉后台后最多等 2 秒；读的是一个小文件，开销可以接受 */
    private const val INTERVAL = 2

    private const val MARKER = "bakasu/keepalive.sh"

    /** 守护的真实状态：在不在跑、开机自启装了没。两者都成立才算「重启后也管用」。 */
    data class Status(val running: Boolean, val autostart: Boolean)

    /**
     * 查一次状态。
     *
     * `pgrep -f` 会把命令行里带这个路径的进程都算上 —— 正好只有守护自己，
     * 因为其它命令里出现的都是带引号的同一个字符串，不构成独立进程。
     * 开机脚本单独看文件在不在：KSU 升级、用户清理 /data/adb 都可能把它弄丢，
     * 那时候守护还在跑、但重启后就没了，得能看出来。
     */
    fun status(ksuCli: KsuCliRepository): Status {
        val lines = exec(
            ksuCli,
            "pgrep -f $MARKER >/dev/null 2>&1 && echo sb-running || echo sb-idle; " +
                "[ -f '$SERVICE_D_PATH' ] && echo sb-auto || echo sb-noauto",
        ).map { it.trim() }
        return Status(
            running = lines.contains("sb-running"),
            autostart = lines.contains("sb-auto"),
        )
    }

    /** 起守护 + 装上开机自启；已经在跑就只补装开机脚本。返回 null 表示成功。 */
    fun start(ksuCli: KsuCliRepository): String? = runCatching {
        exec(ksuCli, buildStartCommand())
        null
    }.getOrElse { error ->
        "启动守护失败：${error.message ?: error.javaClass.simpleName}"
    }

    /** 停守护，并把开机自启一起摘掉。返回 null 表示成功。 */
    fun stop(ksuCli: KsuCliRepository): String? = runCatching {
        exec(ksuCli, "pkill -f $MARKER >/dev/null 2>&1; rm -f '$SERVICE_D_PATH'; true")
        null
    }.getOrElse { error ->
        "停止守护失败：${error.message ?: error.javaClass.simpleName}"
    }

    /**
     * 守护脚本 + 开机脚本 + 白名单，一次做完。
     *
     * 脚本正文走 heredoc，分隔符加了引号，所以写入时不展开变量，`$PKG` 这类引用会
     * 原样留在脚本里；外层的路径才用单引号包。
     *
     * 判断「是不是被停用了」用 `sed -n '/<stopped-packages/,/<\/stopped-packages>/p'`
     * 截出那一段再搜包名 —— 不能全文件搜，同一个 xml 的 `<enabled-components>` 段里
     * 也有包名，那样会永远为真，每 5 秒白跑一次 `cmd`（每次都要拉起一个 app_process）。
     */
    private fun buildStartCommand(): String = buildString {
        append("mkdir -p '").append(SCRIPT_DIR).append("' '").append(SERVICE_D_DIR).append("'\n")

        // ---- 常驻守护 ----
        append("cat > '").append(SCRIPT_PATH).append("' <<'SB_KEEPALIVE'\n")
        append("#!/system/bin/sh\n")
        // ksud 跑开机脚本时环境变量极简，PATH 里常常没有 /system/bin，sed / grep / setsid / cmd
        // 会全部找不到 —— 这是「开机自启装了却不生效」最常见的原因，必须显式补上
        append("PATH=/system/bin:/system/xbin:/sbin:/debug_ramdisk\n")
        append("export PATH\n")
        append("PKG=").append(PACKAGE).append('\n')
        append("XML=/data/system/users/0/package-restrictions.xml\n")
        append("LOG=").append(SCRIPT_DIR).append("/keepalive.log\n")
        // 诊断用：放到 /data/local/tmp，adb shell 也能读，出问题不用猜
        append("DBG=/data/local/tmp/bakasu-daemon.log\n")
        append("say() { printf '[%s] %s\\n' \"\$(date '+%Y-%m-%d %H:%M:%S')\" \"\$1\" >> \"\$DBG\" 2>/dev/null; }\n")
        append("say \"started pid=\$\$ cgroup=\$(cat /proc/self/cgroup 2>/dev/null | tr '\\n' ' ')\"\n")
        // ColorOS 划掉 App 用的是自家 o-stop，按 cgroup 杀进程；守护若还挂在 App 的 cgroup 里
        // 会被连坐。这里尽量把自家人挪到系统组，被 SELinux 拦住就记一笔继续跑。
        append("for cg in /sys/fs/cgroup/system /sys/fs/cgroup/system-background; do\n")
        append("  if [ -w \"\$cg/cgroup.procs\" ] && echo \$\$ > \"\$cg/cgroup.procs\" 2>/dev/null; then\n")
        append("    say \"moved to \$cg\"; break\n")
        append("  fi\n")
        append("done\n")
        append("stopped_now() {\n")
        append("  [ -f \"\$XML\" ] || return 1\n")
        append("  sed -n '/<stopped-packages/,/<\\/stopped-packages>/p' \"\$XML\" 2>/dev/null | grep -F -q \"\\\"\$PKG\\\"\"\n")
        append("}\n")
        // 循环体第一轮立刻执行，所以「刚开机就拨号」不会撞在窗口里
        append("while true; do\n")
        append("  if stopped_now; then\n")
        append("    cmd package unstop \"\$PKG\" --user 0 >/dev/null 2>&1\n")
        append("    say 'cleared stopped flag'\n")
        append("    printf '[%s] cleared stopped flag\\n' \"\$(date '+%Y-%m-%d %H:%M:%S')\" >> \"\$LOG\"\n")
        // 日志按 20KB 截一次，免得哪天一直清不掉把 /data 写满
        append("    if [ \"\$(wc -c < \"\$LOG\" 2>/dev/null || echo 0)\" -gt 20000 ]; then\n")
        append("      tail -n 100 \"\$LOG\" > \"\$LOG.tmp\" && mv \"\$LOG.tmp\" \"\$LOG\"\n")
        append("    fi\n")
        append("  fi\n")
        append("  sleep ").append(INTERVAL).append('\n')
        append("done\n")
        append("SB_KEEPALIVE\n")
        append("chmod 755 '").append(SCRIPT_PATH).append("'\n")
        append("chmod 666 /data/local/tmp/bakasu-daemon.log 2>/dev/null\n")

        // ---- 开机自启（KernelSU 的 service.d，不依赖 App 存活）----
        append("cat > '").append(SERVICE_D_PATH).append("' <<'SB_SERVICE'\n")
        append("#!/system/bin/sh\n")
        append("# 开机把拨号守护拉起来；正式脚本在 /data/adb/bakasu/keepalive.sh\n")
        append("PATH=/system/bin:/system/xbin:/sbin:/debug_ramdisk\n")
        append("export PATH\n")
        append("D=/data/local/tmp/bakasu-daemon.log\n")
        append("printf '[%s] service.d ran\\n' \"\$(date '+%Y-%m-%d %H:%M:%S')\" >> \"\$D\" 2>/dev/null\n")
        append("chmod 666 \"\$D\" 2>/dev/null\n")
        // 绝对路径，别赌 PATH
        append("/system/bin/setsid /system/bin/sh ").append(SCRIPT_PATH).append(" </dev/null >/dev/null 2>&1 &\n")
        append("printf '[%s] service.d spawned pid=%s\\n' \"\$(date '+%Y-%m-%d %H:%M:%S')\" \"\$!\" >> \"\$D\" 2>/dev/null\n")
        append("SB_SERVICE\n")
        append("chmod 755 '").append(SERVICE_D_PATH).append("'\n")

        // 顺手进 Doze 白名单，避免深度睡眠时广播被再压一层
        append("dumpsys deviceidle whitelist +").append(PACKAGE).append(" >/dev/null 2>&1\n")
        // 第二层：进程已死时系统要「冷启动」本包来接广播，这一步会被后台运行 / 待机分组拦下
        append("cmd appops set ").append(PACKAGE).append(" RUN_IN_BACKGROUND allow >/dev/null 2>&1\n")
        append("cmd appops set ").append(PACKAGE).append(" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n")
        append("cmd appops set ").append(PACKAGE).append(" START_FOREGROUND allow >/dev/null 2>&1\n")
        append("am set-standby-bucket ").append(PACKAGE).append(" active >/dev/null 2>&1\n")
        // 已经在跑就不再起第二个（两个循环会互相叠加，纯粹浪费电）
        append("pgrep -f ").append(MARKER).append(" >/dev/null 2>&1 || ")
        append("/system/bin/setsid /system/bin/sh '").append(SCRIPT_PATH).append("' </dev/null >/dev/null 2>&1 &\n")
    }

    private fun exec(ksuCli: KsuCliRepository, command: String): List<String> {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        ksuCli.getRootShell().newJob().add(command).to(out, err).exec()
        return out
    }

    private const val PACKAGE = "com.sb.manager"
}
