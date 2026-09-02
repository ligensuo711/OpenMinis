package com.openminis.app.rag

/**
 * [T-stage2-rag] 本地语料检索的纯逻辑核心：分词、切块、BM25 打分、片段高亮。
 *
 * ## 为什么是词法检索而不是向量检索
 *
 * 设备上没有可用的 embedding 模型，要做向量检索就得打包一个句向量模型
 * （几十 MB 资产 + 推理链路 + 首次预热），而收益仅限"近义词也能命中"。
 * 对本工具的真实用途——"在我攒的文件里找那段讲 X 的内容"——BM25 已经足够：
 * 用户通常记得关键词，只是不记得在哪个文件。
 *
 * 代价要说清：**同义/近义查询是短板**（搜"崩溃"找不到只写了"闪退"的文档）。
 * 因此工具描述里明确建议模型在结果不佳时换同义词重查，而不是让它以为
 * 检索器有语义理解能力。
 *
 * ## 中文处理
 *
 * 无分词词典，改用**二元切分**（"性能优化" → "性能","能优","优化"）。
 * 这是搜索引擎处理 CJK 的标准兜底手段：召回略宽但不漏，且完全不需要词典。
 *
 * 全部纯函数，零 Android / IO 依赖，可直接单测。
 */
object LocalIndex {

    // ── 分词 ────────────────────────────────────────────────────

