package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.transport.BrowserBlockedException
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConnectBrowserTest {

    @Test
    fun aRouterOsRequestThatTheBrowserBlockedIsExplainedByTheMissingCorsHeaders() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), emptyFlow()) { _, _, _ -> throw BrowserBlockedException("https://192.0.2.7/rest") }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope(), TableLayouts(services.storage)) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.7")
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("RouterOS sends no CORS headers", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRequestOfAnotherPlatformGetsTheGeneralText() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), emptyFlow(), connectLogin = { _, _ -> throw BrowserBlockedException("https://192.0.2.7/api") })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope(), TableLayouts(services.storage)) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.7")
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("OPNsense")).onLast().performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("no CORS headers, plain HTTP from an HTTPS page", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
