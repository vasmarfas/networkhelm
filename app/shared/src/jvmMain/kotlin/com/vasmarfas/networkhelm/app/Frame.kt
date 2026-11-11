package com.vasmarfas.networkhelm.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.subpixels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.SkiaLayer
import java.awt.Color
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Rectangle
import java.lang.reflect.Field

private const val FRAME = "window.json"

// wide enough for the lists of the connect screen to stand beside the form
private val FIRST_SIZE = DpSize(1200.dp, 800.dp)

private val MAC = System.getProperty("os.name").orEmpty().startsWith("Mac")

// macOS takes a press this close to the edge of a window for a resize
private val RESIZE_EDGE = 5.dp

@Serializable
private class SavedFrame(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean = false)

// the main window opens where and as large as it was last, and in the middle of the screen when that place is on no screen now
@Composable
fun rememberFrameState(storage: Storage): WindowState {
    val state = remember {
        val saved = runBlocking { storage.read(FRAME) }?.let { text ->
            try {
                AppSettings.json.decodeFromString(SavedFrame.serializer(), text)
            } catch (e: SerializationException) {
                null
            }
        }
        WindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.takeIf(::seen)?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: FIRST_SIZE,
        )
    }
    LaunchedEffect(state) {
        // a maximized window keeps the place it had before, where it goes back when it is restored
        var floating: Pair<WindowPosition, DpSize>? = null
        snapshotFlow { Triple(state.placement, state.position, state.size) }.drop(1).collectLatest { (placement, position, size) ->
            if (placement == WindowPlacement.Floating && position is WindowPosition.Absolute) floating = position to size
            val (at, of) = floating ?: return@collectLatest
            delay(500)
            val frame = SavedFrame(at.x.value, at.y.value, of.width.value, of.height.value, placement == WindowPlacement.Maximized)
            storage.keep(FRAME, AppSettings.json.encodeToString(SavedFrame.serializer(), frame))
        }
    }
    return state
}

private fun seen(frame: SavedFrame): Boolean = try {
    val place = Rectangle(frame.x.toInt(), frame.y.toInt(), frame.width.toInt(), frame.height.toInt())
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { screen ->
        screen.defaultConfiguration.bounds.intersection(place).let { it.width >= 100 && it.height >= 50 }
    }
} catch (e: HeadlessException) {
    false
}

// a window that grows shows its own background until the next frame is drawn, the light grey of AWT unless it takes the colour of the workspace
@Composable
fun FrameWindowScope.WorkspaceBackground() {
    val color = Theme.palette.workspace.toArgb()
    LaunchedEffect(window, color) {
        window.background = Color(color)
        window.contentPane.background = Color(color)
    }
}

// ClearType needs a surface that knows the order of the subpixels, which Compose leaves unknown in the layer of a window; the layer takes the
// order of Windows before the window shows its first frame. A Skiko without that field, or a runtime that keeps a final field final, leaves the text grey
@Composable
fun FrameWindowScope.ClearTypeLayer() {
    SideEffect { window.takeSubpixels(subpixels) }
}

private val layerGeometry: Field? = try {
    SkiaLayer::class.java.getDeclaredField("pixelGeometry").apply { isAccessible = true }
} catch (e: NoSuchFieldException) {
    null
}

internal fun Container.takeSubpixels(geometry: PixelGeometry) {
    if (geometry == PixelGeometry.UNKNOWN) return
    val field = layerGeometry ?: return
    for (component in components) {
        when (component) {
            is SkiaLayer -> try {
                field.set(component, geometry)
            } catch (e: IllegalAccessException) {
                return
            }
            is Container -> component.takeSubpixels(geometry)
        }
    }
}

// macOS starts a resize from a strip just inside the left, right and bottom edges and still hands the press to the window,
// where a list under it selected rows and text as the edge moved; the strips keep such presses from the content
@Composable
fun ResizeEdges(content: @Composable () -> Unit) {
    if (!MAC) return content()
    Box(Modifier.fillMaxSize()) {
        content()
        val held = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(RESIZE_EDGE).then(held))
        Box(Modifier.align(Alignment.TopStart).fillMaxHeight().width(RESIZE_EDGE).then(held))
        Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(RESIZE_EDGE).then(held))
    }
}
