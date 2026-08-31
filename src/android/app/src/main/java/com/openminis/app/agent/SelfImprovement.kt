package com.openminis.app.agent

/**
 * [T-stage5-self-improvement] Stage 5.2 — 自我改进闭环的纯逻辑核心。
 *
 * 闭环语义：agent 运行中记录每个工具调用的成败（[ToolEvent]）；一轮
 * runAgentLoop 结束后由 [extract] 把「同名工具失败 → 之后重试成功」的序列
 * 提炼成 [Lesson]（trigger = 失败调用的签名，guidance = 成功调用的签名）；
 * [merge] 跨会话累积去重（hits 递增）；[renderPromptFragment] 把高分
 * lessons 注入后续会话的 system prompt。全部纯函数，无 Android 依赖，
 * JVM 可测（SelfImprovementTest）。
 *
 * 设计约束：
 *  - 只信「失败后被验证过成功」的经验。失败后没有恢复的调用不产生
 *    lesson——那只是失败，不含可复用的知识。
 *  - lesson 的一行签名必须经 [signature] 归一化：折叠空白、去换行、
 *    截断。签名既做 prompt 注入也做去重 key 的成分，归一化防止
 *    「同一命令不同缩进」产生重复 lesson，也防止换行注入破坏
 *    fragment 的行结构。
 */
object SelfImprovement {

    /** 单个工具调用结果事件（agent loop 内逐次记录）。 */
    data class ToolEvent(
        val toolName: String,
        /** 归一化后的调用签名（见 [signature]），UI/提取层负责归一化。 */
        val inputSignature: String,
        val succeeded: Boolean,
        val timestampMs: Long,
    )

    /**
     * 一条经验。key = `toolName + '#' + trigger`，是跨会话合并去重的
     * 唯一标识。
     */
    data class Lesson(
        val key: String,
        val toolName: String,
        /** 失败调用的签名（问题模式）。 */
        val trigger: String,
        /** 随后成功调用的签名（验证过的解法）。 */
        val guidance: String,
        /** 该 lesson 被再次观测到的次数（首次为 1）。 */
        val hits: Int,
        val createdAt: Long,
        val lastSeenAt: Long,
        val isEnabled: Boolean = true,
        /** 手工录入（Settings UI / 未来 slash 命令）的 lesson 无 trigger。 */
        val isManual: Boolean = false,
    )

    /** lesson 总量上限（LRU by lastSeenAt + hits 淘汰）。 */
    const val MAX_LESSONS = 50

    /** prompt 注入的最大条数（按 hits 降序）。 */
    const val MAX_IN_PROMPT = 8

    /**
     * 归一化调用签名：折叠所有空白为单空格、剔除换行、截断到
     * [maxLen]。防止换行破坏 fragment 行结构 + 跨调用微小差异导致
     * 去重失效。
     */
    fun signature(args: String, maxLen: Int = 80): String {
        val collapsed = args.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= maxLen) collapsed else collapsed.take(maxLen - 1) + "…"
    }

    /**
     * 从一串事件里提取 lessons。规则：对每个失败事件，找**同工具**在
     * 其之后（按时间序）的第一个成功事件；签名不同才成 lesson
     * （签名相同的成功意味着只是瞬时故障重试，不含新知识）。
     * 一个失败最多产出一条 lesson。
     *
     * 事件无需预先排序——这里先按 timestamp 稳定排序再扫描。
     */
    fun extract(events: List<ToolEvent>, now: Long): List<Lesson> {
        if (events.isEmpty()) return emptyList()
        val ordered = events.sortedBy { it.timestampMs }
        val lessons = mutableListOf<Lesson>()
        val seenKeys = mutableSetOf<String>()
        for ((i, failure) in ordered.withIndex()) {
            if (failure.succeeded) continue
            if (failure.toolName.isBlank() || failure.inputSignature.isBlank()) continue
            val recovery = ordered.drop(i + 1)
                .firstOrNull { it.toolName == failure.toolName && it.succeeded }
                ?: continue
            if (recovery.inputSignature == failure.inputSignature) continue
            val key = "${failure.toolName}#${failure.inputSignature}"
            if (!seenKeys.add(key)) continue
            lessons.add(
                Lesson(
                    key = key,
                    toolName = failure.toolName,
                    trigger = failure.inputSignature,
                    guidance = recovery.inputSignature,
                    hits = 1,
                    createdAt = now,
                    lastSeenAt = now,
                )
            )
        }
        return lessons
    }

    /**
     * 跨会话合并：同 key 的 lesson 合并为一条（hits 累加、guidance 更新为
     * 最新观测、lastSeenAt 刷新）；新 lesson 追加；超 [cap] 按淘汰序
     * （hits 升序、lastSeenAt 升序）丢弃尾部。
     */
    fun merge(
        existing: List<Lesson>,
        incoming: List<Lesson>,
        now: Long,
        cap: Int = MAX_LESSONS,
    ): List<Lesson> {
        val byKey = LinkedHashMap<String, Lesson>()
        for (lesson in existing) byKey[lesson.key] = lesson
        for (lesson in incoming) {
            val prev = byKey[lesson.key]
            byKey[lesson.key] = if (prev == null) {
                lesson
            } else {
                prev.copy(
                    hits = prev.hits + lesson.hits,
                    guidance = lesson.guidance.ifBlank { prev.guidance },
                    lastSeenAt = maxOf(prev.lastSeenAt, now),
                )
            }
        }
        if (byKey.size <= cap) return byKey.values.toList()
        return byKey.values
            .sortedWith(compareBy<Lesson> { it.hits }.thenBy { it.lastSeenAt })
            .drop(byKey.size - cap)
            .sortedBy { it.createdAt }
    }

    /**
     * 渲染 prompt fragment。无可用 lesson（空 / 全禁用）时返回 null，
     * 调用方据此跳过注入（与 skills / memory fragment 的 null 语义一致）。
     * 每条 lesson 一行，guidance/trigger 已归一化无换行，不会再破坏行结构。
     */
    fun renderPromptFragment(lessons: List<Lesson>, max: Int = MAX_IN_PROMPT): String? {
        val active = lessons.filter { it.isEnabled }
            .sortedWith(compareByDescending<Lesson> { it.hits }.thenByDescending { it.lastSeenAt })
            .take(max)
        if (active.isEmpty()) return null
        return buildString {
            appendLine("## Lessons learned (self-improvement)")
            appendLine("Auto-extracted from earlier failed-then-recovered tool calls. Apply when a matching call fails:")
            for (lesson in active) {
                if (lesson.isManual || lesson.trigger.isBlank()) {
                    appendLine("- ${lesson.toolName}: ${lesson.guidance}")
                } else {
                    appendLine("- [${lesson.toolName}] when \"${lesson.trigger}\" failed → \"${lesson.guidance}\" worked")
                }
            }
        }.trimEnd()
    }
}
