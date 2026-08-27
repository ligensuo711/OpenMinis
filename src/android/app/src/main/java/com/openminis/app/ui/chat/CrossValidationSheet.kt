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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.crossvalidate.CrossValidation
import com.openminis.app.ui.theme.ChatColors
import kotlin.math.roundToInt

/**
 * [T-cross-validation] Stage 4.12 — multi-model cross-check bottom sheet.
 *
 * Lists up to three answers to the same prompt, each labelled with its model
 * and an "Adopt" affordance; a divergence banner above the answers flags pairs
 * whose lexical overlap fell below the threshold (weak signal — see
 * [CrossValidation] for the honesty note). Answers are NOT persisted unless
 * the user adopts one.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CrossValidationSheet(
    state: kotlinx.coroutines.flow.StateFlow<CrossValidationState?>,
    running: kotlinx.coroutines.flow.StateFlow<Boolean>,
    onAdopt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cross by state.collectAsState()
    val isRunning by running.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = "Cross-check",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)

            val c = cross
            when {
                isRunning && c == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cross-checking…", fontSize = 14.sp, color = ChatColors.secondaryText)
                }

                c == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No cross-check in progress", fontSize = 14.sp, color = ChatColors.secondaryText)
                }

                else -> {
                    // Prompt under test.
                    Text(
                        text = c.prompt,
                        fontSize = 13.sp,
                        color = ChatColors.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )

                    // Divergence banner over divergent pairs.
                    val divs = remember(c) {
                        CrossValidation.divergences(c.answers.map { it.modelName to it.text })
                    }
                    val divergent = divs.filter { it.diverged }
                    if (divergent.isNotEmpty()) {
                        Text(
                            text = divergent.joinToString(" · ") {
                                val pct = (it.similarity * 100).roundToInt()
                                "${it.modelA} vs ${it.modelB} 差异明显 (${pct}%)"
                            },
                            fontSize = 11.sp,
                            color = ToolErrorColor,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val accents = listOf(ChatColors.thinking, ToolCheckColor, ToolErrorColor)
                        c.answers.forEachIndexed { i, answer ->
                            AnswerCard(
                                label = answer.modelName,
                                text = answer.text,
                                accent = accents[i % accents.size],
                                onAdopt = { onAdopt(answer.text) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(
    label: String,
    text: String,
    accent: Color,
    onAdopt: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        Column(modifier = Modifier.height(160.dp).verticalScroll(rememberScrollState())) {
            Text(text = text, fontSize = 12.sp, color = ChatColors.primaryText)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f))
                .clickable(onClick = onAdopt)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Adopt this answer",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}