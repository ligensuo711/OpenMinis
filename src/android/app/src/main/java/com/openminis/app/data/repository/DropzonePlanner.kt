package com.openminis.app.data.repository

/**
 * [T-stage5-plugin-dropzone] Stage 5.1 — 技能插件热加载收件箱的纯规划逻辑。
 *
 * 用户/agent 把 `.zip`（clawhub 导出、GitHub release 技能包）或含
 * SKILL.md 的目录放进 `minis-global/skills-inbox/`，下一轮对话构建
 * system prompt 时 [SkillRepository.processDropzone] 按这里的计划执行：
 * zip → importFromArchive；目录 → 移入 skillsDir 由 auto-discover 收编；
 * 识别不了的留在原地不动（用户可能还在写入，绝不误删）。
 *
 * 纯函数、无 Android 依赖，JVM 可测（DropzonePlannerTest）。
 */
object DropzonePlanner {

    /** 收件箱里的一个条目（由 IO 层 stat 后传入）。 */
    data class InboxEntry(
        val name: String,
        val isDirectory: Boolean,
        val hasSkillMd: Boolean,
        val isZip: Boolean,
    )

    sealed interface Action {
        /** `.zip` 技能包 → 走 importFromArchive。 */
        data class ImportZip(val fileName: String) : Action

        /** 含 SKILL.md 的目录 → 移入 skillsDir（auto-discover 自动收编）。 */
        data class AdoptDirectory(val dirName: String) : Action

        /** 不处理，原样保留。[reason] 仅用于日志/报告。 */
        data class Skip(val name: String, val reason: String) : Action
    }

    /** 处理后的 zip 存档子目录名（成功/失败各一，防无限重试）。 */
    const val ARCHIVE_DONE_DIR = "imported"
    const val ARCHIVE_FAILED_DIR = "failed"

    fun plan(entries: List<InboxEntry>): List<Action> =
        entries.map { entry ->
            val name = entry.name
            when {
                // 存档子目录自身 + 隐藏文件（.DS_Store、编辑器临时文件）跳过
                name == ARCHIVE_DONE_DIR || name == ARCHIVE_FAILED_DIR ->
                    Action.Skip(name, "archive subdirectory")
                name.startsWith(".") ->
                    Action.Skip(name, "hidden entry")
                entry.isDirectory && entry.hasSkillMd ->
                    Action.AdoptDirectory(name)
                entry.isDirectory ->
                    Action.Skip(name, "directory without SKILL.md (possibly mid-copy)")
                entry.isZip ->
                    Action.ImportZip(name)
                else ->
                    Action.Skip(name, "unrecognized file (not .zip, not a skill directory)")
            }
        }

    /**
     * 目采纳目录名 → 技能 id（= skillsDir 子目录名）。目录名来自外部
     * 文件系统，可能含空格/中文/斜杠等对技能 id 不友好的字符；收窄到
     * `[A-Za-z0-9._-]`，其余折叠为 `-`，收尾去 `-`/`.`，空结果回退
     * `skill`。与 Minis 主端「skill id = SKILL.md 目录名」的约定一致。
     */
    fun sanitizeId(dirName: String): String {
        val cleaned = dirName.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '-'
        }.joinToString("")
            .trim('-', '.')
        return cleaned.ifBlank { "skill" }
    }
}
