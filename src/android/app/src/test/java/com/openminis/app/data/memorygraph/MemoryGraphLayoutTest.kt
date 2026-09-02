package com.openminis.app.data.memorygraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphLayoutTest {

    @Test
    fun buildsNodesWithLabels() {
        val files = listOf(
            MemoryGraphLayout.FileInput("GLOBAL.md", "## prefs\nlanguage kotlin", true),
            MemoryGraphLayout.FileInput("2026-08-25.md", "## a\nkotlin 分析", false),
        )
        val g = MemoryGraphLayout.build(files)
        assertEquals(2, g.nodes.size)
        assertEquals("GLOBAL", g.nodes[0].label)
        assertEquals("2026-08-25", g.nodes[1].label)
        assertTrue(g.nodes[0].isGlobal)
    }

    @Test
    fun edgesConnectSharedTerms() {
        val files = listOf(
            MemoryGraphLayout.FileInput("GLOBAL.md", "## prefs\nkotlin compose", true),
            MemoryGraphLayout.FileInput("2026-08-25.md", "## a\nkotlin 分析", false),
            MemoryGraphLayout.FileInput("2026-08-26.md", "## b\nunrelated", false),
        )
        val g = MemoryGraphLayout.build(files)
        assertEquals(1, g.edges.size)
        assertEquals("kotlin", g.edges[0].keyword)
    }

    @Test
    fun termsFilterStopwordsAndEmitCjkBigrams() {
        val t = MemoryGraphLayout.terms("the kotlin 分析一下 我们")
        assertTrue(t.contains("kotlin"))
        assertTrue(!t.contains("the"))
        assertTrue(t.any { it == "分析" || it == "析一" || it == "一下" })
        assertTrue(!t.contains("我们"))
    }

    @Test
    fun entryCountCountsHeadings() {
        assertEquals(3, MemoryGraphLayout.entryCount("## a\n## b\n## c"))
        assertEquals(0, MemoryGraphLayout.entryCount("no headings"))
    }

    @Test
    fun ringLayoutIsDeterministicAndSized() {
        val pts = MemoryGraphLayout.layout(4, 0f, 0f, 10f)
        assertEquals(4, pts.size)
        assertTrue(Math.abs(pts[0].x) < 1e-4f)
        assertTrue(Math.abs(pts[0].y - (-10f)) < 1e-4f)
        assertEquals(1, MemoryGraphLayout.layout(1, 5f, 5f, 10f).size)
        assertEquals(0, MemoryGraphLayout.layout(0, 0f, 0f, 10f).size)
    }
}