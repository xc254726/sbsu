package org.bakasu.bakasu.ui.screen.filemanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.file.RootFileEntry
import org.bakasu.bakasu.data.file.RootFileRepository
import org.bakasu.bakasu.data.file.RootOpResult
import org.bakasu.bakasu.data.file.ScriptSession
import org.bakasu.bakasu.data.file.TextPreview
import org.bakasu.bakasu.data.shell.KsuCliRepository
import org.bakasu.bakasu.ui.component.ConfirmResult
import org.bakasu.bakasu.ui.component.SwipeableSnackbarHost
import org.bakasu.bakasu.ui.component.WarningCard
import org.bakasu.bakasu.ui.component.rememberConfirmDialog
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.adaptiveScaffoldWindowInsets
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.koin.compose.koinInject

private val QUICK_ACCESS = listOf(
    "/sdcard" to "内部存储",
    "/data" to "/data",
    "/data/adb" to "/data/adb",
    "/data/app" to "/data/app",
    "/system" to "/system",
    "/storage/emulated/0/Download" to "下载",
)

private sealed interface FileDialog {
    data object NewFolder : FileDialog
    data object NewFile : FileDialog
    data class GoTo(val pane: PaneState) : FileDialog
    data class Rename(val target: RootFileEntry) : FileDialog
    data class Chmod(val target: RootFileEntry) : FileDialog
    data class Properties(val target: RootFileEntry) : FileDialog
    data class Preview(val target: RootFileEntry) : FileDialog
    data class RunScript(val target: RootFileEntry) : FileDialog
    data class Destination(val cut: Boolean, val source: List<String>, val from: String) : FileDialog
}

/** 每一栏各自的浏览状态：路径、条目、选中项都独立。 */
private class PaneState(initialPath: String) {
    var path by mutableStateOf(initialPath)
    var entries by mutableStateOf<List<RootFileEntry>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var tick by mutableStateOf(0)

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val ksuCli = koinInject<KsuCliRepository>()
    val repo = remember(ksuCli) { RootFileRepository(ksuCli) }
    val confirmDialog = rememberConfirmDialog()

    val left = remember { PaneState("/") }
    val right = remember { PaneState("/sdcard") }
    var activeIndex by remember { mutableStateOf(0) }
    val panes = listOf(left, right)
    val active = panes[activeIndex]
    val other = panes[1 - activeIndex]

