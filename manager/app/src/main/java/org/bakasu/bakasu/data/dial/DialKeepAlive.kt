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

    private const val PID_PATH = "$SCRIPT_DIR/keepalive.pid"

    /** 拉活进程用的组件名：applicationId / 完整 Java 类名（两者不同，不能简写成 .data.dial.X） */
    private const val COMPONENT = "com.sb.manager/org.bakasu.bakasu.data.dial.DialGuardService"

    /** 守护的真实状态：在不在跑、开机自启装了没。两者都成立才算「重启后也管用」。 */
    data class Status(val running: Boolean, val autostart: Boolean)

    /**
     * 查一次状态。
     *
     * 靠 pid 文件 + `kill -0` 判断活着，不用 `pgrep -f`：按命令行匹配时，执行 pgrep 的
     * 那个 shell 自身命令行里也带着脚本路径，会永远匹配到自己，把状态骗成「运行中」。
     * 开机脚本单独看文件在不在：KSU 升级、用户清理 /data/adb 都可能把它弄丢，
     * 那时候守护还在跑、但重启后就没了，得能看出来。
     */
    fun status(ksuCli: KsuCliRepository): Status {
        val lines = exec(
            ksuCli,
            "if [ -f '$PID_PATH' ] && kill -0 \"\$(cat '$PID_PATH')\" 2>/dev/null; " +
                "then echo sb-running; else echo sb-idle; fi; " +
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
        exec(
            ksuCli,
            "if [ -f '$PID_PATH' ]; then kill \"\$(cat '$PID_PATH')\" >/dev/null 2>&1; " +
                "rm -f '$PID_PATH'; fi; rm -f '$SERVICE_D_PATH'; true",
        )
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
     * 守护循环**不判断状态，直接每 2 秒调一次 `cmd package unstop`**。早先的写法是先解析
     * `/data/system/users/0/package-restrictions.xml` 里 `<stopped-packages>` 那一段再决定
     * 要不要清——但那份文件在不同版本上的位置/格式都会变，解析不出来就永远不清，反而更脆。
     * 对没被停用的包，`unstop` 是空操作，实测一次约 0.02 秒，这点开销换来「一定生效」。
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
        // 诊断走 setprop：/data/local/tmp 被 SELinux 拒过，属性通道 adb 一定能读
        append("setprop debug.bakasu.boot \"pid=\$\$ at \$(date '+%H:%M:%S') cgroup=\$(cat /proc/self/cgroup 2>/dev/null | tr '\\n' ' ')\"\n")
        // 记 pid：重启/停止都按 pid 来，不靠 pkill -f 匹配 —— 执行命令的那个 shell
        // 命令行里本身就带着脚本路径，按名字杀会把自己一起干掉
        append("echo \$\$ > \"").append(PID_PATH).append("\"\n")
        // ColorOS 划掉 App 用的是自家 o-stop，按 cgroup 杀进程；守护若还挂在 App 的 cgroup 里
        // 会被连坐。这里尽量把自家人挪到系统组，被 SELinux 拦住就记一笔继续跑。
        append("for cg in /sys/fs/cgroup/system /sys/fs/cgroup/system-background; do\n")
        append("  if [ -w \"\$cg/cgroup.procs\" ] && echo \$\$ > \"\$cg/cgroup.procs\" 2>/dev/null; then\n")
        append("    say \"moved to \$cg\"; break\n")
        append("  fi\n")
        append("done\n")
        append("LPID=\n")
        // 每 5 轮（10 秒）重申一次解冻，兜底 --sticky 万一被厂商逻辑绕过去
        append("N=0\n")
        append("while true; do\n")
        // 不再依赖解析 package-restrictions.xml：那玩意儿的格式随版本会变，
        // 而且 root 侧不一定读得到。`cmd package unstop` 对没被停用的包是空操作，
        // 直接每 2 秒清一次最稳 —— 实测一次调用约 0.02 秒，代价可以忽略。
        append("  cmd package unstop \"\$PKG\" --user 0 >/dev/null 2>&1\n")
        append("  rc=\$?\n")
        append("  setprop debug.bakasu.daemon \"t=\$(date '+%H:%M:%S') rc=\$rc\"\n")
        // 进程被 o-stop 杀掉之后，光清 stopped 还不够：系统仍然不肯为一个"刚被划掉"的包
        // 冷启动进程来接广播。所以直接在 root 侧把它拉回来 —— 这就是用户验证过
        // 「挂着后台就能拨」的那个状态。am 用完整组件名：appId / Java 包名。
        append("  P=\$(pidof \"\$PKG\" 2>/dev/null | cut -d' ' -f1)\n")
        append("  if [ -z \"\$P\" ]; then\n")
        // 必须是 start-foreground-service：用 start-service 起的只是后台服务，拿不到前台身份，
        // ColorOS 会在几秒内把它速冻，广播依旧被跳过。实测前台服务方式 30 秒内不会被冻。
        append("    am start-foreground-service -n \"").append(COMPONENT).append("\" >/dev/null 2>&1\n")
        append("    setprop debug.bakasu.revive \"t=\$(date '+%H:%M:%S') rc=\$?\"\n")
        // 划掉后台时 ColorOS 不杀（有前台服务），而是把进程**冻住**：
        //   SKIPPED: DialCodeReceiver  reason: Skipping delivery ... frozen state
        // 冻结状态下广播直接被跳过，接收器一次都进不来。--sticky 表示「系统不要自动再冻」，
        // 实测执行后 isFrozen 一直保持 false。
        append("  elif [ \"\$P\" != \"\$LPID\" ] || [ \$N -ge 5 ]; then\n")
        append("    am unfreeze --sticky \"\$P\" >/dev/null 2>&1\n")
        append("    setprop debug.bakasu.unfreeze \"t=\$(date '+%H:%M:%S') pid=\$P rc=\$?\"\n")
        append("    LPID=\$P\n")
        append("    N=0\n")
        append("  fi\n")
        append("  N=\$((N+1))\n")
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
        // 先杀掉旧守护再起新的：脚本内容随版本变化，旧进程会一直跑老逻辑
        // （之前就吃过亏：磁盘上是新脚本，内存里跑的是开机时的老脚本，怎么改都没反应）
        append("if [ -f '").append(PID_PATH).append("' ]; then kill \"\$(cat '")
            .append(PID_PATH).append("')\" >/dev/null 2>&1; sleep 1; rm -f '")
            .append(PID_PATH).append("'; fi\n")
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
