package com.openminis.app.data.branching

/**
 * [T-session-branching] Pure-logic branch model for session branch comparison
 * (stage 3.3). Zero Android / Room / Compose dependencies so the tree build
 * and promotion math can be asserted standalone in the sandbox (kotlinc)
 * before the compare UI + repository wiring land — same discipline as the
 * stage-1/2/3.1 pure layers.
 *
 * ### Model
 * A conversation's rows are partitioned into a **trunk** (parentId == null,
 * rendered normally, in sort order) and **branches** (parentId != null,
 * hidden from the trunk stream). A branch is identified by its shared
 * `branchId`; its rows hang off one `parentId` — the message the alternative
 * answer forked FROM. A single fork can carry several branches (two models →
 * two branches with two different branchIds, same parent).
 *
 * The object is deliberately read-only over an abstract [Row] type rather
 * than MessageEntity, so the same fold drives both the live DB rows and
 * in-memory previews.
 */
object BranchGraph {

    /** Minimal row projection the graph needs; decoupled from Room. */
    data class Row(
        val id: String,
        val parentId: String?,
        val branchId: String?,
        val role: String,
        val summary: String,
        val sortOrder: Int,
    )

    /** One alternative answer hanging off a fork point. */
    data class Branch(
        val branchId: String,
        val parentMessageId: String,
        /** Rows of this branch in sort order (usually one assistant turn). */
        val rows: List<Row>,
    )

    data class Fork(
        /** The message id every branch under this fork hangs off. */
        val parentMessageId: String,
        val branches: List<Branch>,
    )

    /** Rows with parentId == null, in sort order — the normal chat stream. */
    fun trunk(rows: List<Row>): List<Row> =
        rows.filter { it.parentId == null }.sortedBy { it.sortOrder }

    /** Group branch rows by branchId, then by their common parent. */
    fun forks(rows: List<Row>): List<Fork> {
        val branched = rows.filter { it.parentId != null && it.branchId != null }
        val byBranch = branched.groupBy { it.branchId!! }
        val byParent = LinkedHashMap<String, MutableList<Branch>>()
        for ((branchId, branchRows) in byBranch) {
            val parent = branchRows.first().parentId!!
            byParent.getOrPut(parent) { mutableListOf() }.add(
                Branch(
                    branchId = branchId,
                    parentMessageId = parent,
                    rows = branchRows.sortedBy { it.sortOrder },
                )
            )
        }
        return byParent.map { (parent, branches) -> Fork(parent, branches) }
    }

    /**
     * When the user keeps [keepBranchId], compute which rows must be re-parented
     * so that branch becomes the trunk continuation and every OTHER branch of
     * the same fork stays parked (hidden). Returns the branch rows that should
     * be promoted to parentId == null.
     *
     * Only rows of the kept branch are promoted; alternatives are untouched
     * (they keep their parentId/branchId and remain visible only in the
     * compare view). The caller applies the returned set of ids.
     */
    fun promotionPlan(rows: List<Row>, keepBranchId: String): List<String> =
        rows.filter { it.branchId == keepBranchId }
            .sortedBy { it.sortOrder }
            .map { it.id }
}