    var hasRoot by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(0) }
    var ascending by remember { mutableStateOf(true) }

    var actionTarget by remember { mutableStateOf<RootFileEntry?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var headerPane by remember { mutableStateOf<PaneState?>(null) }
    var terminal by remember { mutableStateOf<ScriptTerminal?>(null) }

    LaunchedEffect(Unit) {
        hasRoot = withContext(Dispatchers.IO) { repo.hasRoot() }
    }

    panes.forEach { pane ->
        LaunchedEffect(pane.path, pane.tick) {
            pane.loading = true
            pane.error = null
            val result = withContext(Dispatchers.IO) { runCatching { repo.list(pane.path) } }
            result.onSuccess { pane.entries = it }
                .onFailure {
                    pane.entries = emptyList()
                    pane.error = it.message ?: "读取失败"
                }
            pane.loading = false
        }
    }

    val leftVisible = remember(left.entries, showHidden, sortMode, ascending) {
        computeVisible(left.entries, showHidden, sortMode, ascending)
    }
    val rightVisible = remember(right.entries, showHidden, sortMode, ascending) {
        computeVisible(right.entries, showHidden, sortMode, ascending)
    }

    fun navigate(pane: PaneState, target: String) {
        val normalized = RootFileRepository.normalize(target)
        pane.selected = emptySet()
        if (normalized == pane.path) {
            pane.tick++
        } else {
            pane.path = normalized
        }
    }

    fun back() {
        when {
            active.selected.isNotEmpty() -> active.selected = emptySet()
            active.path != "/" -> navigate(active, RootFileRepository.parentOf(active.path))
            else -> navigator.pop()
        }
    }

    suspend fun perform(okMessage: String, block: () -> RootOpResult) {
        val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
        val result = outcome.getOrNull()
        when {
            result == null ->
                snackbarHost.showReplacingSnackbar(outcome.exceptionOrNull()?.message ?: "操作失败")

            result.ok -> {
                left.selected = emptySet()
                right.selected = emptySet()
                dialog = null
                actionTarget = null
                snackbarHost.showReplacingSnackbar(okMessage)
                left.tick++
                right.tick++
            }

            else -> snackbarHost.showReplacingSnackbar(result.message.ifBlank { "操作失败" })
        }
    }

    fun requestDelete(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch {
            val content = if (paths.size == 1) {
                "确定永久删除下面这一项吗？删除后无法恢复。\n\n${paths.first()}"
            } else {
                "确定永久删除选中的 ${paths.size} 项吗？删除后无法恢复。"
            }
            val confirmed = confirmDialog.awaitConfirm(
                title = "删除",
                content = content,
                confirm = "删除",
            )
            if (confirmed != ConfirmResult.Confirmed) return@launch
            perform("已删除") { repo.delete(paths) }
        }
    }

    fun transfer(cut: Boolean) {
        val paths = active.selected.toList()
        if (paths.isEmpty()) return
        val dest = other.path
        val destName = RootFileRepository.nameOf(dest)
        scope.launch {
            if (cut) {
                perform("已移动到 $destName") { repo.move(paths, dest) }
            } else {
                perform("已复制到 $destName") { repo.copy(paths, dest) }
            }
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = true) { back() }

    Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "隐藏终端",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = active.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { AppBackButton(onClick = { back() }) },
                actions = {
                    IconButton(
                        onClick = {
                            val moved = left.path
                            left.path = right.path
                            right.path = moved
                            left.selected = emptySet()
                            right.selected = emptySet()
                        },
                    ) {
                        Text(text = "⇄", fontSize = 19.sp)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.TwoTone.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (sortMode == 0) "按名称 ✓" else "按名称") },
                                onClick = { sortMode = 0; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortMode == 1) "按大小 ✓" else "按大小") },
                                onClick = { sortMode = 1; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortMode == 2) "按时间 ✓" else "按时间") },
                                onClick = { sortMode = 2; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (ascending) "倒序排列" else "正序排列") },
                                onClick = { ascending = !ascending; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "隐藏以 . 开头的文件" else "显示隐藏文件") },
                                onClick = { showHidden = !showHidden; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("全选本栏") },
                                onClick = {
                                    showMenu = false
                                    active.selected = active.entries.map { it.path }.toSet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("前往路径…") },
                                onClick = { showMenu = false; dialog = FileDialog.GoTo(active) },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新两栏") },
                                onClick = { showMenu = false; left.tick++; right.tick++ },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 6.dp)),
            )
        },
        snackbarHost = { SwipeableSnackbarHost(hostState = snackbarHost) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp),
        ) {
            if (!hasRoot) {
                WarningCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    message = "当前没有 root 权限，只能浏览应用自身可访问的目录。请先在首页授予 root。",
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                )
            }


            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaneColumn(
                    pane = left,
                    entries = leftVisible,
                    active = activeIndex == 0,
                    modifier = Modifier.weight(1f),
                    onActivate = { activeIndex = 0 },
                    onOpenHeader = { headerPane = left },
                    onNavigate = { target -> navigate(left, target) },
                    onOpenFile = { actionTarget = it },
                )
                PaneColumn(
                    pane = right,
                    entries = rightVisible,
                    active = activeIndex == 1,
                    modifier = Modifier.weight(1f),
                    onActivate = { activeIndex = 1 },
                    onOpenHeader = { headerPane = right },
                    onNavigate = { target -> navigate(right, target) },
                    onOpenFile = { actionTarget = it },
                )
            }

            Spacer(Modifier.height(8.dp))

            ActionBar(
                selectionCount = active.selected.size,
                destLabel = RootFileRepository.nameOf(other.path),
                single = active.entries.firstOrNull { it.path in active.selected },
                onNew = { showTools = true },
                onCopy = { transfer(cut = false) },
                onMove = { transfer(cut = true) },
                onDelete = { requestDelete(active.selected.toList()) },
                onMore = { active.entries.firstOrNull { it.path in active.selected }?.let { actionTarget = it } },
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showTools) {
        ModalBottomSheet(
            onDismissRequest = { showTools = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "在 ${RootFileRepository.nameOf(active.path)} 中新建",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetAction(Icons.TwoTone.Folder, "新建文件夹") {
                    showTools = false
                    dialog = FileDialog.NewFolder
                }
                SheetAction(Icons.TwoTone.Description, "新建文件") {
                    showTools = false
                    dialog = FileDialog.NewFile
                }
                SheetAction(Icons.TwoTone.Sort, "前往路径…") {
                    showTools = false
                    dialog = FileDialog.GoTo(active)
                }
                SheetAction(Icons.TwoTone.Refresh, "刷新两栏") {
                    showTools = false
                    left.tick++
                    right.tick++
                }
            }
        }
    }

    val sheetPane = headerPane
    if (sheetPane != null) {
        ModalBottomSheet(
            onDismissRequest = { headerPane = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = if (sheetPane === active) "当前栏" else "另一栏",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = sheetPane.path,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                QuickAccessRow { target ->
                    activeIndex = if (sheetPane === left) 0 else 1
                    navigate(sheetPane, target)
                    headerPane = null
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                SheetAction(Icons.TwoTone.Folder, "前往路径…") {
                    headerPane = null
                    dialog = FileDialog.GoTo(sheetPane)
                }
                SheetAction(Icons.TwoTone.Refresh, "刷新本栏") {
                    headerPane = null
                    sheetPane.tick++
                }
                SheetAction(Icons.TwoTone.Close, "取消本栏选择", enabled = sheetPane.selected.isNotEmpty()) {
                    headerPane = null
                    sheetPane.selected = emptySet()
                }
            }
        }
    }

    val target = actionTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!target.isDirectory && isScript(target)) {
                    SheetAction(Icons.TwoTone.PlayArrow, "以 root 运行") {
                        actionTarget = null
                        dialog = FileDialog.RunScript(target)
                    }
                }
                if (!target.isDirectory) {
                    SheetAction(Icons.TwoTone.Description, "文本预览") {
                        actionTarget = null
                        dialog = FileDialog.Preview(target)
                    }
                }
                if (!target.isDirectory && target.name.endsWith(".apk", ignoreCase = true)) {
                    SheetAction(Icons.TwoTone.Android, "安装此 APK") {
                        scope.launch { perform("安装完成") { repo.installApk(target.path) } }
                    }
                }
                if (!target.isDirectory) {
                    SheetAction(Icons.TwoTone.Android, "用其他应用打开") {
                        openWith(context, target)
                        actionTarget = null
                    }
                }
                SheetAction(Icons.TwoTone.ContentCopy, "复制到另一栏 ${RootFileRepository.nameOf(other.path)}") {
                    actionTarget = null
                    scope.launch {
                        perform("已复制") { repo.copy(listOf(target.path), other.path) }
                    }
                }
                SheetAction(Icons.TwoTone.ContentCut, "移动到另一栏 ${RootFileRepository.nameOf(other.path)}") {
                    actionTarget = null
                    scope.launch {
                        perform("已移动") { repo.move(listOf(target.path), other.path) }
                    }
                }
                SheetAction(Icons.TwoTone.ContentCopy, "复制路径") {
                    clipboard.setText(AnnotatedString(target.path))
                    actionTarget = null
                    toast("路径已复制")
                }
                SheetAction(Icons.TwoTone.Edit, "重命名") {
                    actionTarget = null
                    dialog = FileDialog.Rename(target)
                }
                SheetAction(Icons.TwoTone.Lock, "修改权限") {
                    actionTarget = null
                    dialog = FileDialog.Chmod(target)
                }
                SheetAction(Icons.TwoTone.Info, "属性") {
                    actionTarget = null
                    dialog = FileDialog.Properties(target)
                }
                SheetAction(Icons.TwoTone.Delete, "删除", danger = true) {
                    requestDelete(listOf(target.path))
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit

        FileDialog.NewFolder -> TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            supporting = active.path,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch { perform("已创建") { repo.createDirectory(active.path, name) } }
            },
        )

        FileDialog.NewFile -> TextInputDialog(
            title = "新建文件",
            label = "文件名称",
            supporting = active.path,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch { perform("已创建") { repo.createFile(active.path, name) } }
            },
        )

        is FileDialog.GoTo -> TextInputDialog(
            title = "前往路径",
            label = "绝对路径",
            initial = current.pane.path,
            onDismiss = { dialog = null },
            onConfirm = { path ->
                dialog = null
                activeIndex = if (current.pane === left) 0 else 1
                navigate(current.pane, path)
            },
        )

        is FileDialog.Rename -> TextInputDialog(
            title = "重命名",
            label = "新名称",
            initial = current.target.name,
            supporting = current.target.path,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch { perform("已重命名") { repo.rename(current.target.path, name) } }
            },
        )

        is FileDialog.Chmod -> TextInputDialog(
            title = "修改权限",
            label = "八进制权限，如 644 / 755",
            initial = modeToOctal(current.target.mode) ?: "644",
            supporting = "当前：${current.target.mode.ifBlank { "未知" }}",
            keyboardType = KeyboardType.Number,
            onDismiss = { dialog = null },
            onConfirm = { mode ->
                scope.launch { perform("权限已修改") { repo.chmod(current.target.path, mode) } }
            },
        )

        is FileDialog.Destination -> TextInputDialog(
            title = if (current.cut) "移动到" else "复制到",
            label = "目标目录",
            initial = current.from,
            supporting = "将处理 ${current.source.size} 项",
            onDismiss = { dialog = null },
            onConfirm = { dest ->
                val paths = current.source
                if (current.cut) {
                    scope.launch { perform("已移动") { repo.move(paths, dest) } }
                } else {
                    scope.launch { perform("已复制") { repo.copy(paths, dest) } }
                }
            },
        )

        is FileDialog.Properties -> PropertiesDialog(
            entry = current.target,
            onDismiss = { dialog = null },
        )

        is FileDialog.RunScript -> TextInputDialog(
            title = "运行脚本",
            label = "参数（可留空）",
            initial = "",
            supporting = "sh ${current.target.name}\n以 root 身份执行，工作目录为脚本所在目录\n运行后可实时查看输出并输入内容",
            confirmLabel = "运行",
            allowEmpty = true,
            onDismiss = { dialog = null },
            onConfirm = { args ->
                dialog = null
                val run = ScriptTerminal(current.target.name, current.target.path)
                terminal = run
                scope.launch {
                    val session = withContext(Dispatchers.IO) {
                        ScriptSession.start(
                            scriptPath = current.target.path,
                            args = args,
                            onOutput = { run.append(it) },
                            onFinished = { code -> run.finish(code) },
                        )
                    }
                    if (session == null) {
                        run.append("无法启动脚本：没有找到可用的 shell\n")
                        run.finish(-1)
                    } else {
                        run.session = session
                    }
                }
            },
        )

        is FileDialog.Preview -> PreviewSheet(
            target = current.target,
            repo = repo,
            onDismiss = { dialog = null },
        )
    }

    val running = terminal
    if (running != null) {
        ScriptTerminalDialog(
            run = running,
            onSend = { text -> running.session?.send(text) },
            onStop = { running.session?.stop() },
            onClose = {
                running.session?.stop()
                terminal = null
            },
        )
    }
}

