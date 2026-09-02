package com.openminis.app.tools

/**
 * [T-stage2-search] 搜索结果的纯逻辑部分：Provider 选择、请求 URL 构造、
 * 响应解析、结果归一化与引用 id 生成。
 *
 * 刻意与网络 IO 分离：这里全部是纯函数，零 Android / OkHttp 依赖，
 * 可以在 JVM 单测和沙箱 kotlinc 里直接断言（与 DangerousCommandScanner
 * 同一套路）。真正发请求的部分在 [SearchTool]。
 */
object SearchProviders {

    /** 支持的搜索后端。按"是否需要 API Key"分两类。 */
    enum class Provider(val id: String, val displayName: String, val envKey: String?) {
        /** 自托管 / 公共 SearXNG 实例，免 key。地址来自 SEARXNG_URL。 */
        SEARXNG("searxng", "SearXNG", "SEARXNG_URL"),

        /** DuckDuckGo HTML 端点，免 key，无需配置。 */
        DUCKDUCKGO("duckduckgo", "DuckDuckGo", null),

        /** Tavily，需 TAVILY_API_KEY。返回结构最规整，带 answer。 */
        TAVILY("tavily", "Tavily", "TAVILY_API_KEY"),

        /** Exa，需 EXA_API_KEY。语义检索质量高。 */
        EXA("exa", "Exa", "EXA_API_KEY");

        companion object {
            fun fromId(id: String?): Provider? =
                entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
        }
    }

    /**
     * 单条搜索结果。[id] 是短 id，模型用 `[cite:id]` 引用它。
     */
    data class SearchItem(
        val index: Int,
        val id: String,
        val title: String,
        val url: String,
        val text: String,
    )

    /** 一次搜索的归一化结果。[answer] 只有部分 Provider（Tavily）会给。 */
    data class SearchOutcome(
        val provider: Provider,
        val query: String,
        val answer: String?,
        val items: List<SearchItem>,
    )

    /**
     * 按"哪些环境变量已配置"选出可用 Provider，优先级：
     * Tavily > Exa > SearXNG > DuckDuckGo。
     *
     * 前两个质量最好但要 key；SearXNG 需要用户自己填实例地址；
     * DuckDuckGo 作为零配置兜底，保证 search_web 永远可用（Tier 0）。
     */
    fun resolveProvider(configuredEnv: Set<String>, preferred: String? = null): Provider {
        Provider.fromId(preferred)?.let { p ->
            if (p.envKey == null || p.envKey in configuredEnv) return p
        }
        return when {
            Provider.TAVILY.envKey in configuredEnv -> Provider.TAVILY
            Provider.EXA.envKey in configuredEnv -> Provider.EXA
            Provider.SEARXNG.envKey in configuredEnv -> Provider.SEARXNG
            else -> Provider.DUCKDUCKGO
        }
    }

    /** 生成引用用的短 id：query + url 的稳定哈希前 6 位十六进制。 */
    fun citationId(query: String, url: String, index: Int): String {
        var h = 0x811c9dc5.toInt()          // FNV-1a 32bit offset basis
        for (ch in (query + "\u0000" + url + "\u0000" + index)) {
            h = h xor ch.code
            h *= 0x01000193                  // FNV prime
        }
        return "%06x".format(h and 0xffffff)
    }

    /** 归一化 URL：补协议、去首尾引号、处理协议相对地址。 */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return u
        if ((u.startsWith("\"") && u.endsWith("\"")) || (u.startsWith("'") && u.endsWith("'"))) {
            u = u.substring(1, u.length - 1).trim()
        }
        if (u.isEmpty()) return u
        if (u.startsWith("//")) return "https:$u"
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) return "https://$u"
        return u
    }

    /** 单条摘要长度上限，防止一次搜索灌满上下文。 */
    const val MAX_SNIPPET_CHARS = 600

    /** 返回给模型的最大条目数。 */
    const val MAX_ITEMS = 8

    fun truncateSnippet(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_SNIPPET_CHARS) clean
        else clean.take(MAX_SNIPPET_CHARS).trimEnd() + "…"
    }

    /**
     * 把各 Provider 的原始 (title, url, snippet) 三元组归一化成带 id 的结果集。
     * 空 url 与空标题的条目丢弃；按 [MAX_ITEMS] 截断。
     */
    fun buildOutcome(
        provider: Provider,
        query: String,
        answer: String?,
        rawItems: List<Triple<String, String, String>>,
    ): SearchOutcome {
        val items = rawItems.asSequence()
            .map { (title, url, snippet) ->
                Triple(title.trim(), normalizeUrl(url), truncateSnippet(snippet))
            }
            .filter { (title, url, _) -> url.isNotEmpty() && title.isNotEmpty() }
            .distinctBy { (_, url, _) -> url }
            .take(MAX_ITEMS)
            .mapIndexed { i, (title, url, snippet) ->
                SearchItem(
                    index = i + 1,
                    id = citationId(query, url, i + 1),
                    title = title,
                    url = url,
                    text = snippet,
                )
            }
            .toList()
        return SearchOutcome(provider, query, answer?.trim()?.ifEmpty { null }, items)
    }

    /**
     * 渲染给模型的文本结果。用 JSON 而不是自然语言，让模型稳定地取 id 做引用。
     */
    fun renderForModel(outcome: SearchOutcome): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"provider\": ").append(jsonStr(outcome.provider.displayName)).append(",\n")
        sb.append("  \"query\": ").append(jsonStr(outcome.query)).append(",\n")
        if (outcome.answer != null) {
            sb.append("  \"answer\": ").append(jsonStr(outcome.answer)).append(",\n")
        }
        sb.append("  \"items\": [\n")
        outcome.items.forEachIndexed { i, item ->
            sb.append("    {")
            sb.append("\"index\": ").append(item.index).append(", ")
            sb.append("\"id\": ").append(jsonStr(item.id)).append(", ")
            sb.append("\"title\": ").append(jsonStr(item.title)).append(", ")
            sb.append("\"url\": ").append(jsonStr(item.url)).append(", ")
            sb.append("\"text\": ").append(jsonStr(item.text))
            sb.append("}")
            if (i != outcome.items.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
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

    /** 系统提示里注入的引用规则。放在这里与工具定义同源，避免两处措辞漂移。 */
    val CITATION_PROMPT: String = """
<citations>
当回答中的某个陈述来自 search_web 结果时，紧跟该陈述后面追加引用标记：[cite:id]，
其中 id 是对应结果条目的 `id` 字段原文。
- 示例："该活动在昨天下午举行。[cite:a1b2c3]"
- 多个结果支持同一陈述时可以连写：[cite:a1b2c3][cite:d4e5f6]
- id 必须逐字复制工具返回的值，不要臆造、重新编号或复用其他结果的 id。
- 标记就近放在被支撑的陈述之后（标点之后），不要收集到回答末尾，
  也不要额外写"参考资料"或"来源"小节——应用会根据行内标记渲染引用。
- 来自你自身知识的陈述不加标记。
</citations>
""".trimIndent()
}
