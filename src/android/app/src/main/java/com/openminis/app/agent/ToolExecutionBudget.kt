package com.openminis.app.agent

import java.security.MessageDigest

/**
 * 单轮工具调用去重 + Agent 总轮数预算（阶段 1 · 工具执行链安全地基）。
 *
 * 与既有 [ToolLoopDetector] 的分工：
 *  - [ToolLoopDetector] 看的是**跨轮的同工具打转**（滑窗 30、警告 10、
 *    严重 20、熔断 30），治的是"同一个工具反复调、结果也差不多"。
 *  - 本类治两件它管不到的事：
 *    1. **同一轮内**模型一次返回多个完全相同的 tool_call（同名 + 同参数）。
 *       重复执行既烧 token 又可能重复写文件；直接复用首次结果即可。
 *    2. **跨工具发散**：模型不停换工具但始终不收敛，单工具计数永远不到阈值，
 *       loop detector 不会触发，会话却已经烧掉几十轮。用总轮数硬上限兜住。
 *
 * 设计约束：纯逻辑、零 Android 依赖，可在 JVM 单测/沙箱 kotlinc 独立断言。
 * 状态按会话持有一个实例，[resetTurn] 在每轮模型调用开始时调用，
 * [reset] 在会话重置（新对话 / 清空 / 重试）时调用。
 */
class ToolExecutionBudget(
    /**
     * 一次用户消息触发的 Agent 循环里，最多允许多少轮"模型调用 + 工具执行"。
     * 默认 40：正常复杂任务（读文件→改代码→跑测试→修错→再跑）通常 10-25 轮，
     * 40 给足余量又能在真正发散时截断。
     */
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 40

        /** SHA-256 前 16 位十六进制，足够避免同轮碰撞又不占内存。 */
        internal fun fingerprint(toolName: String, argsJson: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            // 用 \u0000 分隔，避免 ("ab","c") 与 ("a","bc") 撞同一指纹。
            val payload = toolName + "\u0000" + argsJson
            return md.digest(payload.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }
    }

    /** 去重结果。 */
    sealed interface DedupeResult {
        /** 首次出现，正常执行。 */
        data object Execute : DedupeResult

        /**
         * 本轮已执行过同名同参调用，复用首次结果。
         * [cachedResult] 是首次执行的结果文本，[originalToolCallId] 便于日志对齐。
         */
        data class ReuseCached(
            val cachedResult: String,
            val originalToolCallId: String,
        ) : DedupeResult
    }

    /** 轮数预算检查结果。 */
    sealed interface RoundVerdict {
        data object Continue : RoundVerdict

        /** 已达上限，[message] 面向模型说明为什么停下。 */
        data class Exhausted(val message: String) : RoundVerdict
    }

    private data class CachedCall(val toolCallId: String, val result: String)

    /** 本轮 fingerprint → 首次执行结果。 */
    private val turnCache = mutableMapOf<String, CachedCall>()

    /** 本次用户消息已消耗的轮数。 */
    private var roundsUsed = 0

    /** 当前轮数（供日志/UI 展示）。 */
    val currentRound: Int get() = roundsUsed

    /** 上限（供提示词/日志展示）。 */
    val roundLimit: Int get() = maxRounds

    /**
     * 每轮模型调用开始时调用：递增轮数并清空同轮去重缓存。
     *
     * 返回 [RoundVerdict.Exhausted] 时调用方应停止循环，把 message 作为
     * 最后一条工具结果/助手提示交给模型，让它向用户交代进展而不是静默中断。
     */
    fun beginRound(): RoundVerdict {
        if (roundsUsed >= maxRounds) {
            return RoundVerdict.Exhausted(
                "已达到本次任务的工具调用轮数上限（$maxRounds 轮）。请停止继续调用工具，" +
                    "用一段话向用户说明：已经完成了什么、当前卡在哪里、建议的下一步。" +
                    "如果任务确实需要更多轮次，请让用户重新发起或拆分任务。"
            )
        }
        roundsUsed++
        turnCache.clear()
        return RoundVerdict.Continue
    }

    /**
     * 执行前检查同轮重复。命中则返回缓存结果，不应再真正执行。
     *
     * [argsJson] 传归一化后的参数 JSON 字符串（同一份内容不同键序会被视为
     * 不同调用，这是可接受的保守行为——模型同轮重复通常是逐字复制）。
     */
    fun checkDuplicate(toolName: String, argsJson: String): DedupeResult {
        val fp = fingerprint(toolName, argsJson)
        val cached = turnCache[fp] ?: return DedupeResult.Execute
        return DedupeResult.ReuseCached(cached.result, cached.toolCallId)
    }

    /**
     * 执行完成后登记结果，供同轮后续重复调用复用。
     *
     * 只登记成功结果：失败结果不缓存，让模型有机会用同样参数重试
     * （比如网络抖动导致的 curl 失败）。
     */
    fun recordExecution(
        toolName: String,
        argsJson: String,
        toolCallId: String,
        result: String,
        isError: Boolean,
    ) {
        if (isError) return
        val fp = fingerprint(toolName, argsJson)
        turnCache[fp] = CachedCall(toolCallId, result)
    }

    /** 会话级重置：新对话 / 清空 / 重试时调用。 */
    fun reset() {
        turnCache.clear()
        roundsUsed = 0
    }

    /** 仅清空同轮缓存，不动轮数（用于同一轮内的重新分发场景）。 */
    fun resetTurn() {
        turnCache.clear()
    }
}