/**
 * 一次脚本运行的界面状态：累积输出、记录退出码。
 * 输出在后台线程到达，这里统一转到主线程再写入 snapshot state。
 */
private class ScriptTerminal(val name: String, val path: String) {
    private val content = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val textState = mutableStateOf("")
    private val exitState = mutableStateOf<Int?>(null)
    private var scheduled = false

    var session: ScriptSession? = null

    val text: String get() = textState.value
    val exitCode: Int? get() = exitState.value

    @Synchronized
    fun append(chunk: String) {
        content.append(chunk)
        if (content.length > MAX_CHARS) {
            content.delete(0, content.length - MAX_CHARS)
        }
        if (scheduled) return
        scheduled = true
        handler.postDelayed({ flush() }, 60L)
    }

    fun finish(code: Int) {
        handler.post {
            exitState.value = code
            flush()
        }
    }

    @Synchronized
    private fun flush() {
        scheduled = false
        textState.value = content.toString()
    }

    private companion object {
        const val MAX_CHARS = 120_000
    }
}

/**
 * 全屏终端弹窗：实时输出 + 可输入 stdin。
 * 需要用户输入的脚本（选择菜单、确认提示）就在这里回答。
 */
@Composable
private fun ScriptTerminalDialog(
    run: ScriptTerminal,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    val output = run.text
    val exitCode = run.exitCode

    LaunchedEffect(output) {
        scroll.scrollTo(scroll.maxValue)
    }

    val terminalBackground = Color(0xFF0A0B0E)
    val panelBackground = Color(0xFF14161C)
    val fieldBackground = Color(0xFF1E2029)
    val bodyColor = Color(0xFFCBD5CF)
    val titleColor = Color(0xFFEDEDF2)
    val hintColor = Color(0xFF6A6E7C)
    val accentColor = Color(0xFF6EE7A0)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = terminalBackground,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panelBackground)
                        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = run.name,
                            color = titleColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (val code = exitCode) {
                                null -> "运行中 · ${run.path}"
                                else -> "已结束 · 退出码 $code"
                            },
                            color = if (exitCode == null) accentColor else Color(0xFF9DA0AC),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (exitCode == null) {
                        TextButton(onClick = onStop) {
                            Text("停止", color = Color(0xFFFF9A9A), fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", color = Color(0xFF9DA0AC), fontSize = 12.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(terminalBackground),
                ) {
                    Text(
                        text = output.ifEmpty { "正在启动脚本…" },
                        color = if (output.isEmpty()) hintColor else bodyColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panelBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFE8EAF0),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(accentColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                onSend(input)
                                input = ""
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .background(fieldBackground, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "输入内容后回车发送给脚本",
                                        color = hintColor,
                                        fontSize = 12.sp,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onSend(input)
                            input = ""
                        },
                    ) {
                        Text("发送", color = accentColor, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneColumn(
    pane: PaneState,
    entries: List<RootFileEntry>,
    active: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onOpenHeader: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenFile: (RootFileEntry) -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "paneBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        label = "paneBorder",
    )

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = BorderStroke(if (active) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onActivate()
                        onOpenHeader()
                    }
                    .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Folder,
                    contentDescription = null,
                    tint = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = RootFileRepository.nameOf(pane.path),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pane.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (pane.selected.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = pane.selected.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (entries.size > 999) "999+" else entries.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.5f), thickness = 0.7.dp)

            Box(modifier = Modifier.weight(1f)) {
                val error = pane.error
                when {
                    pane.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }

                    error != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { pane.tick++ }) { Text("重试") }
                    }

                    entries.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "空目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 3.dp),
                    ) {
                        items(items = entries, key = { it.path }) { entry ->
                            PaneRow(
                                entry = entry,
                                selected = entry.path in pane.selected,
                                selecting = pane.selected.isNotEmpty(),
                                onClick = {
                                    onActivate()
                                    when {
                                        pane.selected.isNotEmpty() -> pane.toggle(entry.path)
                                        entry.isDirectory -> {
                                            pane.selected = emptySet()
                                            onNavigate(entry.path)
                                        }

                                        else -> onOpenFile(entry)
                                    }
                                },
                                onLongClick = {
                                    onActivate()
                                    pane.toggle(entry.path)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneRow(
    entry: RootFileEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(entry),
            contentDescription = null,
            tint = iconTintOf(entry),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = subtitleOf(entry),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting && selected) {
            Icon(
                imageVector = Icons.TwoTone.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ActionBar(
    selectionCount: Int,
    destLabel: String,
    single: RootFileEntry?,
    onNew: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            if (selectionCount > 0) {
                Text(
                    text = "已选 $selectionCount 项 · 目标栏 $destLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BarAction(Icons.TwoTone.Add, "新建", enabled = true, onClick = onNew)
                BarAction(
                    Icons.TwoTone.ContentCopy,
                    "复制",
                    enabled = selectionCount > 0,
                    onClick = onCopy,
                )
                BarAction(
                    Icons.TwoTone.ContentCut,
                    "移动",
                    enabled = selectionCount > 0,
                    onClick = onMove,
                )
                BarAction(
                    Icons.TwoTone.Delete,
                    "删除",
                    enabled = selectionCount > 0,
                    danger = true,
                    onClick = onDelete,
                )
                BarAction(
                    Icons.TwoTone.MoreVert,
                    "更多",
                    enabled = selectionCount > 0 || single != null,
                    onClick = onMore,
                )
            }
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 10.sp, color = color)
    }
}

@Composable
private fun QuickAccessRow(onJump: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_ACCESS.forEach { (target, label) ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clickable { onJump(target) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    supporting: String? = null,
    confirmLabel: String = "确定",
    allowEmpty: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (supporting != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = allowEmpty || value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PropertiesDialog(entry: RootFileEntry, onDismiss: () -> Unit) {
    val rows = listOf(
        "路径" to entry.path,
        "类型" to if (entry.isDirectory) "目录" else if (entry.isSymlink) "符号链接" else "文件",
        "大小" to if (entry.isDirectory) "-" else formatSize(entry.size),
        "权限" to entry.mode.ifBlank { "未知" },
        "所有者" to if (entry.owner.isBlank()) "未知" else "${entry.owner}:${entry.group}",
        "修改时间" to if (entry.modifiedSeconds > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(entry.modifiedSeconds * 1000L))
        } else {
            "未知"
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach { (key, value) ->
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    target: RootFileEntry,
    repo: RootFileRepository,
    onDismiss: () -> Unit,
) {
    var preview by remember(target.path) { mutableStateOf<TextPreview?>(null) }
    var error by remember(target.path) { mutableStateOf<String?>(null) }
    var loading by remember(target.path) { mutableStateOf(true) }

    LaunchedEffect(target.path) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.readText(target.path) } }
        result.onSuccess { preview = it }
            .onFailure { error = it.message ?: "读取失败" }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatSize(target.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val content = preview
            val text = content?.takeIf { !it.binary }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            } else if (text == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "这是二进制文件，无法以文本方式预览",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (text.text.isBlank()) {
                        Text("（空文件）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            text = text.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (text.truncated) {
                        Text(
                            text = "内容过大，仅显示前 512 KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun computeVisible(
    entries: List<RootFileEntry>,
    showHidden: Boolean,
    sortMode: Int,
    ascending: Boolean,
): List<RootFileEntry> {
    val filtered = entries.filter { showHidden || !it.isHidden }
    val comparator = when (sortMode) {
        1 -> compareBy<RootFileEntry> { if (it.size < 0) Long.MAX_VALUE else it.size }
        2 -> compareBy<RootFileEntry> { it.modifiedSeconds }
        else -> compareBy<RootFileEntry> { it.name.lowercase(Locale.ROOT) }
    }
    val ordered = if (ascending) comparator else comparator.reversed()
    return filtered.sortedWith(
        compareByDescending<RootFileEntry> { it.isDirectory }.then(ordered),
    )
}

private fun isScript(entry: RootFileEntry): Boolean {
    if (entry.isDirectory) return false
    if (entry.name.endsWith(".sh", ignoreCase = true)) return true
    // 权限串形如 -rwxr-xr-x，第 2~10 位里的 x 表示可执行
    val mode = entry.mode
    return mode.length >= 10 && mode.substring(1, 10).any { it == 'x' }
}

private fun iconFor(entry: RootFileEntry): ImageVector = when {
    entry.isDirectory -> Icons.TwoTone.Folder
    entry.name.endsWith(".apk", ignoreCase = true) -> Icons.TwoTone.Android
    isScript(entry) -> Icons.TwoTone.PlayArrow
    else -> Icons.TwoTone.Description
}

@Composable
private fun iconTintOf(entry: RootFileEntry): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        entry.isDirectory -> scheme.primary
        entry.name.endsWith(".apk", ignoreCase = true) -> scheme.tertiary
        isScript(entry) -> scheme.secondary
        entry.isSymlink -> scheme.onSurfaceVariant.copy(alpha = 0.8f)
        else -> scheme.onSurfaceVariant
    }
}

private fun subtitleOf(entry: RootFileEntry): String = when {
    entry.isDirectory -> "目录"
    entry.isSymlink -> "链接 · ${formatSize(entry.size)}"
    else -> formatSize(entry.size)
}

private fun modeToOctal(mode: String): String? {
    if (mode.length < 10) return null
    val perms = mode.substring(1, 10)
    val builder = StringBuilder()
    for (group in 0 until 3) {
        val base = group * 3
        var digit = 0
        if (perms[base] != '-') digit += 4
        if (perms[base + 1] != '-') digit += 2
        if (perms[base + 2] != '-') digit += 1
        builder.append(digit)
    }
    return builder.toString()
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext.isEmpty()) return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

private fun openWith(context: Context, entry: RootFileEntry) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(entry.path),
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(entry.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可以打开该文件的应用", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "打开失败", Toast.LENGTH_SHORT).show()
    }
}
