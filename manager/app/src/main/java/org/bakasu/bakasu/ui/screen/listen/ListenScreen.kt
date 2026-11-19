package org.bakasu.bakasu.ui.screen.listen

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.R
import org.bakasu.bakasu.data.file.RootFileEntry
import org.bakasu.bakasu.data.file.RootFileRepository
import org.bakasu.bakasu.data.packageinfo.InstalledPackageRepository
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.domain.model.InstalledPackageInfo
import org.bakasu.bakasu.ui.component.ConfirmResult
import org.bakasu.bakasu.ui.component.PackageIcon
import org.bakasu.bakasu.ui.component.SwipeableSnackbarHost
import org.bakasu.bakasu.ui.component.rememberConfirmDialog
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
import org.koin.compose.koinInject

/**
 * 「监听」页：不依赖任何模块，脚本由面具自己落到 /data/adb。
 *
 *  - 首次进入或脚本版本变化时，把 daemon.sh / start.sh 写到 /data/adb/sb_autotype，
 *    并注册 /data/adb/service.d/sb-autotype.sh 负责开机拉起
 *  - 之后守护进程与面具无关：划掉、卸载面具，甚至重启都不影响
 *  - 开关关掉 = 写暂停标记 + 杀进程，重启也不会自启；打开 = 清标记 + 立即启动
 */
private const val RULE_DIR = "/data/adb/sb_autotype"
private const val RULES_FILE = "$RULE_DIR/rules.conf"
private const val BOOT_SCRIPT = "/data/adb/service.d/sb-autotype.sh"
private const val LEGACY_RULES = "/data/adb/modules/sb-autotype/rules.conf"

/** 改动 assets 里的脚本时把这个数字加一，下次进页面会自动覆盖更新 */
private const val BUNDLED_VERSION = "1"

private const val STATUS_SCRIPT = """
[ -d /data/adb/sb_autotype ] && echo DIR=1 || echo DIR=0
[ -f /data/adb/service.d/sb-autotype.sh ] && echo BOOT=1 || echo BOOT=0
[ -f /data/adb/sb-autotype.paused ] && echo PAUSED=1 || echo PAUSED=0
echo PID=`pgrep -f 'sb[-_]autotype/[d]aemon\.sh' 2>/dev/null | head -n1`
"""

private const val START_SCRIPT = """
rm -f /data/adb/sb-autotype.paused
rm -f /data/adb/sb_autotype/disable
[ -f /data/adb/sb_autotype/start.sh ] && sh /data/adb/sb_autotype/start.sh >/dev/null 2>&1
echo STARTED
"""

private const val PAUSE_SCRIPT = """
touch /data/adb/sb-autotype.paused
pkill -f 'sb[-_]autotype/[d]aemon\.sh' 2>/dev/null
rm -rf /data/adb/sb-autotype.lock
echo PAUSED
"""

private const val REMOVE_SCRIPT = """
pkill -f 'sb[-_]autotype/[d]aemon\.sh' 2>/dev/null
rm -f /data/adb/service.d/sb-autotype.sh
rm -f /data/adb/sb-autotype.paused
rm -rf /data/adb/sb_autotype
rm -rf /data/adb/modules/sb-autotype
rm -f /data/adb/sb-autotype.log /data/adb/sb-autotype.pid
rm -rf /data/adb/sb-autotype.state /data/adb/sb-autotype.lock
echo REMOVED
"""

private const val READ_VERSION_SCRIPT = "cat /data/adb/sb_autotype/version 2>/dev/null"

private enum class RuleDialogMode { Edit, Apps, Files }

private data class ListenRule(
    val pkg: String,
    val text: String,
    val delay: String,
    val script: String,
)

private data class ListenStatus(
    val installed: Boolean = false,
    val bootRegistered: Boolean = false,
    val paused: Boolean = false,
    val running: Boolean = false,
)

private fun runRootShell(ksuCli: KsuCliRepository, script: String): String {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    runCatching {
        ksuCli.getRootShell().newJob().add(script).to(out, err).exec()
    }
    return out.joinToString("\n")
}

private fun assetBase64(context: Context, name: String): String =
    Base64.encodeToString(
        context.assets.open("sbautotype/$name").use { it.readBytes() },
        Base64.NO_WRAP,
    )

