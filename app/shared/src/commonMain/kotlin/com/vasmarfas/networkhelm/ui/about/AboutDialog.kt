package com.vasmarfas.networkhelm.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.aboutTexts
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.about_chat
import networkhelm.app.shared.generated.resources.about_close
import networkhelm.app.shared.generated.resources.about_description
import networkhelm.app.shared.generated.resources.about_site
import networkhelm.app.shared.generated.resources.about_source
import networkhelm.app.shared.generated.resources.about_summary
import networkhelm.app.shared.generated.resources.about_support
import networkhelm.app.shared.generated.resources.about_telegram
import networkhelm.app.shared.generated.resources.networkhelm

private const val SITE = "https://vasmarfas.com"
private const val SOURCE = "https://github.com/vasmarfas/networkhelm"
private const val TELEGRAM = "https://t.me/vasmarfas_channel"
private const val CHAT = "https://t.me/vasmarfas_chat"
private const val SUPPORT = "https://github.com/vasmarfas/networkhelm/blob/master/SUPPORT.md"

@Composable
fun appIcon(): Painter = painterResource(Res.drawable.networkhelm)

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val palette = Theme.palette
    val links = LocalUriHandler.current
    var shown by remember { mutableIntStateOf(0) }
    val notes = rememberScrollState()
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f), onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).heightIn(max = 640.dp).padding(16.dp).dialogPanel(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(appIcon(), null, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text("NetworkHelm $appVersion", style = Theme.type.strong)
                    Text(stringResource(Res.string.about_summary), color = palette.muted, style = Theme.type.small)
                }
                IconButton(Lucide.X, stringResource(Res.string.about_close), onClose, Modifier.testTag("about-cross"))
            }
            ScrollArea(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.about_description))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(stringResource(Res.string.about_support), { links.openUri(SUPPORT) }, Modifier.testTag("about-support"), primary = true)
                    Button(stringResource(Res.string.about_site), { links.openUri(SITE) }, Modifier.testTag("about-site"))
                    Button(stringResource(Res.string.about_source), { links.openUri(SOURCE) }, Modifier.testTag("about-source"))
                    Button(stringResource(Res.string.about_telegram), { links.openUri(TELEGRAM) }, Modifier.testTag("about-telegram"))
                    Button(stringResource(Res.string.about_chat), { links.openUri(CHAT) }, Modifier.testTag("about-chat"))
                }
                TabStrip(aboutTexts.map { it.first }, shown, { shown = it })
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    SelectionContainer(Modifier.fillMaxSize()) {
                        Text(
                            aboutTexts[shown].second,
                            Modifier.fillMaxSize().background(palette.raised).verticalScroll(notes).padding(8.dp),
                            style = Theme.type.mono,
                        )
                    }
                    VerticalScrollbar(notes, Modifier.align(Alignment.CenterEnd))
                }
            }
            Row(Modifier.align(Alignment.End)) { Button(stringResource(Res.string.about_close), onClose, primary = true) }
        }
    }
}
