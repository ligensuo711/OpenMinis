package com.openminis.app.data.branching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchGraphTest {

    private fun row(id: String, parent: String?, branch: String?, sort: Int) =
        BranchGraph.Row(id = id, parentId = parent, branchId = branch, role = "assistant", summary = id, sortOrder = sort)

    @Test
    fun trunkIsParentlessInSortOrder() {
        val rows = listOf(
            row("u1", null, null, 1),
            row("a1", null, null, 2),
            row("b1", "a1", "br1", 3),
        )
        assertEquals(listOf("u1", "a1"), BranchGraph.trunk(rows).map { it.id })
    }

    @Test
    fun forksGroupByParent() {
        val rows = listOf(
            row("u1", null, null, 1),
            row("a1", null, null, 2),
            row("b1", "a1", "br1", 3),
            row("b2", "a1", "br2", 4),
        )
        val forks = BranchGraph.forks(rows)
        assertEquals(1, forks.size)
        assertEquals("a1", forks[0].parentMessageId)
        assertEquals(setOf("br1", "br2"), forks[0].branches.map { it.branchId }.toSet())
    }

    @Test
    fun promotionPlanKeepsOnlyKeptBranch() {
        val rows = listOf(
            row("b1", "a1", "br1", 3),
            row("b2", "a1", "br2", 4),
        )
        assertEquals(listOf("b2"), BranchGraph.promotionPlan(rows, "br2"))
        assertTrue(BranchGraph.promotionPlan(rows, "nope").isEmpty())
    }
}