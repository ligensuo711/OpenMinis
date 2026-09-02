package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.logging.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * [T-stage2-search] `search_web` 工具：结构化联网搜索 + 可溯源引用。
 *
 * 在此之前 Agent 查资料只能 `browser_use` 导航到搜索引擎再抓取正文——
 * 3~6 轮工具调用、结果结构不稳定、无法生成可点击引用。本工具把它压成
 * 一次调用，并让模型用 `[cite:id]` 标注每条事实的来源。
 *
 * 纯逻辑（Provider 选择、URL 归一化、结果清洗、引用 id、渲染）都在
 * [SearchProviders] 里，可独立单测；这里只负责发请求与解析响应。
 */
object SearchTool {
    const val NAME = "search_web"
    private const val TAG = "SearchTool"

    /** 搜索请求超时：宁可失败也不要卡住 Agent 循环。 */
    private const val TIMEOUT_SECONDS = 20L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the web for current information, news, and real-time data. " +
            "Use this when the user asks about recent events, current prices or live data; when you need to " +
            "verify a fact you are unsure about or that may have changed; or when the user references something " +
            "you have no context on (a product, person, API, document). Do NOT use it for math, code reasoning, " +
            "or well-known facts you can answer from training. Write focused keyword queries, not full sentences. " +
            "You may call it multiple times to broaden coverage — for example repeat the query translated into " +
            "English for technical topics, or into the local language for regional news. " +
            "Returns items[] with index / id / title / url / text, plus an optional pre-synthesized answer. " +
            "Cite every statement a result supports by appending [cite:id] with that result's exact id field.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Search latest Kotlin release notes'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "The search query to look up online. Prefer focused keywords over full sentences."),
            "provider" to AgentToolParam(
                "string",
                "Optional search backend override: tavily / exa / searxng / duckduckgo. " +
                    "Omit to use the best backend the user has configured (falls back to DuckDuckGo, which needs no key).",
            ),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "provider"),
    )

    /**
     * 执行搜索。任何失败都返回 `success = false` 的结果而不是抛异常，
     * 让模型在下一轮看到原因并自行决定换 Provider 还是改查询词。
     */
    fun execute(argsJson: String, context: Context): ToolExecutionResult {
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

        val env = try {
            EnvVarRepository(context).allAsDict()
        } catch (e: Exception) {
            AppLogger.warning(TAG, "读取环境变量失败：${e.message}")
            emptyMap()
        }
        val configured = env.filterValues { it.isNotBlank() }.keys
        val provider = SearchProviders.resolveProvider(
            configuredEnv = configured,
            preferred = args.optString("provider", "").ifBlank { null },
        )

        AppLogger.info(TAG, "search provider=${provider.id} query=<<<${query.take(200)}>>>")

        return try {
            val outcome = when (provider) {
                SearchProviders.Provider.TAVILY -> searchTavily(query, env["TAVILY_API_KEY"].orEmpty())
                SearchProviders.Provider.EXA -> searchExa(query, env["EXA_API_KEY"].orEmpty())
                SearchProviders.Provider.SEARXNG -> searchSearxng(query, env["SEARXNG_URL"].orEmpty())
                SearchProviders.Provider.DUCKDUCKGO -> searchDuckDuckGo(query)
            }
            if (outcome.items.isEmpty() && outcome.answer == null) {
                return ToolExecutionResult(
                    "搜索没有返回结果（provider=${provider.displayName}）。可以换用更宽泛的关键词，" +
                        "或指定 provider 参数改用其他后端。",
                    false,
                    toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(SearchProviders.renderForModel(outcome), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "search failed provider=${provider.id}: ${e.message}")
            ToolExecutionResult(
                "搜索失败（provider=${provider.displayName}）：${e.message ?: e.javaClass.simpleName}。" +
                    "可以稍后重试，或指定 provider 参数改用其他后端。",
                false,
                toolTitle = toolTitle,
            )
        }
    }

    // ── Provider 实现 ──────────────────────────────────────────

    private fun searchTavily(query: String, apiKey: String): SearchProviders.SearchOutcome {
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "basic")
            put("include_answer", true)
            put("max_results", SearchProviders.MAX_ITEMS)
        }
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val json = executeJson(request)
        val raw = mutableListOf<Triple<String, String, String>>()
        json.optJSONArray("results")?.forEachObject { item ->
            raw.add(
                Triple(
                    item.optString("title", ""),
                    item.optString("url", ""),
                    item.optString("content", ""),
                )
            )
        }
        return SearchProviders.buildOutcome(
            SearchProviders.Provider.TAVILY, query,
            json.optString("answer", "").ifBlank { null },
            raw,
        )
    }

    private fun searchExa(query: String, apiKey: String): SearchProviders.SearchOutcome {
        val body = JSONObject().apply {
            put("query", query)
            put("numResults", SearchProviders.MAX_ITEMS)
            put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", SearchProviders.MAX_SNIPPET_CHARS)))
        }
        val request = Request.Builder()
            .url("https://api.exa.ai/search")
            .addHeader("x-api-key", apiKey)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val json = executeJson(request)
        val raw = mutableListOf<Triple<String, String, String>>()
        json.optJSONArray("results")?.forEachObject { item ->
            raw.add(
                Triple(
                    item.optString("title", ""),
                    item.optString("url", ""),
                    item.optString("text", "").ifBlank { item.optString("snippet", "") },
                )
            )
        }
        return SearchProviders.buildOutcome(SearchProviders.Provider.EXA, query, null, raw)
    }

    private fun searchSearxng(query: String, baseUrl: String): SearchProviders.SearchOutcome {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) error("SEARXNG_URL 未配置")
        val url = "$root/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        val json = executeJson(request)
        val raw = mutableListOf<Triple<String, String, String>>()
        json.optJSONArray("results")?.forEachObject { item ->
            raw.add(
                Triple(
                    item.optString("title", ""),
                    item.optString("url", ""),
                    item.optString("content", ""),
                )
            )
        }
        return SearchProviders.buildOutcome(SearchProviders.Provider.SEARXNG, query, null, raw)
    }

    /**
     * DuckDuckGo 零配置后端：用 html 端点再解析结果块。
     *
     * 这是兜底路径，解析基于 DDG 的 class 结构，上游改版可能失效——
     * 所以失败时给模型明确提示"换 provider 或配置 key"，而不是静默返回空。
     */
    private fun searchDuckDuckGo(query: String): SearchProviders.SearchOutcome {
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            // 不带 UA 时 DDG 会返回精简页面，结果块 class 也不同。
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .get()
            .build()
        val html = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return SearchProviders.buildOutcome(
            SearchProviders.Provider.DUCKDUCKGO, query, null, parseDuckDuckGoHtml(html),
        )
    }

    /**
     * 从 DDG html 结果页抽取 (title, url, snippet)。
     *
     * 内部可见以便单测直接喂固定 HTML 片段（不发网络请求）。
     */
    internal fun parseDuckDuckGoHtml(html: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        // 结果链接：<a ... class="result__a" href="...">标题</a>
        val linkRe = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        // 摘要：<a ... class="result__snippet" ...>摘要</a> 或 <div class="result__snippet">
        val snippetRe = Regex(
            """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
        links.forEachIndexed { i, m ->
            val href = decodeDdgRedirect(m.groupValues[1])
            val title = stripHtml(m.groupValues[2])
            val snippet = snippets.getOrElse(i) { "" }
            if (href.isNotBlank() && title.isNotBlank()) out.add(Triple(title, href, snippet))
        }
        return out
    }

    /**
     * DDG 的结果链接是 `//duckduckgo.com/l/?uddg=<encoded>&...` 跳转形式，
     * 取出 uddg 参数还原真实地址；非跳转形式原样返回。
     */
    internal fun decodeDdgRedirect(href: String): String {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return href
        val tail = href.substring(idx + marker.length)
        val encoded = tail.substringBefore('&')
        return try {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    /** 去标签 + 常见实体反转义，供解析 HTML 结果用。内部可见以便单测。 */
    internal fun stripHtml(fragment: String): String =
        fragment
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── 工具方法 ───────────────────────────────────────────────

    private fun executeJson(request: Request): JSONObject =
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 把上游错误正文的前若干字符带出来，便于模型判断是 key 无效
                // 还是配额用尽，而不是只看到一个 HTTP 码。
                error("HTTP ${resp.code} ${text.take(300)}")
            }
            if (text.isBlank()) error("上游返回空响应")
            JSONObject(text)
        }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) {
            optJSONObject(i)?.let(action)
        }
    }
}
