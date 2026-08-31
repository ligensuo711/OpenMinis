package com.openminis.app.agent

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-stage5-self-improvement] Stage 5.2 — lessons 持久化 + 进程级单例。
 *
 * 存储：`<filesDir>/minis-global/memory/lessons.json`（与 GLOBAL.md、
 * daily logs 同目录，Memory 管理屏统一可见的家族位置）。原子写
 * （tmp + rename）；损坏文件不致命——改名 `.corrupt` 留档后从空重建。
 *
 * 单例（[get]）：ChatViewModel 的 agent loop 与 Settings 的
 * SelfImprovementScreen 共用同一实例，避免两个实例对同一文件
 * last-writer-wins 互相覆盖。
 *
 * 纯逻辑（提取/合并/渲染）全部在 [SelfImprovement]，本类只做 I/O 和
 * 状态发布。
 */
class SelfImprovementStore private constructor(context: Context) {

    companion object {
        private const val TAG = "SelfImprovementStore"

        @Volatile
        private var instance: SelfImprovementStore? = null

        fun get(context: Context): SelfImprovementStore =
            instance ?: synchronized(this) {
                instance ?: SelfImprovementStore(context.applicationContext).also { instance = it }
            }
    }

    private val file = File(context.filesDir, "minis-global/memory/lessons.json")

    private val _lessons = MutableStateFlow<List<SelfImprovement.Lesson>>(emptyList())
    val lessons: StateFlow<List<SelfImprovement.Lesson>> = _lessons.asStateFlow()

    init {
        load()
    }

    /** Agent loop 尾部调用：提取 + 合并 + 持久化。返回新增 lesson 数（日志用）。 */
    @Synchronized
    fun record(events: List<SelfImprovement.ToolEvent>): Int {
        if (events.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val extracted = SelfImprovement.extract(events, now)
        if (extracted.isEmpty()) return 0
        val before = _lessons.value.map { it.key }.toSet()
        _lessons.value = SelfImprovement.merge(_lessons.value, extracted, now)
        persist()
        return _lessons.value.count { it.key !in before }
    }

    /** 手工录入一条 lesson（无 trigger，纯 guidance）。 */
    @Synchronized
    fun addManual(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        val key = "manual#${trimmed.lowercase().replace(Regex("\\s+"), " ").take(64)}"
        val lesson = SelfImprovement.Lesson(
            key = key,
            toolName = "manual",
            trigger = "",
            guidance = SelfImprovement.signature(trimmed, maxLen = 160),
            hits = 1,
            createdAt = now,
            lastSeenAt = now,
            isManual = true,
        )
        _lessons.value = SelfImprovement.merge(_lessons.value, listOf(lesson), now)
        persist()
    }

    @Synchronized
    fun setEnabled(key: String, enabled: Boolean) {
        _lessons.value = _lessons.value.map {
            if (it.key == key) it.copy(isEnabled = enabled) else it
        }
        persist()
    }

    @Synchronized
    fun delete(key: String) {
        _lessons.value = _lessons.value.filterNot { it.key == key }
        persist()
    }

    /** system prompt 注入用。无可用 lesson 时返回 null（跳过注入）。 */
    fun promptFragment(): String? = SelfImprovement.renderPromptFragment(_lessons.value)

    // -- Internal I/O --

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            val loaded = mutableListOf<SelfImprovement.Lesson>()
            for (i in 0 until arr.length()) {
                // 单条损坏跳过，不拖垮整个文件（对齐 SkillRepository.loadAll 的 per-row isolation）
                runCatching {
                    val o = arr.getJSONObject(i)
                    loaded.add(
                        SelfImprovement.Lesson(
                            key = o.getString("key"),
                            toolName = o.getString("tool"),
                            trigger = o.optString("trigger"),
                            guidance = o.optString("guidance"),
                            hits = o.optInt("hits", 1),
                            createdAt = o.optLong("created_at"),
                            lastSeenAt = o.optLong("last_seen_at"),
                            isEnabled = o.optBoolean("enabled", true),
                            isManual = o.optBoolean("manual", false),
                        )
                    )
                }.onFailure { Log.w(TAG, "skip unreadable lesson entry: ${it.message}") }
            }
            _lessons.value = loaded
        }.onFailure {
            Log.w(TAG, "lessons.json unreadable (${it.message}) — archiving and starting fresh")
            file.renameTo(File(file.parentFile, "lessons.json.corrupt"))
            _lessons.value = emptyList()
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (l in _lessons.value) {
                arr.put(JSONObject().apply {
                    put("key", l.key)
                    put("tool", l.toolName)
                    put("trigger", l.trigger)
                    put("guidance", l.guidance)
                    put("hits", l.hits)
                    put("created_at", l.createdAt)
                    put("last_seen_at", l.lastSeenAt)
                    put("enabled", l.isEnabled)
                    put("manual", l.isManual)
                })
            }
            val tmp = File(file.parentFile, "lessons.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // 跨文件系统 rename 失败时退回直接写（PRoot bind 场景可能出现）
                file.writeText(arr.toString())
                tmp.delete()
            }
        }.onFailure { Log.e(TAG, "persist lessons failed: ${it.message}", it) }
    }
}
