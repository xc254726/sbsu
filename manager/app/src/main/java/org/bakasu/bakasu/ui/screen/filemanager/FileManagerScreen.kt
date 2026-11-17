package org.bakasu.bakasu.ui.screen.filemanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Android
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
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import org.bakasu.bakasu.data.file.ScriptOutput
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
    data object GoTo : FileDialog
    data class Rename(val target: RootFileEntry) : FileDialog
    data class Chmod(val target: RootFileEntry) : FileDialog
    data class Properties(val target: RootFileEntry) : FileDialog
    data class Preview(val target: RootFileEntry) : FileDialog
    data class RunScript(val target: RootFileEntry) : FileDialog
    data class Destination(val cut: Boolean) : FileDialog
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
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf(emptyList<RootFileEntry>()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var hasRoot by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(0) }
    var ascending by remember { mutableStateOf(true) }

    var selected by remember { mutableStateOf(emptySet<String>()) }
    var actionTarget by remember { mutableStateOf<RootFileEntry?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showSelectionActions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var scriptRunning by remember { mutableStateOf(false) }
    var scriptOutput by remember { mutableStateOf<ScriptOutput?>(null) }

    LaunchedEffect(Unit) {
        hasRoot = withContext(Dispatchers.IO) { repo.hasRoot() }
    }

    LaunchedEffect(currentPath, refreshTick) {
        isLoading = true
        loadError = null
        val result = withContext(Dispatchers.IO) { runCatching { repo.list(currentPath) } }
        result.onSuccess { entries = it }
            .onFailure {
                entries = emptyList()
                loadError = it.message ?: "读取失败"
            }
        isLoading = false
    }

    fun navigateTo(target: String) {
        val normalized = RootFileRepository.normalize(target)
        selected = emptySet()
        query = ""
        searching = false
        if (normalized == currentPath) {
            refreshTick++
        } else {
            currentPath = normalized
        }
    }

    fun goUp() {
        if (currentPath == "/") {
            navigator.pop()
        } else {
            navigateTo(RootFileRepository.parentOf(currentPath))
        }
    }

    suspend fun perform(okMessage: String, block: () -> RootOpResult) {
        val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
        val result = outcome.getOrNull()
        when {
            result == null ->
                snackbarHost.showReplacingSnackbar(outcome.exceptionOrNull()?.message ?: "操作失败")

            result.ok -> {
                selected = emptySet()
                dialog = null
                actionTarget = null
                snackbarHost.showReplacingSnackbar(okMessage)
                refreshTick++
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

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = selected.isNotEmpty() || currentPath != "/") {
        if (selected.isNotEmpty()) {
            selected = emptySet()
        } else {
            navigateTo(RootFileRepository.parentOf(currentPath))
        }
    }

    val visible = remember(entries, query, showHidden, sortMode, ascending) {
        val filtered = entries.filter { entry ->
            (showHidden || !entry.isHidden) &&
                (query.isBlank() || entry.name.contains(query, ignoreCase = true))
        }
        val comparator = when (sortMode) {
            1 -> compareBy<RootFileEntry> { if (it.size < 0) Long.MAX_VALUE else it.size }
            2 -> compareBy<RootFileEntry> { it.modifiedSeconds }
            else -> compareBy<RootFileEntry> { it.name.lowercase(Locale.ROOT) }
        }
        val ordered = if (ascending) comparator else comparator.reversed()
        filtered.sortedWith(
            compareByDescending<RootFileEntry> { it.isDirectory }.then(ordered),
        )
    }

    Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("已选择 ${selected.size} 项") },
                    navigationIcon = {
                        AppBackButton(onClick = { selected = emptySet() })
                    },
                    actions = {
                        TextButton(onClick = { selected = entries.map { it.path }.toSet() }) {
                            Text("全选")
                        }
                        IconButton(onClick = { showSelectionActions = true }) {
                            Icon(Icons.TwoTone.MoreVert, contentDescription = "更多操作")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 6.dp)),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = RootFileRepository.nameOf(currentPath),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = currentPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = { AppBackButton(onClick = { goUp() }) },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(
                                imageVector = if (searching) Icons.TwoTone.Close else Icons.TwoTone.Search,
                                contentDescription = "搜索",
                            )
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
                                    text = { Text("前往路径…") },
                                    onClick = { showMenu = false; dialog = FileDialog.GoTo },
                                )
                                DropdownMenuItem(
                                    text = { Text("刷新") },
                                    onClick = { showMenu = false; refreshTick++ },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 6.dp)),
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                FloatingActionButton(onClick = { showTools = true }) {
                    Icon(Icons.TwoTone.Add, contentDescription = "新建")
                }
            }
        },
        snackbarHost = { SwipeableSnackbarHost(hostState = snackbarHost) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(bottom = innerPadding.calculateBottomPadding())
                .padding(horizontal = 12.dp),
        ) {
            PathBar(path = currentPath, onJump = { navigateTo(it) })

            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    placeholder = { Text("在当前目录中筛选") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.TwoTone.Close, contentDescription = "清除")
                            }
                        }
                    },
                )
            }

            if (currentPath == "/") {
                QuickAccessRow(onJump = { navigateTo(it) })
            }

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

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    loadError != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        WarningCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            message = loadError ?: "",
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        )
                        TextButton(onClick = { refreshTick++ }) { Text("重试") }
                    }

                    visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (query.isNotBlank()) "没有匹配的文件" else "此目录为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(items = visible, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                selected = entry.path in selected,
                                selecting = selected.isNotEmpty(),
                                timeFormat = timeFormat,
                                onClick = {
                                    when {
                                        selected.isNotEmpty() -> {
                                            selected = if (entry.path in selected) {
                                                selected - entry.path
                                            } else {
                                                selected + entry.path
                                            }
                                        }

                                        entry.isDirectory -> navigateTo(entry.path)
                                        else -> actionTarget = entry
                                    }
                                },
                                onLongClick = {
                                    selected = if (entry.path in selected) {
                                        selected - entry.path
                                    } else {
                                        selected + entry.path
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${visible.size} 项 · 长按多选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasRoot) "· root 权限" else "· 无 root 权限",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showTools) {
        ModalBottomSheet(
            onDismissRequest = { showTools = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
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
                    dialog = FileDialog.GoTo
                }
                SheetAction(Icons.TwoTone.Refresh, "刷新") {
                    showTools = false
                    refreshTick++
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

    if (showSelectionActions) {
        ModalBottomSheet(
            onDismissRequest = { showSelectionActions = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            val paths = selected.toList()
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "已选择 ${paths.size} 项",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetAction(Icons.TwoTone.ContentCopy, "复制到…") {
                    showSelectionActions = false
                    dialog = FileDialog.Destination(cut = false)
                }
                SheetAction(Icons.TwoTone.ContentCut, "移动到…") {
                    showSelectionActions = false
                    dialog = FileDialog.Destination(cut = true)
                }
                if (paths.size == 1) {
                    val only = entries.firstOrNull { it.path == paths.first() }
                    if (only != null) {
                        SheetAction(Icons.TwoTone.Edit, "重命名") {
                            showSelectionActions = false
                            dialog = FileDialog.Rename(only)
                        }
                    }
                }
                SheetAction(Icons.TwoTone.Delete, "删除", danger = true) {
                    showSelectionActions = false
                    requestDelete(paths)
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit

        FileDialog.NewFolder -> TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch { perform("已创建") { repo.createDirectory(currentPath, name) } }
            },
        )

        FileDialog.NewFile -> TextInputDialog(
            title = "新建文件",
            label = "文件名称",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch { perform("已创建") { repo.createFile(currentPath, name) } }
            },
        )

        FileDialog.GoTo -> TextInputDialog(
            title = "前往路径",
            label = "绝对路径",
            initial = currentPath,
            onDismiss = { dialog = null },
            onConfirm = { path ->
                dialog = null
                navigateTo(path)
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
            initial = currentPath,
            supporting = "将处理 ${selected.size} 项",
            onDismiss = { dialog = null },
            onConfirm = { dest ->
                val paths = selected.toList()
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
            supporting = "sh ${current.target.name}\n以 root 身份执行，工作目录为脚本所在目录",
            confirmLabel = "运行",
            allowEmpty = true,
            onDismiss = { dialog = null },
            onConfirm = { args ->
                dialog = null
                scope.launch {
                    scriptRunning = true
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { repo.runScript(current.target.path, args) }
                    }
                    scriptRunning = false
                    outcome.onSuccess { scriptOutput = it }
                        .onFailure { snackbarHost.showReplacingSnackbar(it.message ?: "执行失败") }
                }
            },
        )

        is FileDialog.Preview -> PreviewSheet(
            target = current.target,
            repo = repo,
            onDismiss = { dialog = null },
        )
    }

    if (scriptRunning) {
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(text = "正在以 root 执行…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    val result = scriptOutput
    if (result != null) {
        Dialog(onDismissRequest = { scriptOutput = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "脚本输出", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (result.ok) "退出码 0" else "退出码 ${result.exitCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = result.output.ifBlank { "（无输出）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(result.output))
                                toast("输出已复制")
                            },
                        ) { Text("复制") }
                        TextButton(onClick = { scriptOutput = null }) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PathBar(path: String, onJump: (String) -> Unit) {
    val segments = remember(path) {
        val result = ArrayList<Pair<String, String>>()
        var acc = ""
        RootFileRepository.segments(path).forEach { segment ->
            acc = "$acc/$segment"
            result.add(acc to segment)
        }
        result
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathChip(text = "/", active = segments.isEmpty(), onClick = { onJump("/") })
        segments.forEach { (target, name) ->
            Text(
                text = "›",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
            )
            PathChip(text = name, active = false, onClick = { onJump(target) })
        }
    }
}

@Composable
private fun PathChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun QuickAccessRow(onJump: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: RootFileEntry,
    selected: Boolean,
    selecting: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(entry),
                contentDescription = null,
                tint = when {
                    entry.isDirectory -> MaterialTheme.colorScheme.primary
                    entry.name.endsWith(".apk", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaOf(entry, timeFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
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
    else -> Icons.TwoTone.Description
}

private fun metaOf(entry: RootFileEntry, timeFormat: SimpleDateFormat): String {
    val parts = ArrayList<String>(3)
    if (entry.isSymlink) parts.add("链接")
    parts.add(if (entry.isDirectory) "目录" else formatSize(entry.size))
    if (entry.modifiedSeconds > 0) parts.add(timeFormat.format(Date(entry.modifiedSeconds * 1000L)))
    if (entry.mode.isNotBlank()) parts.add(entry.mode)
    return parts.joinToString(" · ")
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
        context.startActivity(Intent.createChooser(intent, "打开方式"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "无法打开：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
