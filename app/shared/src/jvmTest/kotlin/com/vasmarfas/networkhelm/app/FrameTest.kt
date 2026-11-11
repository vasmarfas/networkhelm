package com.vasmarfas.networkhelm.app

import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.SkiaLayer
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameTest {

    @Test
    fun theLayerInsideAWindowTakesTheOrderOfTheSubpixelsAndAnUnknownOrderLeavesItAsItIs() {
        val layer = SkiaLayer()
        try {
            val window = JPanel().apply { add(JPanel().apply { add(layer) }) }
            window.takeSubpixels(PixelGeometry.UNKNOWN)
            assertEquals(PixelGeometry.UNKNOWN, layer.pixelGeometry)
            window.takeSubpixels(PixelGeometry.BGR_H)
            assertEquals(PixelGeometry.BGR_H, layer.pixelGeometry)
        } finally {
            SwingUtilities.invokeAndWait(layer::dispose)
        }
    }
}