/** 把内置脚本落到 /data/adb，并注册开机脚本 */
private fun installScripts(context: Context, ksuCli: KsuCliRepository): String {
    val script = buildString {
        append("mkdir -p $RULE_DIR /data/adb/service.d\n")
        listOf("daemon.sh", "start.sh").forEach { name ->
            append("echo '").append(assetBase64(context, name)).append("' | base64 -d > $RULE_DIR/$name\n")
            append("chmod 755 $RULE_DIR/$name\n")
        }
        append("echo '").append(assetBase64(context, "boot.sh")).append("' | base64 -d > $BOOT_SCRIPT\n")
        append("chmod 755 $BOOT_SCRIPT\n")
        append("echo '$BUNDLED_VERSION' > $RULE_DIR/version\n")
        append("[ -f $RULES_FILE ] || { [ -f $LEGACY_RULES ] && cp -f $LEGACY_RULES $RULES_FILE; }\n")
        append("[ -f $RULES_FILE ] || : > $RULES_FILE\n")
        append("chmod 644 $RULES_FILE\n")
        append("echo INSTALLED\n")
    }
    return runRootShell(ksuCli, script)
}

private fun parseRules(raw: String): List<ListenRule> = raw.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line ->
        val parts = line.split("|")
        ListenRule(
            pkg = parts.getOrElse(0) { "" }.trim(),
            text = parts.getOrElse(1) { "" },
            delay = parts.getOrElse(2) { "" }.trim(),
            script = parts.getOrElse(3) { "" }.trim(),
        )
    }
    .filter { it.pkg.isNotEmpty() }
    .toList()

