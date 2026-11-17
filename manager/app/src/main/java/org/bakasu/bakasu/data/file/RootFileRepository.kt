package org.bakasu.bakasu.data.file

import com.topjohnwu.superuser.io.SuFile
import org.bakasu.bakasu.data.shell.KsuCliRepository

/**
 * 一条目录项。所有字段都直接来自 root shell 的 stat，不依赖 java.io.File 的可见性判断，
 * 因此 /data、/system 等普通应用无权的路径也能正常列出。
 */
data class RootFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedSeconds: Long,
    val mode: String,
    val owner: String,
    val group: String,
) {
    val isHidden: Boolean get() = name.startsWith(".")
}

data class RootOpResult(val ok: Boolean, val message: String = "")

data class TextPreview(
    val text: String,
    val truncated: Boolean,
    val binary: Boolean,
)

class RootFileException(message: String) : Exception(message)

/**
 * 以 root 身份做文件操作的仓库。
 * 所有命令都通过 [KsuCliRepository.getRootShell] 执行，失败时回落到普通权限（此时只能看到
 * 应用自己可读的路径）。
 */
class RootFileRepository(private val ksuCli: KsuCliRepository) {

    companion object {
        private const val NO_DIR_MARK = "__SB_NO_DIR__"
        private const val LIST_MARK = "__SB_LIST__"
        private const val MAX_PREVIEW_BYTES = 512 * 1024

        fun normalize(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return "/"
            val collapsed = StringBuilder()
            var lastSlash = false
            for (ch in trimmed) {
                if (ch == '/') {
                    if (!lastSlash) collapsed.append(ch)
                    lastSlash = true
                } else {
                    collapsed.append(ch)
                    lastSlash = false
                }
            }
            var result = collapsed.toString()
            if (!result.startsWith("/")) result = "/$result"
            if (result.length > 1 && result.endsWith("/")) result = result.dropLast(1)
            return result.ifEmpty { "/" }
        }

        fun childOf(parent: String, name: String): String {
            val base = normalize(parent)
            return if (base == "/") "/$name" else "$base/$name"
        }

        fun parentOf(path: String): String {
            val normalized = normalize(path)
            if (normalized == "/") return "/"
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "/" else normalized.substring(0, index)
        }

        fun nameOf(path: String): String {
            val normalized = normalize(path)
            if (normalized == "/") return "/"
            return normalized.substringAfterLast('/')
        }

        fun segments(path: String): List<String> {
            val normalized = normalize(path)
            if (normalized == "/") return emptyList()
            return normalized.trimStart('/').split('/').filter { it.isNotEmpty() }
        }
    }

    private data class ShellOutcome(
        val out: List<String>,
        val err: String,
        val code: Int,
    )

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun run(script: String): ShellOutcome {
        val shell = ksuCli.getRootShell()
        val errLines = ArrayList<String>()
        val result = shell.newJob().add(script).to(ArrayList(), errLines).exec()
        return ShellOutcome(result.out, errLines.joinToString("\n").trim(), result.code)
    }

    fun hasRoot(): Boolean = runCatching { ksuCli.rootAvailable() }.getOrDefault(false)

    /** 列出目录内容；失败时抛 [RootFileException]，message 可直接展示给用户。 */
    fun list(directory: String): List<RootFileEntry> {
        val dir = normalize(directory)
        val script = buildString {
            append("cd ").append(quote(dir)).append(" 2>/dev/null || { echo ")
            append(NO_DIR_MARK).append("; exit 3; }\n")
            // ./ 前缀避免文件名以 - 开头时被当成选项；.[!.]* 不会匹配 . 与 ..
            append("stat -c '%A|%U|%G|%s|%Y|%n' ./.[!.]* ./* 2>/dev/null\n")
            // 第二遍跟随符号链接，用来修正 "指向目录的链接" 的类型
            append("echo ").append(LIST_MARK).append("\n")
            append("stat -L -c '%F|%n' ./.[!.]* ./* 2>/dev/null")
        }
        val stat = run(script)
        if (stat.out.any { it.trim() == NO_DIR_MARK }) {
            throw RootFileException("无法进入 $dir：目录不存在或没有权限")
        }
        val marker = stat.out.indexOfFirst { it.trim() == LIST_MARK }
        val plain = if (marker >= 0) stat.out.subList(0, marker) else stat.out
        val followed = if (marker >= 0) stat.out.subList(marker + 1, stat.out.size) else emptyList()

        val entries = plain.mapNotNull { parseStatLine(dir, it) }
        if (entries.isNotEmpty()) {
            val dirNames = parseFollowedDirectories(followed)
            if (dirNames.isEmpty()) return entries
            return entries.map { entry ->
                if (!entry.isDirectory && entry.name in dirNames) {
                    entry.copy(isDirectory = true)
                } else {
                    entry
                }
            }
        }

        // stat 不可用时退回 ls，保证任何机器上都能浏览
        val ls = run("cd ${quote(dir)} 2>/dev/null && ls -A -p 2>/dev/null")
        val fallback = ls.out.mapNotNull { parseLsLine(dir, it) }
        if (fallback.isEmpty() && ls.out.isEmpty() && stat.err.isNotBlank()) {
            throw RootFileException(stat.err)
        }
        return fallback
    }

