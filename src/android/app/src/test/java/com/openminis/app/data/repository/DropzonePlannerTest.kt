package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-stage5-plugin-dropzone] 收件箱规划回归测试。
 *
 * 关键契约：
 *  - .zip → ImportZip；含 SKILL.md 目录 → AdoptDirectory
 *  - 无 SKILL.md 目录 / 非 zip 文件 / 隐藏文件 → Skip（原样保留，绝不误删）
 *  - imported/failed 存档子目录自身跳过，防自噬
 *  - sanitizeId 收窄目录名到技能 id 安全字符集
 */
class DropzonePlannerTest {

    private fun entry(name: String, dir: Boolean = false, skillMd: Boolean = false, zip: Boolean = false) =
        DropzonePlanner.InboxEntry(name = name, isDirectory = dir, hasSkillMd = skillMd, isZip = zip)

    @Test
    fun `zip files are planned for archive import`() {
        val actions = DropzonePlanner.plan(
            listOf(
                entry("my-skill.zip", zip = true),
                entry("My.Skill_2.zip", zip = true),
            ),
        )
        assertEquals(
            listOf(
                DropzonePlanner.Action.ImportZip("my-skill.zip"),
                DropzonePlanner.Action.ImportZip("My.Skill_2.zip"),
            ),
            actions,
        )
    }

    @Test
    fun `directory with SKILL.md is planned for adoption`() {
        val actions = DropzonePlanner.plan(listOf(entry("bilibili-hub", dir = true, skillMd = true)))
        assertEquals(listOf(DropzonePlanner.Action.AdoptDirectory("bilibili-hub")), actions)
    }

    @Test
    fun `directory without SKILL.md is skipped not deleted`() {
        val actions = DropzonePlanner.plan(listOf(entry("still-copying", dir = true, skillMd = false)))
        assertEquals(
            listOf(DropzonePlanner.Action.Skip("still-copying", "directory without SKILL.md (possibly mid-copy)")),
            actions,
        )
    }

    @Test
    fun `unrecognized files are skipped`() {
        val actions = DropzonePlanner.plan(
            listOf(
                entry("README.txt"),
                entry(".DS_Store"),
                entry(".tmp-skill.zip", zip = true),
            ),
        )
        assertEquals(3, actions.size)
        assertTrue(actions.all { it is DropzonePlanner.Action.Skip })
    }

    @Test
    fun `archive subdirectories never self-process`() {
        val actions = DropzonePlanner.plan(
            listOf(
                entry("imported", dir = true, skillMd = true),
                entry("failed", dir = true, skillMd = true),
            ),
        )
        // 即便有人往 imported/ 里塞了 SKILL.md（正常情况下没有），也不处理
        assertEquals(2, actions.size)
        assertEquals(
            "archive subdirectory",
            (actions[0] as DropzonePlanner.Action.Skip).reason,
        )
    }

    @Test
    fun `zip check is case-insensitive on extension`() {
        val actions = DropzonePlanner.plan(listOf(entry("skill.ZIP", zip = true)))
        assertEquals(listOf(DropzonePlanner.Action.ImportZip("skill.ZIP")), actions)
    }

    @Test
    fun `sanitizeId narrows to skill-id-safe charset`() {
        assertEquals("my-skill", DropzonePlanner.sanitizeId("my skill"))
        assertEquals("a-b-c", DropzonePlanner.sanitizeId("a b/c"))
        assertEquals("v1.2_3", DropzonePlanner.sanitizeId("v1.2_3"))
        assertEquals("skill", DropzonePlanner.sanitizeId("---..."))
        assertEquals("skill", DropzonePlanner.sanitizeId("中文目录"))
        assertEquals("skill-1", DropzonePlanner.sanitizeId(" skill 1 "))
    }
}
