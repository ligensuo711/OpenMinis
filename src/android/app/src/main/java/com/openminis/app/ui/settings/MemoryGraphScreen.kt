package com.openminis.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.memorygraph.MemoryGraphLayout
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-memory-graph] Stage 3.2 — memory graph visualisation.
 *
 * Renders memory files as a deterministic ring of nodes; edges connect files
 * that share a significant term (lexical, mirrors stage-2 search_files). Taps
 * on a node select it and show its dated entries below. Reads files through
 * [MemoryRepository] and computes the layout via the pure [MemoryGraphLayout]
 * (asserted standalone in the sandbox + a JVM test in the CI gate).
 *
 * Colour stays inside the ChatColors palette; edges use the tool-border tone,
 * the GLOBAL node uses the thinking accent, daily nodes use the secondary
 * text tone — restrained, no new visual language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryGraphScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var graph by remember { mutableStateOf<MemoryGraphLayout.Graph?>(null) }

    LaunchedEffect(Unit) {
        val files = memoryRepository.listAllFiles().map {
            MemoryGraphLayout.FileInput(
                name = it.name,
                content = memoryRepository.readFile(it.name),
                isGlobal = it.isGlobal,
            )
        }
        graph = MemoryGraphLayout.build(files)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ChatColors.primaryText)
            }
            Text(
                text = "Memory Graph",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
            )
        }

        val g = graph
        if (g == null || g.nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No memory files yet", fontSize = 14.sp, color = ChatColors.secondaryText)
            }
            return@Column
        }

        // Graph canvas
        val points = remember(g.nodes.size) {
            MemoryGraphLayout.layout(g.nodes.size, 0f, 0f, 1f) // unit circle; scale at draw
        }
        val selectedContent = remember(selected) {
            selected?.let { memoryRepository.readFile(it) } ?: ""
        }
        // Colours + text measurer read ONCE in composable scope — DrawScope
        // (Canvas's onDraw lambda) is NOT @Composable, so ChatColors (a
        // composition-local read) and rememberTextMeasurer must be resolved
        // here and passed in as plain values.
        val edgeColor = ChatColors.toolBorder
        val globalColor = ChatColors.thinking
        val dailyColor = ChatColors.secondaryText
        val primaryColor = ChatColors.primaryText
        val textMeasurer = rememberTextMeasurer()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .pointerInput(g) {
                    detectTapGestures { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = (minOf(size.width, size.height) / 2f) * 0.72f
                        g.nodes.indices.minByOrNull { i ->
                            val p = points[i]
                            val dx = offset.x - (cx + p.x * radius)
                            val dy = offset.y - (cy + p.y * radius)
                            dx * dx + dy * dy
                        }?.let { selected = g.nodes[it].id }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = (minOf(size.width, size.height) / 2f) * 0.72f
                val nodePos = g.nodes.indices.map { i ->
                    Offset(cx + points[i].x * radius, cy + points[i].y * radius)
                }
                // Edges
                for (edge in g.edges) {
                    val a = g.nodes.indexOfFirst { it.id == edge.from }
                    val b = g.nodes.indexOfFirst { it.id == edge.to }
                    if (a < 0 || b < 0) continue
                    drawLine(
                        color = edgeColor,
                        start = nodePos[a],
                        end = nodePos[b],
                        strokeWidth = (1f + edge.weight.coerceAtMost(3)).dp.toPx(),
                    )
                }
                // Nodes
                g.nodes.forEachIndexed { i, node ->
                    val r = (14f + node.entryCount.coerceAtMost(20) * 1.2f).dp.toPx()
                    val fill = if (node.isGlobal) globalColor else dailyColor
                    drawCircle(color = fill.copy(alpha = 0.18f), radius = r, center = nodePos[i])
                    drawCircle(
                        color = if (node.id == selected) globalColor else fill,
                        radius = 4.dp.toPx(),
                        center = nodePos[i],
                    )
                    val layout = textMeasurer.measure(
                        text = node.label,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 10.sp,
                            color = primaryColor,
                        ),
                    )
                    drawText(layout, topLeft = Offset(nodePos[i].x - layout.size.width / 2f, nodePos[i].y + r + 2.dp.toPx()))
                }
            }
        }

        HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)

        // Selected node detail
        if (selected != null && selectedContent.isNotBlank()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(
                    text = selected!!.removeSuffix(".md"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = selectedContent,
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                )
            }
        } else {
            // Node list (tap = select)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                g.nodes.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = node.id }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (node.isGlobal) ChatColors.thinking else ChatColors.secondaryText),
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = node.label,
                            fontSize = 14.sp,
                            color = ChatColors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${node.entryCount}",
                            fontSize = 12.sp,
                            color = ChatColors.secondaryText,
                        )
                    }
                    HorizontalDivider(color = ChatColors.toolBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }
        }
    }
}