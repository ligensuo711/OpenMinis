package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-session-branching] Stage 3.3 — branch compare bottom sheet.
 *
 * Shows two alternative answers side-by-side (each labelled with its model)
 * and lets the user keep one; the kept branch is promoted to the trunk and the
 * sheet dismisses. Reads the live [BranchCompareState] flow so the sheet can
 * also render the in-flight spinner before the two answers land.
 *
 * Visual language stays restrained: transparent sheet, ChatColors text tones,
 * a single accent "Keep" affordance per column. No new palette.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BranchCompareSheet(
    state: kotlinx.coroutines.flow.StateFlow<BranchCompareState?>,
    running: kotlinx.coroutines.flow.StateFlow<Boolean>,
    onKeep: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val compare by state.collectAsState()
    val isRunning by running.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = "Compare answers",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)

            val c = compare
            if (isRunning && c == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Asking two models…",
                        fontSize = 14.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            } else if (c == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No comparison in progress",
                        fontSize = 14.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnswerColumn(
                        label = c.answerA.modelName,
                        text = c.answerA.text,
                        accent = ChatColors.thinking,
                        onKeep = { onKeep(c.answerA.branchId) },
                        modifier = Modifier.weight(1f),
                    )
                    AnswerColumn(
                        label = c.answerB.modelName,
                        text = c.answerB.text,
                        accent = ToolCheckColor,
                        onKeep = { onKeep(c.answerB.branchId) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerColumn(
    label: String,
    text: String,
    accent: androidx.compose.ui.graphics.Color,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.toolBorder.copy(alpha = 0.6f))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
            Text(
                text = text,
                fontSize = 12.sp,
                color = ChatColors.primaryText,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f))
                .clickable(onClick = onKeep)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Keep this",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}