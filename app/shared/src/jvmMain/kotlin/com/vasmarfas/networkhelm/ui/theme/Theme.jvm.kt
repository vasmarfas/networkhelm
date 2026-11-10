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

// Skia on Windows ignores ClearType and draws grey text; whole-pixel glyph positions make the stems crisper
@OptIn(ExperimentalTextApi::class)
internal actual val textRasterization: PlatformTextStyle? =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        PlatformTextStyle(null, PlatformParagraphStyle(FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Normal, subpixelPositioning = false, autoHintingForced = false)))
    } else {
        null
    }

// Segoe UI, the sans-serif of Windows, comes out thin and grey at the size of the classic theme; Tahoma holds its stems on whole pixels
@OptIn(ExperimentalTextApi::class)
internal actual val systemSans: FontFamily? =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) FontFamily(SystemFont("Tahoma"), SystemFont("Tahoma", FontWeight.Bold)) else FontFamily.SansSerif

@Composable
internal actual fun systemAccent(): Color? = null
