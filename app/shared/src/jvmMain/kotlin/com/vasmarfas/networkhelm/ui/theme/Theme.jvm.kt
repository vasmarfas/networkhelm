package com.vasmarfas.networkhelm.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.PixelGeometry
import java.awt.RenderingHints
import java.awt.Toolkit

private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")

// the order of the subpixels ClearType draws in, as Windows is set; with ClearType off the text stays grey
internal val subpixels: PixelGeometry =
    if (!WINDOWS) {
        PixelGeometry.UNKNOWN
    } else {
        when ((Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>)?.get(RenderingHints.KEY_TEXT_ANTIALIASING)) {
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB -> PixelGeometry.RGB_H
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR -> PixelGeometry.BGR_H
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB -> PixelGeometry.RGB_V
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR -> PixelGeometry.BGR_V
            else -> PixelGeometry.UNKNOWN
        }
    }

// Skia draws ClearType only on a surface that knows the order of the subpixels, which the layer of a window gets from ClearTypeLayer;
// whole-pixel glyph positions make the stems crisper
@OptIn(ExperimentalTextApi::class)
internal actual val textRasterization: PlatformTextStyle? =
    if (WINDOWS) {
        val smoothing = if (subpixels == PixelGeometry.UNKNOWN) FontSmoothing.AntiAlias else FontSmoothing.SubpixelAntiAlias
        PlatformTextStyle(null, PlatformParagraphStyle(FontRasterizationSettings(smoothing, FontHinting.Normal, subpixelPositioning = false, autoHintingForced = false)))
    } else {
        null
    }

// Segoe UI, the sans-serif of Windows, comes out thin and grey at the size of the classic theme; Tahoma holds its stems on whole pixels
@OptIn(ExperimentalTextApi::class)
internal actual val systemSans: FontFamily? =
    if (WINDOWS) FontFamily(SystemFont("Tahoma"), SystemFont("Tahoma", FontWeight.Bold)) else FontFamily.SansSerif

@Composable
internal actual fun systemAccent(): Color? = null
