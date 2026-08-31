package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stage5-self-improvement] 自我改进闭环纯逻辑回归测试。
 *
 * 关键契约：
 *  - 只有「失败 → 同工具后续成功」才产生 lesson；签名相同的成功不算（瞬时重试）
 *  - 不同工具的成功不交叉计数
 *  - signature 归一化：折叠空白/换行、截断 —— 防注入 + 防重复
 *  - merge 同 key 去重累加，超 cap 淘汰低分
 *  - fragment：null-safe、只含 enabled、按 hits 降序截断、无换行注入
 */
class SelfImprovementTest {

    private val t0 = 1_000L

    private fun event(tool: String, sig: String, ok: Boolean, at: Long) =
        SelfImprovement.ToolEvent(tool, sig, ok, at)

    @Test
    fun `failure followed by same-tool success yields a lesson`() {
        val lessons = SelfImprovement.extract(
            listOf(
                event("shell_execute", "grep -r foo .", false, t0),
                event("shell_execute", "grep -rl foo .", true, t0 + 10),
            ),
            now = t0 + 20,
        )
        assertEquals(1, lessons.size)
        assertEquals("shell_execute", lessons[0].toolName)
        assertEquals("grep -r foo .", lessons[0].trigger)
        assertEquals("grep -rl foo .", lessons[0].guidance)
        assertEquals(1, lessons[0].hits)
    }

    @Test
    fun `failure with no recovery yields nothing`() {
        val lessons = SelfImprovement.extract(
            listOf(event("file_read", "/nope/x.txt", false, t0)),
            now = t0 + 10,
        )
        assertTrue(lessons.isEmpty())
    }

    @Test
    fun `identical-signature success is a transient retry not a lesson`() {
        val lessons = SelfImprovement.extract(
            listOf(
                event("shell_execute", "curl api", false, t0),
                event("shell_execute", "curl api", true, t0 + 5),
            ),
            now = t0 + 10,
        )
        assertTrue(lessons.isEmpty())
    }

    @Test
    fun `other tool's success does not rescue a failure`() {
        val lessons = SelfImprovement.extract(
            listOf(
                event("shell_execute", "make build", false, t0),
                event("file_write", "make build", true, t0 + 5),
            ),
            now = t0 + 10,
        )
        assertTrue(lessons.isEmpty())
    }

    @Test
    fun `events are time-sorted before scanning`() {
        // 乱序输入（时间戳在后的事件排在前面）依然正确配对
        val lessons = SelfImprovement.extract(
            listOf(
                event("shell_execute", "grep -rl foo .", true, t0 + 10),
                event("shell_execute", "grep -r foo .", false, t0),
            ),
            now = t0 + 20,
        )
        assertEquals(1, lessons.size)
        assertEquals("grep -r foo .", lessons[0].trigger)
    }

    @Test
    fun `duplicate failures dedupe to one lesson`() {
        val lessons = SelfImprovement.extract(
            listOf(
                event("file_edit", "old_string=A", false, t0),
                event("file_edit", "old_string=A", false, t0 + 1),
                event("file_edit", "old_string=B", true, t0 + 2),
            ),
            now = t0 + 3,
        )
        assertEquals(1, lessons.size)
    }

    @Test
    fun `signature collapses whitespace strips newlines and truncates`() {
        val raw = "cmd  a\n\tb   c\ncat /etc/shadow-long-path-abcdefghijklmnopqrstuvwxyz0123456789"
        val sig = SelfImprovement.signature(raw, maxLen = 40)
        assertTrue(sig.length <= 40)
        assertTrue(!sig.contains('\n'))
        assertTrue(!sig.contains('\t'))
        assertTrue(sig.endsWith("…"))
        // 折叠空白后正常短输入保持原样（去首尾空白）
        assertEquals("cmd a", SelfImprovement.signature("  cmd \n a  "))
    }

    @Test
    fun `merge dedupes by key and accumulates hits`() {
        val l1 = SelfImprovement.extract(
            listOf(
                event("shell_execute", "grep x", false, t0),
                event("shell_execute", "grep -rl x", true, t0 + 1),
            ),
            now = t0 + 2,
        )
        val l2 = SelfImprovement.extract(
            listOf(
                event("shell_execute", "grep x", false, t0 + 100),
                event("shell_execute", "grep -rl x --include=*.kt", true, t0 + 101),
            ),
            now = t0 + 102,
        )
        val merged = SelfImprovement.merge(l1, l2, now = t0 + 102)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].hits)
        // guidance 更新为最新观测到的成功签名
        assertEquals("grep -rl x --include=*.kt", merged[0].guidance)
        assertEquals(t0 + 102, merged[0].lastSeenAt)
    }

    @Test
    fun `merge evicts lowest-value lessons beyond cap`() {
        val existing = (1..5).map { i ->
            SelfImprovement.Lesson(
                key = "tool#$i", toolName = "tool", trigger = "t$i", guidance = "g$i",
                hits = i, createdAt = t0 + i, lastSeenAt = t0 + i,
            )
        }
        val incoming = listOf(
            SelfImprovement.Lesson(
                key = "new#9", toolName = "new", trigger = "t9", guidance = "g9",
                hits = 1, createdAt = t0 + 99, lastSeenAt = t0 + 99,
            ),
        )
        val merged = SelfImprovement.merge(existing, incoming, now = t0 + 99, cap = 5)
        assertEquals(5, merged.size)
        // hits=1 的老 lesson（key=tool#1）被淘汰，新的进来了
        assertTrue(merged.none { it.key == "tool#1" })
        assertTrue(merged.any { it.key == "new#9" })
    }

    @Test
    fun `fragment returns null when empty or all disabled`() {
        assertNull(SelfImprovement.renderPromptFragment(emptyList()))
        val disabled = listOf(
            SelfImprovement.Lesson(
                key = "k", toolName = "t", trigger = "a", guidance = "b",
                hits = 1, createdAt = t0, lastSeenAt = t0, isEnabled = false,
            ),
        )
        assertNull(SelfImprovement.renderPromptFragment(disabled))
    }

    @Test
    fun `fragment ranks by hits and caps at max`() {
        val lessons = (1..10).map { i ->
            SelfImprovement.Lesson(
                key = "tool#$i", toolName = "shell_execute",
                trigger = "trig$i", guidance = "good$i",
                hits = i, createdAt = t0, lastSeenAt = t0 + i,
            )
        }
        val frag = SelfImprovement.renderPromptFragment(lessons, max = 3)!!
        val lines = frag.lines()
        // header 2 行 + 3 条 lesson
        assertEquals(5, lines.size)
        assertTrue(lines[2].contains("trig10"))
        assertTrue(lines[2].contains("good10"))
        assertTrue(!frag.contains("trig7"))
        assertTrue(frag.startsWith("## Lessons learned"))
    }
}
