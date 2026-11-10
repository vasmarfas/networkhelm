package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NativeScreenLabTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun lab(prefix: String): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        val lab = Properties().apply { file?.inputStream()?.use(::load) }
        for (key in listOf("host", "user", "password")) assumeTrue("$prefix.$key is empty", !lab.getProperty("$prefix.$key").isNullOrEmpty())
        Locale.setDefault(Locale.ENGLISH)
        return lab
    }

    private suspend fun removeEntries(session: DeviceSession, list: MenuPath) = withTimeout(15_000) {
        val items = (session.watch(list, Filter.Equals("list", "rd-test-native")).first() as ListEvent.Snapshot).items
        if (items.isNotEmpty()) session.remove(list, items.map { it.id })
    }

    private fun <T> SkikoComposeUiTest.offTest(call: suspend () -> T): T {
        val answer = CoroutineScope(Dispatchers.Default).async { call() }
        waitUntil(timeoutMillis = 30_000) { answer.isCompleted }
        return runBlocking { answer.await() }
    }

    @Test
    fun theWindowsAndTheirDialogsWorkOverTheConsoles() = windows("chr")

    @Test
    fun theWindowsAndTheirDialogsWorkOverTheConsolesOfRouterOs6() = windows("chr6")

    private fun windows(prefix: String) = runDesktopComposeUiTest(1280, 800) {
        val lab = lab(prefix)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = runBlocking { connectNativeHost(lab.getProperty("$prefix.host"), 8291, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), scope) }
        val list = MenuPath.parse("ip/firewall/address-list")
        try {
            runBlocking { removeEntries(session, list) }
            val storage = MemoryStorage()
            val tab = RouterTab(session, session.device.identity, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Interfaces", "interface"))
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("bps", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            picture("native-interfaces-$prefix")

            tab.open(MenuEntry("Address List", list.toString()))
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("New")).fetchSemanticsNodes().size >= 2 }
            onAllNodes(hasText("New")).onLast().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("field-list")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-list"))).onLast().performTextInput("rd-test-native")
            onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-address"))).onLast().performTextInput("203.0.113.51")
            onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-comment"))).onLast().performTextInput("made in a window; \"quoted\"")
            picture("native-dialog-$prefix")
            onNodeWithText("OK").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("OK")).fetchSemanticsNodes().isEmpty() }
            // the lab router keeps more addresses than the window shows, and the new one is found by the filter of the window
            onAllNodes(hasTestTag("find")).onLast().performTextInput("203.0.113.51")
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("203.0.113.51") and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, offTest { session.count(list, Filter.Equals("list", "rd-test-native")) })
            assertEquals("made in a window; \"quoted\"", offTest { (session.watch(list, Filter.Equals("list", "rd-test-native")).first() as ListEvent.Snapshot).items.single()["comment"] })
            onAllNodes(hasText("203.0.113.51") and !hasSetTextAction()).onLast().performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasSetTextAction() and hasText("203.0.113.51") and hasAnyAncestor(hasTestTag("field-address"))).fetchSemanticsNodes().isNotEmpty() }
            picture("native-opened-$prefix")
        } finally {
            runCatching { offTest { removeEntries(session, list) } }
            session.close()
            scope.cancel()
        }
    }
}
