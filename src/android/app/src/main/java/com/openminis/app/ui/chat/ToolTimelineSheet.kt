package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-tool-timeline] Stage 3.1 — per-session tool execution timeline.
 *
 * Folds the conversation's tool blocks through [ToolTimelineAggregator] into a
 * chronological list of steps with a status header: total / succeeded / failed
 * / timed out / cancelled plus the summed terminal duration. Renders as a
 * bottom sheet off the chat "..." menu so it never interferes with the message
 * stream, and reads the live [messages] flow so an in-flight turn's steps show
 * up as they complete.
 *
 * Glass language stays minimal — transparent sheet + ChatColors status dots +
 * the existing toolName/status palette used by the reveal sheet. No new visual
 * vocabulary (stage-3 discipline).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolTimelineSheet(
    messages: StateFlow<List<ChatMessage>>,
    onDismiss: () -> Unit,
) {
    val msgs by messages.collectAsState()
    val (entries, summary) = remember(msgs) {
        ToolTimelineAggregator.aggregate(msgs)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = "Tool Timeline",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            // Status header: compact stat chips.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatChip(label = "${summary.total}", caption = "total", color = ChatColors.secondaryText)
                StatChip(label = "${summary.succeeded}", caption = "ok", color = ToolCheckColor)
                StatChip(label = "${summary.failed}", caption = "failed", color = ToolErrorColor)
                StatChip(label = "${summary.timedOut}", caption = "timeout", color = ToolErrorColor)
                StatChip(label = "${summary.cancelled}", caption = "cancelled", color = ToolCancelColor)
                if (summary.totalDurationMs > 0) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatToolDuration(summary.totalDurationMs),
                        fontSize = 12.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            }

            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No tool calls in this session yet",
                        fontSize = 14.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp
                    ),
                ) {
                    items(entries) { entry ->
                        TimelineRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, caption: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = caption,
            fontSize = 10.sp,
            color = ChatColors.secondaryText,
        )
    }
}

@Composable
private fun TimelineRow(entry: ToolTimelineAggregator.Entry) {
    val dotColor = statusColor(entry.status)
    Row(modifier = Modifier.fillMaxWidth()) {
        // Left rail: status dot on top, vertical connector filling the row.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .weight(1f)
                    .background(ChatColors.toolBorder),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = toolDisplayName(entry.toolName),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationLabel(entry),
                    fontSize = 11.sp,
                    color = ChatColors.secondaryText,
                )
            }
            if (entry.summary.isNotEmpty()) {
                Text(
                    text = entry.summary,
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "SUCCESS" -> ToolCheckColor
    "FAILED" -> ToolErrorColor
    "TIMEOUT" -> ToolErrorColor
    "CANCELLED" -> ToolCancelColor
    else -> ChatColors.thinking // RUNNING / PENDING / STREAMING
}

private fun durationLabel(entry: ToolTimelineAggregator.Entry): String {
    if (entry.status == "RUNNING" || entry.status == "PENDING" || entry.status == "STREAMING") return "…"
    if (entry.durationMs <= 0L) return "—"
    return formatToolDuration(entry.durationMs)
}