private fun writeRules(ksuCli: KsuCliRepository, rules: List<ListenRule>): String {
    val body = buildString {
        append("# sb 监听规则（由面具写入，一行一条）\n")
        append("# 包名|输入内容|延迟毫秒|脚本路径\n")
        rules.forEach {
            append(it.pkg).append('|')
                .append(it.text).append('|')
                .append(it.delay).append('|')
                .append(it.script).append('\n')
        }
    }
    val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
    return runRootShell(
        ksuCli,
        "echo '$b64' | base64 -d > $RULES_FILE\nchmod 644 $RULES_FILE\necho OK",
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListenScreen() {
    val themeConfig: ThemeConfig = koinInject()
    val cardConfig: CardConfig = koinInject()
    val ksuCli: KsuCliRepository = koinInject()
    val installedPackages: InstalledPackageRepository = koinInject()
    val rootFiles = remember(ksuCli) { RootFileRepository(ksuCli) }
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val confirmDialog = rememberConfirmDialog()
    val packages by installedPackages.packages.collectAsStateWithLifecycle()

    var status by remember { mutableStateOf(ListenStatus()) }
    var rules by remember { mutableStateOf<List<ListenRule>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<ListenRule?>(null) }
    var draftIndex by remember { mutableStateOf(-1) }
    var dialogMode by remember { mutableStateOf(RuleDialogMode.Edit) }

    val msgStart = stringResource(R.string.listen_started)
    val msgPaused = stringResource(R.string.listen_paused_toast)
    val msgSaved = stringResource(R.string.listen_rule_saved)
    val msgRemoved = stringResource(R.string.listen_removed)
    val msgRuleDelete = stringResource(R.string.listen_rule_delete)
    val msgRemoveTitle = stringResource(R.string.listen_remove_title)
    val msgRemoveConfirm = stringResource(R.string.listen_remove_confirm)

    suspend fun refresh() {
        val text = withContext(Dispatchers.IO) { runRootShell(ksuCli, STATUS_SCRIPT) }
        status = ListenStatus(
            installed = text.contains("DIR=1"),
            bootRegistered = text.contains("BOOT=1"),
            paused = text.contains("PAUSED=1"),
            running = text.lineSequence()
                .firstOrNull { it.startsWith("PID=") }
                ?.removePrefix("PID=")
                ?.trim()
                ?.isNotEmpty() == true,
        )
        rules = parseRules(withContext(Dispatchers.IO) { runRootShell(ksuCli, "cat $RULES_FILE 2>/dev/null") })
        loaded = true
    }

    LaunchedEffect(Unit) {
        installedPackages.refresh()
        val version = withContext(Dispatchers.IO) { runRootShell(ksuCli, READ_VERSION_SCRIPT) }.trim()
        val bootOk = withContext(Dispatchers.IO) { runRootShell(ksuCli, STATUS_SCRIPT) }.contains("BOOT=1")
        if (version != BUNDLED_VERSION || !bootOk) {
            busy = true
            withContext(Dispatchers.IO) { installScripts(context, ksuCli) }
            busy = false
        }
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

    suspend fun saveRules(next: List<ListenRule>) {
        busy = true
        withContext(Dispatchers.IO) { writeRules(ksuCli, next) }
        rules = next
        busy = false
        snackBarHost.showReplacingSnackbar(msgSaved)
    }

    val statusText = when {
        !loaded -> stringResource(R.string.listen_loading)
        busy -> stringResource(R.string.listen_working)
        status.paused -> stringResource(R.string.listen_paused)
        status.running -> stringResource(R.string.listen_running)
        else -> stringResource(R.string.listen_idle)
    }

    val labelOf: (String) -> String = { pkg ->
        packages.firstOrNull { it.packageName == pkg }?.appLabel?.takeIf { it.isNotBlank() } ?: pkg
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
                            description = statusText,
                            enabled = loaded && !busy,
                            checked = status.installed && !status.paused,
                            onCheckedChange = { on ->
                                scope.launch {
                                    busy = true
                                    withContext(Dispatchers.IO) {
                                        runRootShell(ksuCli, if (on) START_SCRIPT else PAUSE_SCRIPT)
                                    }
                                    delay(if (on) 600 else 400)
                                    refresh()
                                    busy = false
                                    snackBarHost.showReplacingSnackbar(if (on) msgStart else msgPaused)
                                }
                            },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.listen_rule_group)) {
                    if (rules.isEmpty()) {
                        item {
                            SettingsBaseWidget(
                                icon = null,
                                iconPlaceholder = false,
                                title = stringResource(R.string.listen_rules_empty),
                                description = stringResource(R.string.listen_rules_empty_summary),
                            )
                        }
                    }
                    rules.forEachIndexed { index, rule ->
                        item {
                            SettingsBaseWidget(
                                leadingContent = {
                                    PackageIcon(
                                        packageName = rule.pkg,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                },
                                title = labelOf(rule.pkg),
                                description = buildString {
                                    append(
                                        if (rule.text.isNotBlank()) {
                                            stringResource(R.string.listen_rule_input, rule.text)
                                        } else {
                                            stringResource(R.string.listen_rule_input_empty)
                                        },
                                    )
                                    append(" · ")
                                    append(rule.delay.ifBlank { "1000" })
                                    append("ms")
                                    if (rule.script.isNotBlank()) {
                                        append("\n")
                                        append(rule.script)
                                    }
                                },
                                onClick = {
                                    draftIndex = index
                                    draft = rule
                                },
                                foreContent = {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val result = confirmDialog.awaitConfirm(
                                                    title = msgRuleDelete,
                                                    content = labelOf(rule.pkg),
                                                )
                                                if (result == ConfirmResult.Confirmed) {
                                                    saveRules(rules.filterIndexed { i, _ -> i != index })
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(Icons.TwoTone.Delete, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Add,
                            title = stringResource(R.string.listen_rule_add),
                            description = stringResource(R.string.listen_rule_add_summary),
                            onClick = {
                                draftIndex = -1
                                draft = ListenRule(pkg = "", text = "", delay = "1500", script = "")
                            },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.listen_about_group)) {
                    item {
                        SettingsBaseWidget(
                            icon = null,
                            iconPlaceholder = false,
                            title = stringResource(R.string.listen_boot_title),
                            description = if (status.bootRegistered) {
                                stringResource(R.string.listen_boot_summary)
                            } else {
                                stringResource(R.string.listen_boot_missing)
                            },
                        )
                    }
                    item {
                        SettingsBaseWidget(
                            icon = Icons.TwoTone.Delete,
                            title = stringResource(R.string.listen_remove_title),
                            description = stringResource(R.string.listen_remove_summary),
                            isError = true,
                            onClick = {
                                scope.launch {
                                    val result = confirmDialog.awaitConfirm(
                                        title = msgRemoveTitle,
                                        content = msgRemoveConfirm,
                                    )
                                    if (result == ConfirmResult.Confirmed) {
                                        withContext(Dispatchers.IO) { runRootShell(ksuCli, REMOVE_SCRIPT) }
                                        delay(300)
                                        refresh()
                                        snackBarHost.showReplacingSnackbar(msgRemoved)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val current = draft
    if (current != null) {
        AlertDialog(
            onDismissRequest = {
                draft = null
                dialogMode = RuleDialogMode.Edit
            },
            title = {
                Text(
                    when (dialogMode) {
                        RuleDialogMode.Apps -> stringResource(R.string.listen_rule_pick_app)
                        RuleDialogMode.Files -> stringResource(R.string.listen_rule_script)
                        else -> stringResource(R.string.listen_rule_edit)
                    },
                )
            },
            text = {
                when (dialogMode) {
                    RuleDialogMode.Apps -> AppPickerContent(
                        packages = packages,
                        onPick = { info ->
                            draft = current.copy(pkg = info.packageName)
                            dialogMode = RuleDialogMode.Edit
                        },
                    )

                    RuleDialogMode.Files -> ScriptPickerContent(
                        rootFiles = rootFiles,
                        onPick = { picked ->
                            draft = current.copy(script = picked)
                            dialogMode = RuleDialogMode.Edit
                        },
                    )

                    else -> RuleEditContent(
                        rule = current,
                        labelOf = labelOf,
                        onChange = { draft = it },
                        onPickApp = {
                            dialogMode = RuleDialogMode.Apps
                            scope.launch { installedPackages.refresh() }
                        },
                        onPickScript = { dialogMode = RuleDialogMode.Files },
                    )
                }
            },
            confirmButton = {
                when (dialogMode) {
                    RuleDialogMode.Edit -> TextButton(
                        onClick = {
                            if (current.pkg.isBlank()) {
                                scope.launch {
                                    snackBarHost.showReplacingSnackbar(context.getString(R.string.listen_pick_app_first))
                                }
                            } else {
                                val next = rules.toMutableList()
                                if (draftIndex in next.indices) {
                                    next[draftIndex] = current
                                } else {
                                    next.add(current)
                                }
                                scope.launch {
                                    saveRules(next)
                                    draft = null
                                    dialogMode = RuleDialogMode.Edit
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.listen_rule_save)) }

                    else -> TextButton(
                        onClick = { dialogMode = RuleDialogMode.Edit },
                    ) { Text(stringResource(R.string.listen_rule_back)) }
                }
            },
            dismissButton = {
                if (dialogMode == RuleDialogMode.Edit) {
                    TextButton(onClick = { draft = null }) {
                        Text(stringResource(R.string.listen_rule_cancel))
                    }
                }
            },
        )
    }
}

@Composable
private fun RuleEditContent(
    rule: ListenRule,
    labelOf: (String) -> String,
    onChange: (ListenRule) -> Unit,
    onPickApp: () -> Unit,
    onPickScript: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { onPickApp() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rule.pkg.isNotBlank()) {
                PackageIcon(rule.pkg, null, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (rule.pkg.isBlank()) {
                        stringResource(R.string.listen_rule_pick_app)
                    } else {
                        labelOf(rule.pkg)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (rule.pkg.isNotBlank()) {
                    Text(
                        text = rule.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        OutlinedTextField(
            value = rule.text,
            onValueChange = { onChange(rule.copy(text = it)) },
            label = { Text(stringResource(R.string.listen_rule_text)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = rule.delay,
            onValueChange = { value -> onChange(rule.copy(delay = value.filter { it.isDigit() })) },
            label = { Text(stringResource(R.string.listen_rule_delay)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = rule.script,
            onValueChange = { onChange(rule.copy(script = it)) },
            label = { Text(stringResource(R.string.listen_rule_script)) },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = onPickScript) {
                    Text(stringResource(R.string.listen_rule_browse))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppPickerContent(
    packages: List<InstalledPackageInfo>,
    onPick: (InstalledPackageInfo) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(packages, query) {
        val q = query.trim().lowercase()
        packages
            .filter { it.packageName != "android" }
            .filter {
                q.isEmpty() || it.appLabel.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            .sortedWith(compareBy({ it.appLabel.firstOrNull()?.code ?: 0 }, { it.appLabel }))
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.listen_rule_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.listen_apps_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
        ) {
            items(filtered, key = { it.packageName }) { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(info) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PackageIcon(info.packageName, null, Modifier.size(34.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = info.appLabel.ifBlank { info.packageName },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptPickerContent(
    rootFiles: RootFileRepository,
    onPick: (String) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<RootFileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        error = null
        entries = withContext(Dispatchers.IO) {
            runCatching {
                rootFiles.list(path)
                    .filter { it.isDirectory || it.name.endsWith(".sh") }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }.getOrElse {
                error = it.message
                emptyList()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    if (path != "/") {
                        path = path.substringBeforeLast('/', "/").ifEmpty { "/" }
                    }
                },
            ) { Text("..") }
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.listen_files_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
        ) {
            items(entries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) path = entry.path else onPick(entry.path)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
