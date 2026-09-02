package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.rag.LocalIndex
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * [T-stage2-rag] `search_files` 工具：在沙箱本地文件里做相关性检索。
 *
 * ## 解决什么
 *
 * 在此之前，"在我攒的资料里找那段讲 X 的内容"只有两条路：`shell_execute`
 * 跑 grep（只能精确匹配，且一个词命中几十行没有排序），或者 `file_read`
 * 逐个文件读（几千行文档要翻十几轮，上下文烧光）。两者都不回答"哪一段
 * 最相关"。
 *
 * 本工具做的是：切块 → BM25 打分 → 返回最相关的若干片段，每条带
 * path + line，模型可以据此再 `file_read` 精读。
 *
 * ## 与 search_web 的分工
 *
 * `search_web` 查外网，`search_files` 查本地。两个都返回结构化结果，
 * 但本地检索不产生引用 id——本地文件不是需要溯源的外部主张，模型直接
 * 报路径和行号更有用。
 *
 * 打分与切块逻辑全在 [LocalIndex]（纯函数、可单测），这里只做文件遍历
 * 与安全边界。
 */
object LocalSearchTool {
    const val NAME = "search_files"
    private const val TAG = "LocalSearchTool"

    /** 默认检索根：会话工作区与共享目录，用户资料的实际所在。 */
    private val DEFAULT_ROOTS = listOf("/var/minis/workspace", "/var/minis/shared", "/var/minis/memory")

    /**
     * 单文件大小上限。超过就跳过——超大文件通常是数据集/日志，切块后
     * 会产生上千块把打分拖慢，且很少是用户"想找的那段话"。
     */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 扫描文件数上限，防止在巨大目录树上跑到超时。 */
    private const val MAX_FILES = 400

    /** 累计读入字符上限，控制内存占用。 */
    private const val MAX_TOTAL_CHARS = 6_000_000

    /**
     * 参与检索的文本扩展名。白名单而非黑名单：未知扩展名默认跳过，
     * 避免把 apk/so/db 之类二进制读进内存再靠 null 字节检测兜底。
     */
    private val TEXT_EXTENSIONS = setOf(
        "md", "txt", "markdown", "rst", "org", "log",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "swift", "c", "h", "cpp", "hpp",
        "rs", "go", "rb", "sh", "bash", "zsh", "smali", "gradle", "pro",
        "json", "yaml", "yml", "toml", "ini", "conf", "properties", "xml", "html", "css",
        "csv", "tsv", "sql", "graphql", "env",
    )

