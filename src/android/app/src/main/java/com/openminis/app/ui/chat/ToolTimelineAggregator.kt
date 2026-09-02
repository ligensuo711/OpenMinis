package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-tool-timeline] Pure-logic fold for the tool timeline view (stage 3.1).
 *
 * Flattens a conversation's tool blocks into chronological [Entry] rows and a
 * header summary. Zero Android / Compose dependencies so the fold and the
 * summary math can be asserted standalone in the sandbox (kotlinc) before the
 * Compose screen wires them up — same discipline as the stage-1/2 pure layers
 * (DangerousCommandScanner, ToolExecutionBudget, SearchProviders).
 *
 * ### What counts as an executable tool step
 * A block qualifies when `kind == "tool_use"` AND it carry a non-null
 * [AssistantBlock.toolStatus]. Thinking / text / info blocks and stream-status
 * blocks that never reached a terminal verdict are skipped — they are not tool
 * executions. The result is exactly the set of steps the agent actually ran.
 *
 * ### Ordering
 * Entries keep the in-message block order and the message-list order. For a
 * restored (from-DB) session every [AssistantBlock.startTimeMs] is 0, so the
 * timeline falls back to that stable structural order rather than a fake
 * clock. When live-captured timestamps exist (the turn just ran), they are
 * only used for the per-row time-of-day label, never for re-sorting — the
 * agent's execution order is authoritative.
 */
object ToolTimelineAggregator {

    /** One row of the timeline, pre-digested for the Compose screen. */
    data class Entry(
        /** Tool name as emitted by the model (shell_execute, file_write, …). */
        val toolName: String,
        val toolTitle: String,
        /** Raw tool-args JSON string (for [tag]/[summary] extraction). */
        val toolArgs: String,
        val status: String,
        /** End-to-end elapsed ms; 0 when unknown (restored session). */
        val durationMs: Long,
        /** Epoch-ms of execution start; 0 when unknown. */
        val startTimeMs: Long,
        /** Short human summary of the action (command / path / URL / query). */
        val summary: String,
    )

    data class Summary(
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val running: Int,
        val timedOut: Int,
        val cancelled: Int,
        val totalDurationMs: Long,
    )

    private val TERMINAL_OK = setOf("SUCCESS")
    private val TERMINAL_BAD = setOf("FAILED", "TIMEOUT", "CANCELLED")
    private val RUNNING = setOf("STREAMING", "PENDING", "RUNNING")

    /**
     * Flatten [messages]' tool blocks into timeline entries, then fold the
     * header summary. Single pass over the already-built block lists — no
     * re-parsing of persisted JSON here.
     */
    fun aggregate(messages: List<ChatMessage>): Pair<List<Entry>, Summary> {
        val entries = ArrayList<Entry>()
        var total = 0
        var succeeded = 0
        var failed = 0
        var running = 0
        var timedOut = 0
        var cancelled = 0
        var totalDurationMs = 0L

        for (msg in messages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName.isBlank()) continue
                val status = block.toolStatus ?: continue
                val statusName = status.name
                total++
                when (statusName) {
                    "SUCCESS" -> succeeded++
                    "FAILED" -> failed++
                    "TIMEOUT" -> timedOut++
                    "CANCELLED" -> cancelled++
                    in RUNNING -> running++
                }
                if (statusName !in RUNNING) totalDurationMs += block.durationMs
                entries.add(
                    Entry(
                        toolName = block.toolName,
                        toolTitle = block.toolTitle,
                        toolArgs = block.toolArgs,
                        status = statusName,
                        durationMs = block.durationMs,
                        startTimeMs = block.startTimeMs,
                        summary = summarize(block.toolArgs),
                    )
                )
            }
        }

        val summary = Summary(
            total = total,
            succeeded = succeeded,
            failed = failed,
            running = running,
            timedOut = timedOut,
            cancelled = cancelled,
            totalDurationMs = totalDurationMs,
        )
        return entries to summary
    }

    /**
     * Build a one-line action summary per tool. Mirrors the detail-sheet
     * extraction priorities (shell command → path/query/url → generic label)
     * so the timeline headline reads the same way the reveal sheet does.
     *
     * Deliberately tolerant: toolArgs may be a partial JSON buffer mid-stream;
     * [extractPartialJsonString] handles unclosed strings.
     */
    fun summarize(json: String): String {
        return try {
            val obj = JSONObject(json)
            when {
                obj.has("command") -> commandLabel(obj.optString("command", ""))
                obj.has("path") -> pathLabel(obj.optString("path", ""))
                obj.has("url") -> urlLabel(obj.optString("url", ""))
                obj.has("query") -> obj.optString("query", "")
                obj.has("title") -> obj.optString("title", "")
                obj.has("action") -> obj.optString("action", "")
                else -> ""
            }
        } catch (_: Exception) {
            // Partial JSON buffer: fall back to the raw-keyword extractor.
            partialSummary(json)
        }
    }

    private fun partialSummary(raw: String): String {
        for (key in listOf("command", "path", "url", "query", "title", "action")) {
            val p = extractPartialJsonString(key, raw)
            if (!p.isNullOrEmpty()) {
                return when (key) {
                    "command" -> commandLabel(p)
                    "path" -> pathLabel(p)
                    "url" -> urlLabel(p)
                    else -> p
                }
            }
        }
        return ""
    }

    private fun commandLabel(cmd: String): String {
        val s = cmd.trim()
        return if (s.isEmpty()) "" else s
    }

    private fun pathLabel(path: String): String {
        val s = path.trim()
        return if (s.isEmpty()) "" else s.substringAfterLast('/')
    }

    private fun urlLabel(url: String): String {
        val s = url.trim()
        if (s.isEmpty()) return ""
        return s.removePrefix("https://").removePrefix("http://")
    }
}