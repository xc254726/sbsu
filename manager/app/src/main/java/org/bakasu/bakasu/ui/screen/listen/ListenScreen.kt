package org.bakasu.bakasu.ui.screen.listen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.R
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.ui.component.SwipeableSnackbarHost
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.component.settings.SegmentedColumn
import org.bakasu.bakasu.ui.component.settings.SettingsBaseWidget
import org.bakasu.bakasu.ui.component.settings.SettingsJumpPageWidget
import org.bakasu.bakasu.ui.component.settings.SettingsSwitchWidget
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.theme.CardConfig
import org.bakasu.bakasu.ui.theme.ThemeConfig
import org.bakasu.bakasu.ui.theme.blurEffect
import org.bakasu.bakasu.ui.theme.blurSource
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.adaptiveScaffoldWindowInsets
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.bakasu.bakasu.ui.webui.WebUIActivity
import org.koin.compose.koinInject

/**
 * 「监听」页：控制 sb-autotype 模块的常驻守护进程。
 *
 * 规则：
 *  - 打开这个页面 => 如果没被暂停，就确保守护进程在跑
 *  - 开关关掉 => 写暂停标记并杀掉进程，重启后也不会自动启动
 *  - 开关打开 => 清掉暂停标记并立即启动，重启后由 service.sh 自动拉起
 */
private const val MODULE_ID = "sb-autotype"

private const val STATUS_SCRIPT = """
[ -d /data/adb/modules/sb-autotype ] && echo MOD=1 || echo MOD=0
[ -f /data/adb/sb-autotype.paused ] && echo PAUSED=1 || echo PAUSED=0
echo PID=`pgrep -f 'sb-autotype/[d]aemon\.sh' 2>/dev/null | head -n1`
echo RULES=`grep -cv -e '^[[:space:]]*#' -e '^[[:space:]]*$' /data/adb/modules/sb-autotype/rules.conf 2>/dev/null`
"""

private const val START_SCRIPT = """
rm -f /data/adb/sb-autotype.paused
rm -f /data/adb/modules/sb-autotype/disable
sh /data/adb/modules/sb-autotype/start.sh >/dev/null 2>&1
echo STARTED
"""

private const val PAUSE_SCRIPT = """
touch /data/adb/sb-autotype.paused
pkill -f 'sb-autotype/[d]aemon\.sh' 2>/dev/null
rm -rf /data/adb/sb-autotype.lock
echo PAUSED
"""

private data class ListenStatus(
    val installed: Boolean = false,
    val paused: Boolean = false,
    val running: Boolean = false,
    val rules: Int = 0,
)

private fun runRootShell(ksuCli: KsuCliRepository, script: String): String {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    runCatching {
        ksuCli.getRootShell().newJob().add(script).to(out, err).exec()
    }
    return out.joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListenScreen() {
    val themeConfig: ThemeConfig = koinInject()
    val cardConfig: CardConfig = koinInject()
    val ksuCli: KsuCliRepository = koinInject()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var status by remember { mutableStateOf(ListenStatus()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val msgStart = stringResource(R.string.listen_started)
    val msgPaused = stringResource(R.string.listen_paused_toast)

    suspend fun refresh() {
        val text = withContext(Dispatchers.IO) { runRootShell(ksuCli, STATUS_SCRIPT) }
        status = ListenStatus(
            installed = text.contains("MOD=1"),
            paused = text.contains("PAUSED=1"),
            running = text.lineSequence()
                .firstOrNull { it.startsWith("PID=") }
                ?.removePrefix("PID=")
                ?.trim()
                ?.isNotEmpty() == true,
            rules = text.lineSequence()
                .firstOrNull { it.startsWith("RULES=") }
                ?.removePrefix("RULES=")
                ?.trim()
                ?.toIntOrNull() ?: 0,
        )
        loaded = true
    }

    suspend fun startListening() {
        busy = true
        withContext(Dispatchers.IO) { runRootShell(ksuCli, START_SCRIPT) }
        delay(600)
        refresh()
        busy = false
        snackBarHost.showReplacingSnackbar(msgStart)
    }

    suspend fun pauseListening() {
        busy = true
        withContext(Dispatchers.IO) { runRootShell(ksuCli, PAUSE_SCRIPT) }
        delay(400)
        refresh()
        busy = false
        snackBarHost.showReplacingSnackbar(msgPaused)
    }

    // 点开这个页面就算「启动」：没暂停过就确保守护进程在跑；
    // 之后一直盯着，掉线就自动拉起来 —— 只有用户按下暂停才停。
    LaunchedEffect(Unit) {
        refresh()
        if (status.installed && !status.paused && !status.running) {
            withContext(Dispatchers.IO) { runRootShell(ksuCli, START_SCRIPT) }
            delay(500)
            refresh()
        }
        var healLeft = 5
        while (true) {
            delay(5000)
            refresh()
            if (!busy && status.installed && !status.paused && !status.running) {
                if (healLeft > 0) {
                    healLeft--
                    withContext(Dispatchers.IO) { runRootShell(ksuCli, START_SCRIPT) }
                    delay(400)
                    refresh()
                    if (status.running) healLeft = 5
                }
            } else if (status.running) {
                healLeft = 5
            }
        }
    }

    val statusText = when {
        !status.installed -> stringResource(R.string.listen_not_installed)
        busy -> stringResource(R.string.listen_working)
        status.paused -> stringResource(R.string.listen_paused)
        status.running -> stringResource(R.string.listen_running)
        else -> stringResource(R.string.listen_idle)
    }

    Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.blurEffect(),
                title = { Text(stringResource(R.string.listen_title)) },
                navigationIcon = { AppBackButton(onClick = { navigator.pop() }) },
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor =
                        if (themeConfig.isEnableBlur) {
                            Color.Transparent
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(cardConfig.cardAlpha)
                        },
                    scrolledContainerColor =
                        if (themeConfig.isEnableBlur) {
                            Color.Transparent
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(cardConfig.cardAlpha)
                        },
                ),
            )
        },
        snackbarHost = { SwipeableSnackbarHost(hostState = snackBarHost) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .blurSource(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 5.dp,
                bottom = innerPadding.calculateBottomPadding() + 15.dp,
            ),
        ) {
            item {
                SegmentedColumn(title = stringResource(R.string.listen_title)) {
                    item {
                        SettingsSwitchWidget(
                            icon = Icons.TwoTone.Tune,
                            title = stringResource(R.string.listen_title),
                            description = if (loaded) statusText else stringResource(R.string.listen_loading),
                            enabled = status.installed && !busy,
                            checked = status.installed && !status.paused,
                            onCheckedChange = { on ->
                                scope.launch {
                                    if (on) startListening() else pauseListening()
                                }
                            },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.listen_rule_group)) {
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Extension,
                            title = stringResource(R.string.listen_rules_title),
                            description = stringResource(R.string.listen_rules_summary, status.rules),
                            enabled = status.installed,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(context, WebUIActivity::class.java).setData(
                                            Uri.Builder()
                                                .scheme("kernelsu")
                                                .authority("webui")
                                                .appendQueryParameter("id", MODULE_ID)
                                                .build(),
                                        ),
                                    )
                                }.onFailure {
                                    scope.launch {
                                        snackBarHost.showReplacingSnackbar("WebUI: ${it.message}")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.listen_about_group)) {
                    item {
                        SettingsBaseWidget(
                            icon = Icons.TwoTone.Tune,
                            title = stringResource(R.string.listen_boot_title),
                            description = stringResource(R.string.listen_boot_summary),
                        )
                    }
                }
            }
        }
    }
}