    private fun parseFollowedDirectories(lines: List<String>): Set<String> {
        if (lines.isEmpty()) return emptySet()
        val result = HashSet<String>()
        lines.forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && parts[0].trim() == "directory") {
                result.add(parts[1].removePrefix("./"))
            }
        }
        return result
    }

    private fun parseStatLine(directory: String, line: String): RootFileEntry? {
        val text = line.trim()
        if (text.isEmpty()) return null
        // 文件名里可能含 '|'，所以限制切 6 段，余下全部算文件名
        val parts = text.split("|", limit = 6)
        if (parts.size < 6) return null
        val mode = parts[0]
        if (mode.isEmpty()) return null
        val name = parts[5].removePrefix("./")
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) return null
        return RootFileEntry(
            name = name,
            path = childOf(directory, name),
            isDirectory = mode.startsWith("d"),
            isSymlink = mode.startsWith("l"),
            size = parts[3].toLongOrNull() ?: -1L,
            modifiedSeconds = parts[4].toLongOrNull() ?: 0L,
            mode = mode,
            owner = parts[1],
            group = parts[2],
        )
    }

    private fun parseLsLine(directory: String, line: String): RootFileEntry? {
        val text = line.trimEnd()
        if (text.isEmpty()) return null
        val isDir = text.endsWith("/")
        val name = if (isDir) text.dropLast(1) else text
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) return null
        return RootFileEntry(
            name = name,
            path = childOf(directory, name),
            isDirectory = isDir,
            isSymlink = false,
            size = -1L,
            modifiedSeconds = 0L,
            mode = "",
            owner = "",
            group = "",
        )
    }

    fun delete(paths: List<String>): RootOpResult {
        if (paths.isEmpty()) return RootOpResult(false, "没有选中文件")
        val script = paths.joinToString("\n") { "rm -rf -- ${quote(it)}" }
        return asResult(run(script))
    }

    fun createDirectory(parent: String, name: String): RootOpResult {
        val validName = validateName(name) ?: return RootOpResult(false, "名称不合法")
        return asResult(run("mkdir -p -- ${quote(childOf(parent, validName))}"))
    }

    fun createFile(parent: String, name: String): RootOpResult {
        val validName = validateName(name) ?: return RootOpResult(false, "名称不合法")
        return asResult(run("touch -- ${quote(childOf(parent, validName))}"))
    }

    fun rename(path: String, newName: String): RootOpResult {
        val validName = validateName(newName) ?: return RootOpResult(false, "名称不合法")
        val target = childOf(parentOf(path), validName)
        if (target == path) return RootOpResult(true)
        return asResult(run("mv -- ${quote(path)} ${quote(target)}"))
    }

    fun copy(paths: List<String>, destDir: String): RootOpResult {
        if (paths.isEmpty()) return RootOpResult(false, "没有选中文件")
        val dest = normalize(destDir)
        val bad = paths.firstOrNull { dest == it || dest.startsWith(if (it.endsWith("/")) it else "$it/") }
        if (bad != null) return RootOpResult(false, "不能复制到自身或子目录")
        val script = paths.joinToString("\n") { "cp -a -- ${quote(it)} ${quote(dest)}/" }
        return asResult(run(script))
    }

    fun move(paths: List<String>, destDir: String): RootOpResult {
        if (paths.isEmpty()) return RootOpResult(false, "没有选中文件")
        val dest = normalize(destDir)
        val bad = paths.firstOrNull { dest == it || dest.startsWith(if (it.endsWith("/")) it else "$it/") }
        if (bad != null) return RootOpResult(false, "不能移动到自身或子目录")
        val script = paths.joinToString("\n") { "mv -- ${quote(it)} ${quote(dest)}/" }
        return asResult(run(script))
    }

    fun chmod(path: String, mode: String): RootOpResult {
        val value = mode.trim()
        if (!Regex("^[0-7]{3,4}$").matches(value)) return RootOpResult(false, "权限应为 3~4 位八进制数")
        return asResult(run("chmod $value -- ${quote(path)}"))
    }

    fun installApk(path: String): RootOpResult {
        val outcome = run("pm install -r -t ${quote(path)} 2>&1")
        val message = outcome.out.joinToString("\n").ifBlank { outcome.err }
        val ok = outcome.code == 0 && outcome.out.any { it.contains("Success") }
        return RootOpResult(ok, message.trim())
    }

    fun readText(path: String, limit: Int = MAX_PREVIEW_BYTES): TextPreview {
        val file = SuFile.open(path)
        if (!file.exists()) throw RootFileException("文件不存在或没有权限")
        if (file.isDirectory) throw RootFileException("这是一个目录")
        val buffer = ByteArray(limit + 1)
        val read = file.newInputStream().use { input ->
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count <= 0) break
                total += count
            }
            total
        }
        val truncated = read > limit
        val length = if (truncated) limit else read
        var binary = false
        for (i in 0 until length) {
            if (buffer[i] == 0.toByte()) {
                binary = true
                break
            }
        }
        val text = if (binary) "" else String(buffer, 0, length, Charsets.UTF_8)
        return TextPreview(text = text, truncated = truncated, binary = binary)
    }

    private fun asResult(outcome: ShellOutcome): RootOpResult {
        if (outcome.code == 0 && outcome.err.isBlank()) return RootOpResult(true)
        val message = outcome.err.ifBlank { outcome.out.joinToString("\n") }
        return RootOpResult(false, message.trim().ifBlank { "操作失败（退出码 ${outcome.code}）" })
    }

    private fun validateName(name: String): String? {
        val value = name.trim()
        if (value.isEmpty()) return null
        if (value.contains('/')) return null
        if (value == "." || value == "..") return null
        return value
    }
}
