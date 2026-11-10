package com.vasmarfas.networkhelm.ui.theme

import android.R
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily

internal actual val textRasterization: PlatformTextStyle? = null

internal actual val systemSans: FontFamily? = FontFamily.SansSerif

@Composable
internal actual fun systemAccent(): Color? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else Color(LocalContext.current.getColor(R.color.system_accent1_500))
