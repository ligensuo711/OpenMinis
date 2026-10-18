package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-reverse-prompt] User-editable operating procedure injected into
 * the agent system prompt on EVERY turn.
 *
 * Why a separate file instead of reusing SOUL.md:
 *  - SOUL.md is capped at 1600 CJK chars / 1000 EN words and its body is
 *    reserved for persona/voice. A working procedure is multi-section and
 *    would either blow the cap or evict the persona.
 *  - Persona is per-identity ("who you are"); a procedure is per-task-class
 *    ("how you work when the task looks like this"). Mixing them makes both
 *    harder to edit, and a user who switches persona would silently lose the
 *    procedure.
 *
 * Storage: `<filesDir>/minis-global/memory/REVERSE_PROMPT.md` — deliberately
 * the SAME directory as SOUL.md and GLOBAL.md, which buys three things for
 * free:
 *  1. It shows up in the sandbox at `/var/minis/memory/REVERSE_PROMPT.md`, so
 *     the user (or the agent itself) can edit it with `file_write`, `minis-open`,
 *     or any editor — no app rebuild, no restart.
 *  2. `BackupExporter.exportMemory` walks that directory, so it is included in
 *     backups without touching the backup code.
 *  3. The file browser already lists that directory.
 *
 * Semantics:
 *  - File missing or whitespace-only  -> nothing is injected (feature off).
 *  - Non-empty                        -> injected verbatim, clipped at
 *    [CHAR_LIMIT] with an explicit truncation marker.
 *  - No sidecar prefs: "empty file" IS the off switch. One source of truth.
 */
object ReversePromptStore {

    private const val TAG = "ReversePrompt"
    const val FILE_NAME = "REVERSE_PROMPT.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"

    /**
     * Hard cap on injected characters. Not a policy limit but a context-budget
     * guard: this block is re-sent on every request, so an accidental 200 KB
     * paste would tax every turn. 12000 chars ≈ 4k tokens for CJK, which is
     * the comfortable ceiling for a procedure block; the editor shows the same
     * number so the user sees the wall before hitting it.
     */
    const val CHAR_LIMIT: Int = 12000

    /** Appended when [CHAR_LIMIT] clips the body, so the model knows the tail is missing. */
    const val TRUNCATION_MARKER = "\n\n[-- truncated: REVERSE_PROMPT.md exceeds $CHAR_LIMIT chars; edit the file to shorten --]"

    /**
     * Seeded on first run. Written in Chinese because the primary user of this
     * feature works in Chinese; it is plain Markdown and fully rewritable.
     *
     * Kept to ~1 KB on purpose: it is injected every turn, so it states the
     * ORDER of work and the hard rules, and points at the long-form skill file
     * for the command matrix — the expensive detail belongs in a skill the
     * model loads only when a task matches.
     */
    val DEFAULT_CONTENT: String = """# 安卓逆向工作流（每回合注入）

命中任何 .apk/.aab/.dex/.so/加固/去广告/去校验/协议还原/风控绕过任务时，按下面顺序执行，不跳步。

**0 立项**：把交付物写成一句可判定的产物（能装的 patched APK / 接口表 / 分析报告 / 可行与不可行的结论）。写不出就先问一句，然后继续动手。

**1 取证**（先落盘，绝不在原包上改）：
`sha256sum` → `aapt2 dump badging`（包名/版本/minSdk）→ `apksigner verify --print-certs`（证书指纹，覆盖安装必须一致）→ `unzip -l` 查 dex/so/assets。命中 libjiagu/libDexHelper/libshell/libnesec/libmobisec 或 assets 下无扩展名大文件 → 加固，先脱壳再谈静态。

**2 静态**：`jadx -d out --no-res` + `apktool d -f`，然后 `rg -n 'isVip|isPro|verify|signature|license|卡密|expire|trial' out/`；so 走 `readelf -d/-s` + `strings` + `objdump -d`。类名不可猜：先 `rg -l` 命中文件，再回 smali 按行改。

**3 动态**（静态读不出常量/密钥才用）：`frida -U -f <pkg> -l hook.js` 打 String 构造 / MessageDigest / Cipher / CertificatePinner 四个锚点；抓包前先断 pinning（X509TrustManager、SSLContext.init、CertificatePinner.check、HostnameVerifier 四层）。

**4 改**（最小 diff）：smali 改返回值 → 清分支 → 改常量，优先级从高到低；so 常用 `mov w0,#1`+`ret`（20 00 80 52 / C0 03 5F D6）、`nop`（1F 20 03 D5）。回编后 `zipalign` + `apksigner sign`，指纹不一致就只走卸载重装。

**5 验**：装机 → 冷启 → 目标功能点 → `logcat` 查 FATAL/VerifyError → 回归原功能；失败回退上一版，不在坏版本上叠改。

**6 交付**：产物路径 + sha256 + 改了哪个类哪一行 + 可复现命令 + 本轮踩的坑。

**纪律**：动手前 `rg -il '<关键词>' /var/minis/skills/*/SKILL.md` 检索技能库，命中就照它的判据走；命令必须可执行（真实路径/真实类名）；每轮结尾给下一步动作，不停在描述层。
"""

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    /** Seed the file with [DEFAULT_CONTENT] iff absent. Never overwrites edits. */
    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            AppLogger.info(TAG, "seeded REVERSE_PROMPT.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ensureExists failed: ${t.message}")
        }
    }

    /**
     * Raw file text, or null when the file is missing/unreadable.
     * Blank content is returned as-is; callers use [isBlank] to mean "off".
     */
    fun load(context: Context): String? {
        val file = fileLocation(context)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            null
        }
    }

    /** Atomic write through a `.tmp` sibling, then rename. */
    fun save(context: Context, body: String) {
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            target.writeText(body)
            tmp.delete()
        }
        _cached.value = body
    }

    /** Delete the file entirely — the feature is then off and the next [ensureExists] re-seeds it. */
    fun reset(context: Context) {
        save(context, DEFAULT_CONTENT)
    }

    /** True when a non-blank procedure is on disk and will be injected this turn. */
    fun isActive(context: Context): Boolean = load(context)?.isNotBlank() == true

    private val _cached = MutableStateFlow("")
    /** Last loaded/saved body, for synchronous UI reads. */
    val cached: StateFlow<String> = _cached.asStateFlow()

    fun refreshCache(context: Context) {
        _cached.value = load(context).orEmpty()
    }
}
