package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.table.SavedSort
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SavedGroupsTest {
    private fun saved(storage: MemoryStorage, vararg routers: SavedRouter) = SavedRouters(storage).also { saved -> runBlocking { routers.forEach { saved.save(it) } } }

    private fun services(storage: MemoryStorage) = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { _, _, _ -> error("not used") }

    private fun header(name: String) = hasText(name) and hasAnyAncestor(hasTestTag("category"))

    private fun SkikoComposeUiTest.tops(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().map { it.positionInRoot.y }

    private fun SkikoComposeUiTest.top(matcher: SemanticsMatcher) = tops(matcher).firstOrNull()

    // tall enough for the form and the lists of a phone without scrolling the page
    @Test
    fun everyCardOfThePhoneShowsItsGroupAndFoldsByIt() = runDesktopComposeUiTest(400, 1600) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = saved(
            storage,
            SavedRouter("192.0.2.53", "rd-test", group = "office"),
            SavedRouter("192.0.2.51", "rd-test", group = "home"),
            SavedRouter("192.0.2.52", "rd-test", group = "office"),
        )
        val tables = TableLayouts(storage)
        tables["connect/saved"] = TableLayout(sort = listOf(SavedSort("address", true)))
        setContent {
            CompositionLocalProvider(LocalCompact provides true) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services(storage), saved, rememberCoroutineScope(), tables) { _, _, _ -> } }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            val cards = listOf("192.0.2.51", "192.0.2.52", "192.0.2.53").map { top(hasText("Address $it")) ?: -1f }
            cards.first() >= 0f && cards == cards.sorted()
        }
        val cards = listOf("192.0.2.51", "192.0.2.52", "192.0.2.53").map { top(hasText("Address $it"))!! }
        val groups = (tops(hasText("Group home")).map { it to "home" } + tops(hasText("Group office")).map { it to "office" }).sortedBy { it.first }
        assertEquals(listOf("home", "office", "office"), groups.map { it.second })
        groups.forEachIndexed { index, (top, _) -> assertTrue(top >= cards[index] &&(index == cards.lastIndex || top < cards[index + 1]), "group $index at $top, cards at $cards") }

        onNodeWithTag("saved-groups").performClick()
        waitUntil(timeoutMillis = 5_000) { top(header("office")) != null }
        onNode(header("office")).performClick()
        waitUntil(timeoutMillis = 5_000) { top(hasText("Address 192.0.2.52")) == null }
        assertNull(top(hasText("Address 192.0.2.53")))
        assertTrue(top(hasText("Address 192.0.2.51")) != null)
        assertTrue(top(header("2")) != null)
    }

    @Test
    fun foldedGroupsStayFoldedForTheNextStartAndRoutersWithoutAGroupComeLast() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = saved(
            storage,
            SavedRouter("192.0.2.61", "rd-test", group = "office"),
            SavedRouter("192.0.2.62", "rd-test"),
            SavedRouter("192.0.2.63", "rd-test", group = "home"),
            SavedRouter("192.0.2.64", "rd-test", group = "office"),
        )
        val services = services(storage)
        var start by mutableIntStateOf(0)
        setContent {
            key(start) {
                val tables = remember { TableLayouts(storage) }
                LaunchedEffect(tables) { tables.keep() }
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope(), tables) { _, _, _ -> } }
            }
        }
        waitUntil(timeoutMillis = 5_000) { top(hasText("192.0.2.62")) != null }
        assertNull(top(header("office")))
        onNodeWithTag("saved-groups").performClick()
        waitUntil(timeoutMillis = 5_000) { top(header("No group")) != null }
        val order = listOf(header("home"), hasText("192.0.2.63"), header("office"), hasText("192.0.2.61"), hasText("192.0.2.64"), header("No group"), hasText("192.0.2.62"))
            .map { top(it)!! }
        assertEquals(order.sorted(), order)

        onNode(header("office")).performClick()
        waitUntil(timeoutMillis = 5_000) { top(hasText("192.0.2.61")) == null }
        assertNull(top(hasText("192.0.2.64")))
        assertTrue(top(header("2")) != null)
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("tables.json") }.orEmpty().contains("office") }

        runOnIdle { start++ }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { top(header("office")) != null && top(hasText("192.0.2.63")) != null }
        assertNull(top(hasText("192.0.2.61")))
        assertNull(top(hasText("192.0.2.64")))
        assertTrue(top(header("No group"))!! < top(hasText("192.0.2.62"))!!)
    }
}
