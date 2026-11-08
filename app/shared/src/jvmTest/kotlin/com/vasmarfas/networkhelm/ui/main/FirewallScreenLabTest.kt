package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.opnsense.OpnsenseLogin
import com.vasmarfas.networkhelm.opnsense.connectOpnsense
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.pfsense.PfsenseLogin
import com.vasmarfas.networkhelm.pfsense.connectPfsense
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FirewallScreenLabTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun lab(vararg keys: String): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        val lab = Properties().apply { file?.inputStream()?.use(::load) }
        for (key in keys) assumeTrue("$key is empty", !lab.getProperty(key).isNullOrEmpty())
        Locale.setDefault(Locale.ENGLISH)
        return lab
    }

    private suspend fun trust(lab: Properties, prefix: String): TrustPolicy {
        val store = MemoryTrustStore()
        store.put(lab.getProperty("$prefix.host"), 443, TrustKind.Tls, lab.getProperty("$prefix.certificate"))
        return TrustPolicy(store) { false }
    }

    private fun SkikoComposeUiTest.aliasInTheWindow(session: DeviceSession, fields: Map<String, String>, choose: Pair<String, String>?, picture: String) {
        val aliases = MenuPath.parse("firewall/alias")
        val storage = MemoryStorage()
        val tab = RouterTab(session, session.device.identity, bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", aliases.toString()))
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("New")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("New")).onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag("field-name")).fetchSemanticsNodes().isNotEmpty() }
        choose?.let { (field, value) ->
            onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-$field"))).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText(value)).onLast().performClick()
        }
        for ((field, value) in fields) onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$field"))).performTextInput(value)
        picture("$picture-dialog")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("OK")).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("rd_test")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { session.count(aliases, Filter.Equals("name", "rd_test")) })
        onAllNodes(hasText("rd_test")).onLast().performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasSetTextAction() and hasText("rd-test")).fetchSemanticsNodes().isNotEmpty() }
        picture("$picture-opened")
        onAllNodes(hasText("Cancel")).onLast().performClick()
        onNodeWithTag("safe-mode").performClick()
        waitUntil(timeoutMillis = 15_000) { runBlocking { session.safeMode?.actions?.first() } == 0 }
        picture(picture)
    }

    private fun removeAlias(session: DeviceSession) = runBlocking {
        val aliases = MenuPath.parse("firewall/alias")
        session.remove(aliases, (session.watch(aliases, Filter.Equals("name", "rd_test")).first() as ListEvent.Snapshot).items.map { it.id })
        session.safeMode?.enter()
    }

    @Test
    fun anAliasOfOpnsenseIsMadeInItsDialog() = runDesktopComposeUiTest(1280, 800) {
        val lab = lab("opnsense.host", "opnsense.key", "opnsense.secret", "opnsense.certificate", "opnsense.apply")
        val session = runBlocking {
            connectOpnsense(OpnsenseLogin(lab.getProperty("opnsense.host"), lab.getProperty("opnsense.key"), lab.getProperty("opnsense.secret")), trust(lab, "opnsense"))
        }
        try {
            aliasInTheWindow(session, mapOf("name" to "rd_test", "content" to "192.0.2.1", "description" to "rd-test"), "type" to "Host(s)", "opnsense-alias")
        } finally {
            removeAlias(session)
        }
    }

    @Test
    fun anAliasOfPfsenseIsMadeInItsDialog() = runDesktopComposeUiTest(1280, 800) {
        val lab = lab("pfsense.host", "pfsense.user", "pfsense.secret", "pfsense.certificate")
        val session = runBlocking {
            connectPfsense(PfsenseLogin(lab.getProperty("pfsense.host"), lab.getProperty("pfsense.user"), lab.getProperty("pfsense.secret")), trust(lab, "pfsense"))
        }
        try {
            aliasInTheWindow(session, mapOf("name" to "rd_test", "address" to "192.0.2.1", "descr" to "rd-test"), "type" to "host", "pfsense-alias")
        } finally {
            removeAlias(session)
        }
    }
}
