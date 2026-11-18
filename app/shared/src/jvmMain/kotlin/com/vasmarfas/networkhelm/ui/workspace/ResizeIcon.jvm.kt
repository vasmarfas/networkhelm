package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

// X11 draws each of these as an arrow to its own edge, so every edge and corner names its side
internal actual fun resizeIcon(x: Int, y: Int): PointerIcon = PointerIcon(
    Cursor(
        when {
            y == 0 -> if (x < 0) Cursor.W_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR
            x == 0 -> if (y < 0) Cursor.N_RESIZE_CURSOR else Cursor.S_RESIZE_CURSOR
            x < 0 -> if (y < 0) Cursor.NW_RESIZE_CURSOR else Cursor.SW_RESIZE_CURSOR
            else -> if (y < 0) Cursor.NE_RESIZE_CURSOR else Cursor.SE_RESIZE_CURSOR
        },
    ),
)
