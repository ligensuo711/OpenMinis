package com.openminis.app.data.memorygraph

import kotlin.math.cos
import kotlin.math.sin

/**
 * [T-memory-graph] Pure-logic memory-graph model + layout for the memory
 * visualisation screen (stage 3.2). Zero Android / Compose dependencies —
 * the node/edge build and the deterministic layout are asserted standalone in
 * the sandbox before the Compose screen draws them.
 *
 * ### Data
 * Nodes = memory files (GLOBAL.md + YYYY-MM-DD.md). Edges = shared "significant
 * terms" between two files — lexical, not semantic: two files that both
 * mention the same word/bigram get an edge whose weight is the shared count.
 * This mirrors the lexical (not vector) stance of stage-2 search_files — the
 * user usually remembers the *word*, not the *meaning*.
 *
 * ### Layout
 * Deterministic ring layout: nodes sit evenly on a circle (ordered by file
 * date so GLOBAL.md anchors position 0 and daily logs fan out clockwise).
 * This is intentionally NOT a force-directed sim — a ring is stable, cheap,
 * and reads as a clean "memory timeline wheel" under the liquid-glass palette,
 * which suits the restrained aesthetic better than a jittery physics layout.
 */
object MemoryGraphLayout {

    data class FileInput(
        val name: String,
        val content: String,
        val isGlobal: Boolean,
    )

    data class Node(
        val id: String,
        val label: String,
        val size: Float,
        val isGlobal: Boolean,
        val entryCount: Int,
    )

    data class Edge(
        val from: String,
        val to: String,
        val weight: Int,
        /** One representative shared term for the UI tooltip. */
        val keyword: String,
    )

    data class Graph(
        val nodes: List<Node>,
        val edges: List<Edge>,
    )

    data class Point(val x: Float, val y: Float)

    private val EN_STOP = setOf(
        "the", "and", "for", "with", "that", "this", "from", "have", "are",
        "was", "not", "you", "your", "but", "all", "can", "has", "had",
        "will", "would", "what", "when", "where", "which", "there", "their",
        "then", "than", "they", "them", "into", "over", "just", "more",
    )
    private val ZH_STOP_BIGRAM = setOf(
        "我们", "一个", "这个", "那个", "什么", "怎么", "可以", "就是", "还是",
        "没有", "不是", "已经", "还是", "因为", "所以", "如果", "这些", "那些",
    )

    /** Split a file's text into significant terms (lowercased). */
    fun terms(content: String): Set<String> {
        val out = LinkedHashSet<String>()
        // English / identifier words: 3+ chars.
        Regex("[A-Za-z][A-Za-z0-9_]{2,}").findAll(content).forEach {
            val w = it.value.lowercase()
            if (w !in EN_STOP) out.add(w)
        }
        // CJK bigrams over contiguous CJK runs.
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(content).forEach { run ->
            val s = run.value
            for (i in 0 until s.length - 1) {
                val bg = s.substring(i, i + 2)
                if (bg !in ZH_STOP_BIGRAM) out.add(bg)
            }
        }
        return out
    }

    /** Count of dated memory entries (## heading count) for node sizing. */
    fun entryCount(content: String): Int =
        Regex("(?m)^##[ \t]+").findAll(content).count().coerceAtLeast(0)

    fun build(files: List<FileInput>): Graph {
        val nodes = files.mapIndexed { index, f ->
            Node(
                id = f.name,
                label = if (f.isGlobal) "GLOBAL" else f.name.removeSuffix(".md"),
                size = 1f + (entryCount(f.content).coerceAtMost(20)) * 0.25f,
                isGlobal = f.isGlobal,
                entryCount = entryCount(f.content),
            )
        }
        val termMap = files.associate { it.name to terms(it.content) }

        val edges = ArrayList<Edge>()
        for (i in files.indices) {
            for (j in i + 1 until files.size) {
                val a = files[i].name
                val b = files[j].name
                val shared = termMap[a]!!.intersect(termMap[b]!!)
                if (shared.isNotEmpty()) {
                    edges.add(
                        Edge(
                            from = a,
                            to = b,
                            weight = shared.size,
                            keyword = shared.first(),
                        )
                    )
                }
            }
        }
        return Graph(nodes = nodes, edges = edges)
    }

    /**
     * Deterministic ring layout. Node [index] of [nodeCount] sits at angle
     * `index * 2π/n - π/2` (start at top), on a circle of [radius], centered
     * at ([cx], [cy]). GLOBAL (index 0) is always the first node if the caller
     * kept it at the head of the list.
     */
    fun layout(nodeCount: Int, cx: Float, cy: Float, radius: Float): List<Point> {
        if (nodeCount <= 0) return emptyList()
        if (nodeCount == 1) return listOf(Point(cx, cy))
        val pts = ArrayList<Point>(nodeCount)
        for (i in 0 until nodeCount) {
            val angle = (2.0 * Math.PI * i / nodeCount) - (Math.PI / 2.0)
            pts.add(Point(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat()))
        }
        return pts
    }
}