    /** 单个 CJK 字符判定（覆盖中日韩统一表意文字与扩展 A、假名）。 */
    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // CJK 统一表意文字
            (c in 0x3400..0x4DBF) ||      // 扩展 A
            (c in 0x3040..0x30FF) ||      // 平假名 / 片假名
            (c in 0xAC00..0xD7AF)         // 韩文音节
    }

    /**
     * 切词。拉丁文/数字按词切并转小写；CJK 走二元切分。
     *
     * 单个 CJK 字符成词时也保留（"猫"这类单字查询否则会一无所获），
     * 但只在整段就一个字时——否则 bigram 已经覆盖。
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()

        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens.add(latin.toString().lowercase())
                latin.clear()
            }
        }

        val cjkRun = StringBuilder()
        fun flushCjk() {
            when {
                cjkRun.length == 1 -> tokens.add(cjkRun.toString())
                cjkRun.length > 1 -> for (i in 0 until cjkRun.length - 1) {
                    tokens.add(cjkRun.substring(i, i + 2))
                }
            }
            cjkRun.clear()
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushLatin()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() || ch == '_' -> {
                    flushCjk()
                    latin.append(ch)
                }
                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return tokens
    }

    // ── 切块 ────────────────────────────────────────────────────

    /** 单块目标字符数。够装一两段完整论述，又不至于让命中位置模糊。 */
    const val CHUNK_TARGET_CHARS = 900

    /**
     * 相邻块重叠字符数。跨块边界的句子否则会被拦腰截断，两块都得不到
     * 完整语义——重叠是检索系统里对付这个问题的标准做法。
     */
    const val CHUNK_OVERLAP_CHARS = 150

    /**
     * 一个可检索片段。
     *
     * @param docId 所属文档标识（通常是文件路径）
     * @param ordinal 该文档内的块序号，从 0 起
     * @param startLine 块首在原文中的行号（1 起），让结果能定位回文件
     * @param text 块原文
     */
    data class Chunk(
        val docId: String,
        val ordinal: Int,
        val startLine: Int,
        val text: String,
    )

    /**
     * 把文档切成带重叠的块，优先在段落/行边界处断开。
     *
     * 按行累积而不是按字符硬切：硬切会把代码块和表格切碎，按行至少保住
     * 结构完整的最小单位。
     */
    fun chunk(docId: String, content: String): List<Chunk> {
        if (content.isBlank()) return emptyList()
        val lines = content.lines()
        val chunks = mutableListOf<Chunk>()
        var buffer = StringBuilder()
        var bufferStartLine = 1

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) {
                chunks.add(Chunk(docId, chunks.size, bufferStartLine, text))
            }
        }

        var lineNo = 0
        for (line in lines) {
            lineNo++
            if (buffer.isEmpty()) bufferStartLine = lineNo
            buffer.append(line).append('\n')
            if (buffer.length >= CHUNK_TARGET_CHARS) {
                flush()
                // 留下尾部重叠，并把起始行号回退到重叠部分的实际开头。
                val tail = buffer.toString().takeLast(CHUNK_OVERLAP_CHARS)
                val overlapLines = tail.count { it == '\n' }
                buffer = StringBuilder(tail)
                bufferStartLine = (lineNo - overlapLines + 1).coerceAtLeast(1)
            }
        }
        flush()
        return chunks
    }

    // ── BM25 ────────────────────────────────────────────────────

    /**
     * 词频饱和参数。1.2 是 BM25 的通用默认值：一个词出现 10 次的文档
     * 不该比出现 2 次的强 5 倍。
     */
    private const val K1 = 1.2

    /** 长度归一化强度。0.75 为通用默认，抑制长文档单纯因为词多而占优。 */
    private const val B = 0.75

    /** 一条检索结果。[score] 为 BM25 得分，越大越相关。 */
    data class Hit(
        val chunk: Chunk,
        val score: Double,
        val matchedTerms: List<String>,
    )

    /**
     * 在 [chunks] 上检索 [query]，返回按得分降序的前 [limit] 条。
     *
     * 得分为 0（一个查询词都没命中）的块直接丢弃——宁可返回空结果让模型
     * 换词重查，也不要塞一堆无关片段冒充"最相关"。
     */
    fun search(chunks: List<Chunk>, query: String, limit: Int = 8): List<Hit> {
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

        // 预计算每块词频与长度。
        val tokenizedChunks = chunks.map { it to tokenize(it.text) }
        val totalDocs = tokenizedChunks.size
        val avgLength = tokenizedChunks.sumOf { it.second.size }.toDouble() / totalDocs

        // 文档频率：含该词的块数。
        val docFreq = mutableMapOf<String, Int>()
        for ((_, tokens) in tokenizedChunks) {
            for (term in tokens.toSet()) {
                if (term in queryTerms) docFreq[term] = (docFreq[term] ?: 0) + 1
            }
        }

        val hits = mutableListOf<Hit>()
        for ((chunk, tokens) in tokenizedChunks) {
            if (tokens.isEmpty()) continue
            val freq = mutableMapOf<String, Int>()
            for (tk in tokens) if (tk in queryTerms) freq[tk] = (freq[tk] ?: 0) + 1
            if (freq.isEmpty()) continue

            var score = 0.0
            val matched = mutableListOf<String>()
            for (term in queryTerms) {
                val tf = freq[term] ?: continue
                val df = docFreq[term] ?: continue
                // 加一平滑的 IDF，保证非负：出现在所有文档里的词权重趋近 0
                // 而不是变成负数把整块得分拖下去。
                val idf = kotlin.math.ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * tokens.size / avgLength))
                score += idf * norm
                matched.add(term)
            }
            if (score > 0.0) hits.add(Hit(chunk, score, matched))
        }
        // 同分时按文档路径与块序稳定排序，保证结果可复现。
        return hits.sortedWith(
            compareByDescending<Hit> { it.score }
                .thenBy { it.chunk.docId }
                .thenBy { it.chunk.ordinal },
        ).take(limit)
    }

    // ── 片段提取 ────────────────────────────────────────────────

    /** 返回给模型的片段长度上限，控制上下文占用。 */
    const val SNIPPET_CHARS = 400

    /**
     * 从命中块里截取**围绕首个命中词**的片段，而不是无脑取开头。
     *
     * 取开头会让"关键词在块尾"的命中看起来毫不相关，模型于是判断错误。
     */
    fun snippet(chunk: Chunk, matchedTerms: List<String>): String {
        val text = chunk.text
        if (text.length <= SNIPPET_CHARS) return text.trim()

        val lower = text.lowercase()
        val pos = matchedTerms.asSequence()
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: 0

        var start = (pos - SNIPPET_CHARS / 3).coerceAtLeast(0)
        var end = (start + SNIPPET_CHARS).coerceAtMost(text.length)
        // 贴到结尾时把窗口往前挪，避免片段短于上限。
        if (end - start < SNIPPET_CHARS) start = (end - SNIPPET_CHARS).coerceAtLeast(0)

        // 尽量对齐到词/行边界，避免片段从半个单词开始。
        if (start > 0) {
            val nl = text.indexOf('\n', start)
            if (nl in start until start + 80) start = nl + 1
        }
        if (end < text.length) {
            val nl = text.lastIndexOf('\n', end)
            if (nl > start && end - nl < 80) end = nl
        }

        val core = text.substring(start, end.coerceAtLeast(start)).trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + core + suffix
    }

    /**
     * 渲染检索结果。用 JSON 让模型稳定取 path / line 去做后续 file_read。
     */
    fun render(query: String, hits: List<Hit>, scannedFiles: Int): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"query\": ").append(json(query)).append(",\n")
        sb.append("  \"scanned_files\": ").append(scannedFiles).append(",\n")
        sb.append("  \"hits\": [\n")
        hits.forEachIndexed { i, hit ->
            sb.append("    {")
            sb.append("\"rank\": ").append(i + 1).append(", ")
            sb.append("\"path\": ").append(json(hit.chunk.docId)).append(", ")
            sb.append("\"line\": ").append(hit.chunk.startLine).append(", ")
            sb.append("\"score\": ").append(String.format("%.3f", hit.score)).append(", ")
            sb.append("\"text\": ").append(json(snippet(hit.chunk, hit.matchedTerms)))
            sb.append("}")
            if (i != hits.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun json(s: String): String {
        val out = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append("\"")
        return out.toString()
    }
}
