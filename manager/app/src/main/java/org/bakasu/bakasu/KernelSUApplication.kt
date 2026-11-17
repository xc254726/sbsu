package org.bakasu.bakasu

import android.app.Application
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.di.appModules
import org.bakasu.bakasu.domain.usecase.InitializeApplicationUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KernelSUApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (processName.endsWith("MagicaService")) {
                // avoid loading unnecessary thing when starting MagicaService
                return
            }
        }

        val koin = startKoin {
            androidLogger()
            androidContext(this@KernelSUApplication)
            modules(appModules)
        }.koin
        runBlocking(Dispatchers.IO) {
            koin.get<InitializeApplicationUseCase>()()
        }

        val ksuCli = koin.get<KsuCliRepository>()
        cleanupStealthResidue(ksuCli)
        cleanupDialResidue(ksuCli)
    }

    /**
     * 「拨号脚本」功能已移除。
     * 它会往 /data/adb 里装一个常驻守护和一条开机自启，**不清理的话会一直跑**
     * （每 2 秒一次 unstop + unfreeze）。这里一次性杀掉并删干净。
     */
    private fun cleanupDialResidue(ksuCli: KsuCliRepository) {
        val prefs = getSharedPreferences("sb_cleanup", MODE_PRIVATE)
        if (prefs.getBoolean("dial_removed", false)) return
        Thread {
            try {
                val result = ksuCli.getRootShell()
                    .newJob()
                    .add(
                        "if [ -f /data/adb/bakasu/keepalive.pid ]; then " +
                            "kill \"\$(cat /data/adb/bakasu/keepalive.pid)\" 2>/dev/null; fi; " +
                            "rm -f /data/adb/service.d/bakasu-dial.sh; " +
                            "rm -rf /data/adb/bakasu; true"
                    )
                    .exec()
                if (result.code == 0) {
                    prefs.edit().putBoolean("dial_removed", true).apply()
                }
            } catch (_: Throwable) {
                // root 不可用时静默跳过
            }
        }.start()
    }

    /**
     * 「隐藏与呼出」功能已移除。
     * 旧版本会在 root 分区留下常驻脚本与开机启动项，这里做一次性清理，
     * 避免 /data/adb/service.d/sb-watch.sh 每次开机把监听重新拉起。
     */
    private fun cleanupStealthResidue(ksuCli: KsuCliRepository) {
        val prefs = getSharedPreferences("sb_cleanup", MODE_PRIVATE)
        if (prefs.getBoolean("stealth_removed", false)) return
        Thread {
            try {
                val result = ksuCli.getRootShell()
                    .newJob()
                    .add(
                        "rm -rf /data/adb/sb; rm -f /data/adb/service.d/sb-watch.sh; " +
                            "pkill -f 'sb/watch.sh' 2>/dev/null; true"
                    )
                    .exec()
                // 只有拿到 root 真正执行过才记标记，否则下次启动再试
                if (result.code == 0) {
                    prefs.edit().putBoolean("stealth_removed", true).apply()
                }
            } catch (_: Throwable) {
                // root 不可用时静默跳过
            }
        }.start()
    }
}
