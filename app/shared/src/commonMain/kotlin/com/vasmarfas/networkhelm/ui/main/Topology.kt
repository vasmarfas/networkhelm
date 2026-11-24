package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.theme.Theme
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.topology_empty
import networkhelm.app.shared.generated.resources.topology_fit
import networkhelm.app.shared.generated.resources.topology_no_nodes
import networkhelm.app.shared.generated.resources.topology_zoom_in
import networkhelm.app.shared.generated.resources.topology_zoom_out
import org.jetbrains.compose.resources.stringResource
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TopologyNode(val name: String, val device: String?, val target: String?, val x: Int, val y: Int)

internal class Topology(val nodes: List<TopologyNode>, val links: List<Pair<TopologyNode, TopologyNode>>)

// the router keeps a coordinate as an unsigned 32-bit number: -24 reads as 4294967272
internal fun coordinate(text: String?): Int? = text?.toLongOrNull()?.toInt()

internal fun topology(layout: String, nodes: List<Item>, links: List<Item>): Topology {
    val placed = nodes.filter { it["layout"] == layout }.mapNotNull { row ->
        val name = row["name"] ?: return@mapNotNull null
        val x = coordinate(row["x"]) ?: return@mapNotNull null
        val y = coordinate(row["y"]) ?: return@mapNotNull null
        TopologyNode(name, row["device"]?.takeIf(String::isNotEmpty), row["target-layout"]?.takeIf(String::isNotEmpty), x, y)
    }
    val named = placed.associateBy { it.name }
    val joined = links.filter { it["layout"] == layout }.mapNotNull { row ->
        val from = named[row["node1"]] ?: return@mapNotNull null
        val to = named[row["node2"]] ?: return@mapNotNull null
        from to to
    }
    return Topology(placed, joined)
}

private val LAYOUTS = MenuPath.parse("cmr/layout")
private val NODES = MenuPath.parse("cmr/layout/node")
private val LINKS = MenuPath.parse("cmr/layout/link")
private val NODE_WIDTH = 124.dp
private val NODE_HEIGHT = 42.dp
private const val ZOOM_STEP = 1.25f
private const val MIN_SCALE = 0.2f
private const val MAX_SCALE = 4f
private const val MAX_FIT = 2.5f

@Composable
internal fun TopologyView(tab: RouterTab) {
    val palette = Theme.palette
    val session = tab.session
    val layouts by remember(session) { session.rowsAt(LAYOUTS) }.collectAsState(emptyList())
    val nodes by remember(session) { session.rowsAt(NODES) }.collectAsState(emptyList())
    val links by remember(session) { session.rowsAt(LINKS) }.collectAsState(emptyList())
    val names = layouts.mapNotNull { it["name"] }
    var chosen by remember { mutableStateOf<String?>(null) }
    val layout = chosen?.takeIf { it in names } ?: names.firstOrNull()
    Column(Modifier.fillMaxSize().background(palette.surface).testTag("topology")) {
        if (layout == null) {
            Text(stringResource(Res.string.topology_empty), Modifier.padding(14.dp), color = palette.muted)
            return@Column
        }
        val shown = topology(layout, nodes, links)
        var scale by remember(layout) { mutableFloatStateOf(1f) }
        var shift by remember(layout) { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var fitted by remember(layout) { mutableStateOf(false) }
        val density = LocalDensity.current
        val half = with(density) { Offset(NODE_WIDTH.toPx() / 2, NODE_HEIGHT.toPx() / 2) }

        fun zoom(factor: Float, around: Offset) {
            val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val ratio = next / scale
            val centre = Offset(size.width / 2f, size.height / 2f)
            shift = (around - centre) - ((around - centre) - shift) * ratio
            scale = next
        }

        fun fit() {
            if (shown.nodes.isEmpty() || size == IntSize.Zero) return
            val left = shown.nodes.minOf { it.x }
            val right = shown.nodes.maxOf { it.x }
            val top = shown.nodes.minOf { it.y }
            val bottom = shown.nodes.maxOf { it.y }
            val room = Offset(size.width - half.x * 2 - 48f, size.height - half.y * 2 - 48f)
            val next = min(room.x / max(right - left, 1), room.y / max(bottom - top, 1)).coerceIn(MIN_SCALE, MAX_FIT)
            scale = next
            shift = Offset(-(left + right) / 2f * next, -(top + bottom) / 2f * next)
        }

        LaunchedEffect(layout, size, shown.nodes.size) {
            if (!fitted && shown.nodes.isNotEmpty() && size != IntSize.Zero) {
                fit()
                fitted = true
            }
        }
        Row(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Layout", style = Theme.type.strong)
            Select(layout, names, { it }, { chosen = it }, Modifier.width(220.dp).testTag("topology-layout"))
            ToolbarButton(stringResource(Res.string.topology_zoom_out), Lucide.ZoomOut, { zoom(1 / ZOOM_STEP, Offset(size.width / 2f, size.height / 2f)) }, iconOnly = true)
            ToolbarButton(stringResource(Res.string.topology_zoom_in), Lucide.ZoomIn, { zoom(ZOOM_STEP, Offset(size.width / 2f, size.height / 2f)) }, iconOnly = true)
            ToolbarButton(stringResource(Res.string.topology_fit), Lucide.Maximize2, ::fit, iconOnly = true)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(palette.workspace).onSizeChanged { size = it }
                .pointerInput(layout) {
                    detectTransformGestures { centroid, pan, factor, _ ->
                        zoom(factor, centroid)
                        shift += pan
                    }
                }
                .pointerInput(layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.first()
                            zoom(exp(-change.scrollDelta.y * 0.1f), change.position)
                            change.consume()
                        }
                    }
                },
        ) {
            fun place(node: TopologyNode) = Offset(size.width / 2f + shift.x + node.x * scale, size.height / 2f + shift.y + node.y * scale)
            Canvas(Modifier.fillMaxSize()) {
                for ((from, to) in shown.links) drawLine(palette.faint, place(from), place(to), strokeWidth = 1.5.dp.toPx())
            }
            for (node in shown.nodes) {
                val at = place(node) - half
                // a map fitted into a narrow screen shrinks its nodes with their distances, or they would lie on each other
                val shrunk = Modifier.graphicsLayer {
                    scaleX = scale.coerceAtMost(1f)
                    scaleY = scale.coerceAtMost(1f)
                }
                NodeBox(node, Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.then(shrunk)) { chosen = it }
            }
            if (shown.nodes.isEmpty()) Text(stringResource(Res.string.topology_no_nodes), Modifier.align(Alignment.Center), color = palette.muted)
        }
    }
}

@Composable
private fun NodeBox(node: TopologyNode, modifier: Modifier, onOpen: (String) -> Unit) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val target = node.target
    Column(
        modifier.size(NODE_WIDTH, NODE_HEIGHT).clip(shape).background(palette.raised).border(1.dp, if (target != null) palette.accent else palette.line, shape)
            .then(if (target != null) Modifier.clickable { onOpen(target) } else Modifier)
            .padding(horizontal = 6.dp).semantics(mergeDescendants = true) {}.testTag("topology-node"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(node.name, style = Theme.type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(node.device ?: target.orEmpty(), color = palette.muted, style = Theme.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
