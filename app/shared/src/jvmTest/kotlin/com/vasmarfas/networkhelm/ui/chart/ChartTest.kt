package com.vasmarfas.networkhelm.ui.chart

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChartTest {

    private fun half() = Ring(20).apply { repeat(20) { add(50.0) } }

    private fun PixelMap.lastRed(): Int = (0 until width).last { x -> (0 until height).any { y -> this[x, y].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f } } }

    @Test
    fun theNewestValuesAreDrawnLeftOfTheLabelsOfTheAxis() = runDesktopComposeUiTest(400, 120) {
        var label = 0
        setContent {
            NetworkHelmTheme {
                label = rememberTextMeasurer().measure(bits(100.0), Theme.type.small).size.width
                Chart(listOf(Series("rx", Color.Red, half())), Modifier.fillMaxSize(), ceiling = 100.0, format = ::bits)
            }
        }
        val end = onRoot().captureToImage().toPixelMap().lastRed()
        assertTrue(end < 400 - label && end > 400 - label - 20, "the line ends at $end, the widest label takes $label")
    }

    @Test
    fun aChartWithoutValuesWritesNoScale() = runDesktopComposeUiTest(400, 120) {
        setContent { NetworkHelmTheme { Chart(listOf(Series("rx", Color.Red, Ring(20))), Modifier.fillMaxSize(), format = ::bits) } }
        val image = onRoot().captureToImage().toPixelMap()
        assertEquals(0, (300 until 400).sumOf { x -> (0 until 120).count { y -> image[x, y] != image[10, y] } })
    }

    @Test
    fun aChartWithoutAnAxisIsDrawnToItsRightEdge() = runDesktopComposeUiTest(48, 14) {
        setContent { NetworkHelmTheme { Chart(listOf(Series("rx", Color.Red, half())), Modifier.fillMaxSize(), axis = false, ceiling = 100.0) } }
        val end = onRoot().captureToImage().toPixelMap().lastRed()
        assertTrue(end >= 46, "the line ends at $end")
    }
}
