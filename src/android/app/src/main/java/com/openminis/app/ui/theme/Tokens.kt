package com.openminis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * [T-ui-tokens] 设计 token 收敛层 —— Stage「UI 优化」B 部分。
 *
 * 背景：chat 模块 41k 行里圆角/间距/尺寸长期各自为政（气泡 12dp、缩略图
 * 8dp、代码块 6dp、徽标 10dp……），同一语义容器在不同文件出现 3 种值。
 * 本文件是唯一权威来源：**新代码一律引用这里的常量**；存量调用点在触及
 * 该文件时顺手迁移（大爆炸式替换在 52 个 chat 文件里回归面不可控）。
 *
 * 命名规则：`<语义><属性>`，不带具体值 —— 值是契约，名字不是（对齐
 * Theme.kt 里 Teal* 前缀保留的先例）。
 */
object Tokens {

    // ── 圆角（cornerRadius category）────────────────────────────────────
    //
    // 三档收敛：内容小件（代码块/缩略图）→ 8；标准容器（气泡/工具卡/
    // sheet）→ 12；强调性徽标/胶囊 → full。旧值 6/10 一律并入相邻档。

    /** 代码块、图片缩略图等消息内嵌内容件。 */
    val radiusContent = RoundedCornerShape(8.dp)

    /** 气泡、工具卡片、sheet、对话框等标准容器。 */
    val radiusContainer = RoundedCornerShape(12.dp)

    /** 胶囊、圆点、进度环等全圆元素。 */
    val radiusFull = RoundedCornerShape(50)

    // ── 描边（stroke）──────────────────────────────────────────────────

    /** hairline 分隔/描边（0.5dp，对齐 iOS separator 规格）。 */
    val strokeHairline = 0.5.dp

    /** 常规描边（选中态、缩略图边框）。 */
    val strokeRegular = 1.dp

    // ── 间距（spacing category，4pt 网格）───────────────────────────────

    /** 同组元素内间距。 */
    val spacingXs = 4.dp

    /** 列表行内主间距 / 相关元素组间距。 */
    val spacingSm = 8.dp

    /** 标准组件内边距。 */
    val spacingMd = 12.dp

    /** 卡片/分区外间距。 */
    val spacingLg = 16.dp

    /** 屏幕级边距 / 大分区间隔。 */
    val spacingXl = 24.dp

    // ── 尺寸（sizing）──────────────────────────────────────────────────

    /** 工具状态图标、行内 icon 按钮。 */
    val sizeIcon = 20.dp

    /** 标准可点击目标（IconButton 推荐 40+，紧凑处 32）。 */
    val sizeTouchCompact = 32.dp
    val sizeTouchRegular = 40.dp

    /** 消息内缩略图默认边长。 */
    val sizeThumbnail = 160.dp

    // ── 动效（motion，C 部分实施前的占位权威）───────────────────────────

    /** 微交互（状态切换、ripple 伴随）时长。 */
    const val durationMicroMs = 150

    /** 内容进出（sheet、展开/收起）时长。 */
    const val durationStandardMs = 250
}

/**
 * [T-ui-tokens] iOS 系统语义色 —— chat 模块曾以 `Color(0xFF34C759)` /
 * `Color(0xFF007AFF)` 字面量散落 3 个文件 20+ 处（工具成功态、模型选中
 * 态、在线指示点……）。收敛为单一来源；命名对齐 iOS 语义而非 hex。
 *
 * 有别于 [com.openminis.app.ui.theme.ChatPalette]（明暗两套经
 * CompositionLocal 注入），这几个是**品牌固定的 iOS 系统色**：明暗模式同
 * 值（iOS 自身也只在 dark mode 微调这些色，现状代码同样未区分），因此
 * 常量即可，无需 palette 化。
 */
object SemanticColors {
    /** iOS system green —— 成功态（工具执行成功、模型可用、完成指示）。 */
    val success = androidx.compose.ui.graphics.Color(0xFF34C759)

    /** iOS system blue —— 选中态/操作引导（选中勾、当前模型、进行中）。 */
    val accent = androidx.compose.ui.graphics.Color(0xFF007AFF)

    /** iOS system orange —— 警告态（降级提示、注意事项）。 */
    val warning = androidx.compose.ui.graphics.Color(0xFFFF9500)

    /** iOS system red —— 错误/危险态（失败、删除确认强调）。 */
    val danger = androidx.compose.ui.graphics.Color(0xFFFF2D55)
}
