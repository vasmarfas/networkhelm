package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReturnTest {

    private class Sight : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    // the first read of the window loses its connection while the app is out of sight, as a poll the system kept off the network
    private fun SkikoComposeUiTest.returned(menu: String, list: String, rows: List<Item>, shown: String) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf(list to rows))
        var reads = 0
        val failing = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
                if (path.toString() == list && ++reads == 1) throw DeviceException(ErrorCategory.ConnectionLost, "failed to connect")
                emitAll(router.watch(path, filter, properties))
                awaitCancellation()
            }
        }
        val sight = Sight().apply { registry.currentState = Lifecycle.State.CREATED }
        val storage = MemoryStorage()
        val tab = RouterTab(failing, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalLifecycleOwner provides sight) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry(menu, list))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("failed to connect")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(5_000)
        assertEquals(1, reads, "nothing is asked again while the app is out of sight")
        sight.registry.currentState = Lifecycle.State.RESUMED
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(shown)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("failed to connect")).fetchSemanticsNodes().isEmpty())
        assertEquals(2, reads)
    }

    @Test
    fun aListThatLostTheConnectionOutOfSightIsReadAgainWhenTheAppIsBack() = runDesktopComposeUiTest(1000, 620) {
        returned("Firewall", "ip/firewall/filter", listOf(Item("*1", mapOf("chain" to "input", "action" to "drop"))), "drop")
    }

    @Test
    fun aLogThatLostTheConnectionOutOfSightIsReadAgainWhenTheAppIsBack() = runDesktopComposeUiTest(1000, 620) {
        val line = Item("*1", mapOf("time" to "2026-10-27 09:40:00", "topics" to "system,info", "message" to "the router came up"))
        returned("Log", "log", listOf(line), "the router came up")
    }
}
