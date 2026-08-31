package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SelfImprovement
import com.openminis.app.agent.SelfImprovementStore
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-stage5-self-improvement] Stage 5.2 — lessons 管理屏。
 *
 * 自我改进闭环的用户侧出口：
 *  - 查看 agent 自动提炼的「失败→恢复」经验（hits = 被再次观测次数）
 *  - 逐条启用/禁用（禁用 = 不注入 prompt，保留历史）
 *  - 删除 / 手工录入
 *
 * Store 是进程级单例（SelfImprovementStore.get），与 ChatViewModel 的
 * agent loop 写入端共享同一份内存态 + lessons.json。
 */
@Composable
fun SelfImprovementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SelfImprovementStore.get(context) }
    val lessons by store.lessons.collectAsState()
    var manualText by remember { mutableStateOf("") }

    SettingsScaffold(title = stringResource(R.string.self_improvement_title), onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                SettingsSection(
                    header = stringResource(R.string.self_improvement_section_header),
                    footer = stringResource(R.string.self_improvement_section_footer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogTextField(
                            value = manualText,
                            onValueChange = { manualText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.self_improvement_add_placeholder),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        MinisTextButton(
                            onClick = {
                                store.addManual(manualText)
                                manualText = ""
                            },
                            enabled = manualText.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.self_improvement_add_button))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (lessons.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.self_improvement_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.self_improvement_empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val sorted = lessons.sortedWith(
                    compareByDescending<SelfImprovement.Lesson> { it.hits }
                        .thenByDescending { it.lastSeenAt }
                )
                items(sorted, key = { it.key }) { lesson ->
                    LessonRow(
                        lesson = lesson,
                        onToggle = { store.setEnabled(lesson.key, it) },
                        onDelete = { store.delete(lesson.key) },
                        showDivider = sorted.lastOrNull()?.key != lesson.key,
                    )
                }
            }
        }
    }
}

@Composable
private fun LessonRow(
    lesson: SelfImprovement.Lesson,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = if (lesson.isManual) lesson.guidance else "[${lesson.toolName}] ${lesson.trigger}",
        subtitle = if (lesson.isManual) {
            stringResource(R.string.self_improvement_manual_tag)
        } else {
            stringResource(R.string.self_improvement_hits_format, lesson.hits) + " → " + lesson.guidance
        },
        onClick = { onToggle(!lesson.isEnabled) },
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = lesson.isEnabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.self_improvement_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
