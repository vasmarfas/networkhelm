package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun TextMenus(dark: Boolean, content: @Composable () -> Unit) {
    val look = if (dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
    val menus = remember(look) {
        object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                DisableSelection { look.Representation(state, items) }
            }
        }
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides menus, content = content)
}