    /** 跳过的目录名：依赖与构建产物，命中了也没意义。 */
    private val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", ".gradle", "__pycache__", ".venv", "venv",
        "dist", "out", ".idea", ".cxx", "target",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the user's local files by relevance and get back the most pertinent passages, " +
            "each with its path and line number. Use this to answer \"where did I write about X\" / " +
            "\"find that note about Y\" instead of grepping via shell_execute (which cannot rank results) or " +
            "reading whole files one by one. Searches /var/minis/workspace, /var/minis/shared and " +
            "/var/minis/memory by default; pass 'path' to target one directory or file. " +
            "Ranking is lexical (BM25) — it matches the words you give it and does NOT understand synonyms, " +
            "so if results look wrong, retry with different wording (e.g. both '闪退' and '崩溃', both " +
            "'startup' and 'launch'). Follow up with file_read on a returned path+line to read the full context.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Search notes for Binder analysis'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "Keywords to look for. Prefer distinctive terms over common words."),
            "path" to AgentToolParam(
                "string",
                "Optional absolute path to restrict the search to a single directory or file " +
                    "(e.g. /var/minis/workspace/myproject). Omit to search the default roots.",
            ),
            "limit" to AgentToolParam("integer", "Maximum number of passages to return (default: 8, max: 20)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "path", "limit"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: 无法解析工具参数：${e.message}", false, toolTitle = NAME)
        }
        val toolTitle = args.optString("tool_title", NAME)
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' 不能为空。", false, toolTitle = toolTitle)
        }
        val limit = args.optInt("limit", 8).coerceIn(1, 20)
        val requestedPath = args.optString("path", "").trim()

        // 解析检索根。经 PRootKernel 做路径解析，与 file_read 同一套边界，
        // 不自行拼接宿主路径——那样会绕过沙箱隔离。
        val roots = mutableListOf<File>()
        if (requestedPath.isNotEmpty()) {
            val resolved = PRootKernel.resolveSessionHostPath(sessionId, requestedPath, context)
                ?: return ToolExecutionResult("Error: 无法解析路径：$requestedPath", false, toolTitle = toolTitle)
            if (!resolved.exists()) {
                return ToolExecutionResult("Error: 路径不存在：$requestedPath", false, toolTitle = toolTitle)
            }
            roots.add(resolved)
        } else {
            for (p in DEFAULT_ROOTS) {
                PRootKernel.resolveSessionHostPath(sessionId, p, context)
                    ?.takeIf { it.exists() }
                    ?.let { roots.add(it) }
            }
            if (roots.isEmpty()) {
                return ToolExecutionResult(
                    "默认检索目录都不存在（${DEFAULT_ROOTS.joinToString("、")}）。" +
                        "可以传 path 参数指定要检索的目录。",
                    false,
                    toolTitle = toolTitle,
                )
            }
        }

        return try {
            // 把宿主路径映射回沙箱路径展示：模型看到 /var/minis/... 才能直接
            // 拿去 file_read，看到宿主内部路径反而没法用。
            val chunks = mutableListOf<LocalIndex.Chunk>()
            var scanned = 0
            var totalChars = 0
            var skippedLarge = 0

            for (root in roots) {
                val displayRoot = if (requestedPath.isNotEmpty()) requestedPath else displayPathFor(root)
                walk(root) { file ->
                    if (scanned >= MAX_FILES || totalChars >= MAX_TOTAL_CHARS) return@walk false
                    if (file.length() > MAX_FILE_BYTES) {
                        skippedLarge++
                        return@walk true
                    }
                    if (file.extension.lowercase() !in TEXT_EXTENSIONS) return@walk true
                    val content = runCatching { file.readText() }.getOrNull() ?: return@walk true
                    // 二进制兜底：白名单扩展名的文件偶尔也可能是二进制。
                    if (content.contains('\u0000')) return@walk true
                    scanned++
                    totalChars += content.length
                    val docId = displayIdFor(file, root, displayRoot)
                    chunks.addAll(LocalIndex.chunk(docId, content))
                    true
                }
            }

            if (chunks.isEmpty()) {
                return ToolExecutionResult(
                    "在检索范围内没有找到可读的文本文件（已扫描 $scanned 个）。" +
                        "可以传 path 指定其他目录。",
                    false,
                    toolTitle = toolTitle,
                )
            }

            val hits = LocalIndex.search(chunks, query, limit)
            AppLogger.info(TAG, "search_files query=<<<${query.take(120)}>>> files=$scanned chunks=${chunks.size} hits=${hits.size}")

            if (hits.isEmpty()) {
                return ToolExecutionResult(
                    "在 $scanned 个文件（${chunks.size} 个片段）中没有匹配「$query」的内容。" +
                        "本检索是词法匹配、不理解近义词——换个说法再试（例如同时试中英文术语、" +
                        "或用文档里更可能出现的原词）。",
                    // 没找到不是错误：模型据此换词重查即可。
                    true,
                    toolTitle = toolTitle,
                )
            }

            val rendered = buildString {
                append(LocalIndex.render(query, hits, scanned))
                if (skippedLarge > 0) {
                    append("\n[已跳过 $skippedLarge 个超过 2 MB 的文件]")
                }
                if (scanned >= MAX_FILES) {
                    append("\n[已达 $MAX_FILES 个文件的扫描上限，结果可能不完整——传 path 缩小范围以获得更准的结果]")
                }
            }
            ToolExecutionResult(rendered, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "search_files failed: ${e.message}")
            ToolExecutionResult("检索失败：${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }

    /**
     * 深度优先遍历，[onFile] 返回 false 时中止整个遍历（配额用尽）。
     * 不跟随符号链接：沙箱里 mounts 下的软链可能指回上层造成无限递归。
     */
    private fun walk(root: File, onFile: (File) -> Boolean): Boolean {
        if (root.isFile) return onFile(root)
        val children = root.listFiles() ?: return true
        for (child in children) {
            if (child.name in SKIP_DIRS) continue
            if (child.name.startsWith(".") && child.isDirectory) continue
            // 符号链接：目录形式的软链跳过，避免环。
            val isLink = runCatching { child.canonicalPath != child.absolutePath }.getOrDefault(false)
            if (isLink && child.isDirectory) continue
            val keepGoing = if (child.isDirectory) walk(child, onFile) else onFile(child)
            if (!keepGoing) return false
        }
        return true
    }

    /** 宿主根目录 → 展示用沙箱路径。匹配不到时退回绝对路径。 */
    private fun displayPathFor(root: File): String {
        val abs = root.absolutePath
        for (p in DEFAULT_ROOTS) {
            if (abs.endsWith(p.removePrefix("/")) || abs.endsWith(p)) return p
        }
        return abs
    }

    /** 文件 → 展示 id：以 [displayRoot] 为前缀拼相对路径。 */
    private fun displayIdFor(file: File, root: File, displayRoot: String): String {
        val rel = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
        return if (rel.isEmpty()) displayRoot else "${displayRoot.trimEnd('/')}/$rel"
    }
}
