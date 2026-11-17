package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.demoRouters
import com.vasmarfas.networkhelm.app.withDemo
import com.vasmarfas.networkhelm.transport.BrowserTrust
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DemoWebTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun opens(host: String, identity: String, firstRow: String, name: String) = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        runBlocking { demoRouters().forEach { saved.save(it) } }
        setContent { App(Services(storage, BrowserTrust(), neighbors = null, scan = null, standalone = true).withDemo()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(host)).fetchSemanticsNodes().isNotEmpty() }
        if (name == "routeros") picture("demo-connect")
        onNodeWithText(host).performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText(identity)).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("Interfaces")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Interfaces")).onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText(firstRow)).fetchSemanticsNodes().isNotEmpty() }
        picture("demo-$name")
    }

    @Test
    fun thePageStartsOnRestAndRefusesWhatIsNotHttp() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { App(Services(MemoryStorage(), BrowserTrust(), neighbors = null, scan = null, standalone = true).withDemo()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("REST over HTTPS (443)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Grey: a page in a browser cannot speak this protocol, only HTTP").assertExists()
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("protocol").performClick()
        onNodeWithText("API (8728)").performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("a page in a browser can send HTTP requests only", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun routerOsOpens() = opens("routeros.demo", "demo-hap", "ether1", "routeros")

    @Test
    fun openWrtOpens() = opens("openwrt.demo", "demo-ap", "wan6", "openwrt")

    @Test
    fun opnsenseOpens() = opens("opnsense.demo", "demo-fw.lan", "igc0", "opnsense")

    @Test
    fun pfsenseOpens() = opens("pfsense.demo", "demo-pf.lan", "igb0", "pfsense")

    @Test
    fun vyosOpens() = opens("vyos.demo", "demo-vyos", "eth0", "vyos")

    @Test
    fun keeneticOpens() = opens("keenetic.demo", "Keenetic Demo", "Home network", "keenetic")
}
