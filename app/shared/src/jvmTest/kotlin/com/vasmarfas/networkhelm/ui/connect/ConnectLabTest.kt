package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.connectOpenWrtWithForms
import com.vasmarfas.networkhelm.miknative.connectMacTelnetOnInterface
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.miknative.label
import com.vasmarfas.networkhelm.miknative.listBroadcastInterfaces
import com.vasmarfas.networkhelm.miknative.macTelnetInterfaces
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectLabTest {

    private fun lab(): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        return Properties().apply { file?.inputStream()?.use(::load) }
    }

    @Test
    fun connectsToTheLabRouterThroughTheWindow() = runDesktopComposeUiTest(1100, 760) {
        val lab = lab()
        val host = lab.getProperty("chr.host").orEmpty()
        assumeTrue("chr.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), neighbors = null)
        var opened: DeviceSession? = null
        setContent {
            NetworkHelmTheme {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("address").performTextInput(host)
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput(lab.getProperty("chr.user"))
        onNodeWithTag("password").performTextInput(lab.getProperty("chr.password"))
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("trust")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Trust").performClick()
        waitUntil(timeoutMillis = 30_000) { opened != null || onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        val session = opened ?: error(onNodeWithTag("problem").fetchSemanticsNode().config.toString())
        try {
            assertTrue(session.device.identity.isNotEmpty() && session.device.version.isNotEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun connectsOverTcp8291ToAnotherPort() = runDesktopComposeUiTest(1100, 760) {
        val lab = lab()
        val host = System.getProperty("networkhelm.nativeHost")?.takeIf { it.isNotEmpty() } ?: lab.getProperty("chr.host").orEmpty()
        val moved = System.getProperty("networkhelm.nativePort").orEmpty()
        assumeTrue("chr.host is empty", host.isNotEmpty())
        assumeTrue("-PnativePort does not name the port to connect to", moved.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(
            MemoryStorage(), MemoryTrustStore(), neighbors = null,
            connectTcp8291 = { address, port, user, pass, encoding, scope -> withContext(Dispatchers.Default) { connectNativeHost(address, port, user, pass, encoding, scope) } },
        )
        var opened: DeviceSession? = null
        setContent {
            NetworkHelmTheme {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("TCP 8291 (WinBox port)")).onLast().performClick()
        onNodeWithTag("address").performTextInput(host)
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput(lab.getProperty("chr.user"))
        onNodeWithTag("password").performTextInput(lab.getProperty("chr.password"))
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("$host does not accept connections on port 8291: the service is off or the port is closed.")
        onNodeWithTag("port").performTextInput(moved)
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 30_000) { opened != null || onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        val session = opened ?: error(onNodeWithTag("problem").fetchSemanticsNode().config.toString())
        try {
            runBlocking {
                assertTrue(session.count(MenuPath.parse("file")) > 0)
                val console = checkNotNull(session.ssh).shell("xterm", 80, 24)
                try {
                    val seen = console.readToPrompt()
                    assertTrue("] > " in seen, seen)
                } finally {
                    console.close()
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun connectsOverMacTelnetThroughTheWindow() = runDesktopComposeUiTest(1100, 760) {
        val lab = lab()
        val target = System.getProperty("networkhelm.macTarget").orEmpty()
        val from = System.getProperty("networkhelm.macFrom").orEmpty()
        assumeTrue("-PmacTarget and -PmacFrom do not name the link", target.isNotEmpty() && from.isNotEmpty())
        val link = checkNotNull(listBroadcastInterfaces().firstOrNull { it.address == from }) { "no interface has the address $from" }
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(
            MemoryStorage(), MemoryTrustStore(), neighbors = null,
            networkInterfaces = ::macTelnetInterfaces,
            connectMacTelnet = { mac, user, pass, interfaceName, encoding, scope -> withContext(Dispatchers.Default) { connectMacTelnetOnInterface(mac, user, pass, interfaceName, encoding, scope) } },
        )
        var opened: DeviceSession? = null
        setContent {
            NetworkHelmTheme {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("address").performTextInput(target)
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("interface").performClick()
        onAllNodes(hasText(link.label())).onLast().performClick()
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput(lab.getProperty("chr.user"))
        onNodeWithTag("password").performTextInput(lab.getProperty("chr.password"))
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 30_000) { opened != null || onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        val session = opened ?: error(onNodeWithTag("problem").fetchSemanticsNode().config.toString())
        try {
            runBlocking {
                val console = checkNotNull(session.ssh).shell("xterm", 80, 24)
                try {
                    val seen = console.readToPrompt()
                    assertTrue("] > " in seen, seen)
                } finally {
                    console.close()
                }
            }
        } finally {
            session.close()
        }
    }

    private suspend fun ShellChannel.readToPrompt(): String {
        val seen = StringBuilder()
        val buffer = ByteArray(4096)
        withTimeout(30_000) {
            while ("] > " !in seen) {
                val count = read(buffer, 0, buffer.size)
                if (count < 0) break
                seen.append(buffer.decodeToString(0, count))
            }
        }
        return seen.toString()
    }

    @Test
    fun connectsToTheLabOpenWrtOverSsh() = connectsToTheLabOpenWrt("SSH (22)")

    @Test
    fun connectsToTheLabOpenWrtOverHttps() = connectsToTheLabOpenWrt("ubus over HTTPS (443)")

    private fun connectsToTheLabOpenWrt(protocol: String) = runDesktopComposeUiTest(1100, 760) {
        val lab = lab()
        val host = lab.getProperty("openwrt.host").orEmpty()
        assumeTrue("openwrt.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), neighbors = null, connectOpenWrt = ::connectOpenWrtWithForms)
        var opened: DeviceSession? = null
        setContent {
            NetworkHelmTheme {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("OpenWrt")).onLast().performClick()
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText(protocol)).onLast().performClick()
        onNodeWithTag("address").performTextInput(host)
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput(lab.getProperty("openwrt.user"))
        onNodeWithTag("password").performTextInput(lab.getProperty("openwrt.password"))
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("trust")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Trust").performClick()
        waitUntil(timeoutMillis = 60_000) { opened != null || onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        val session = opened ?: error(onNodeWithTag("problem").fetchSemanticsNode().config.toString())
        try {
            assertEquals(Platform.OpenWrt, session.device.platform)
            assertEquals("OpenWrt", session.device.identity)
        } finally {
            session.close()
        }
    }
}
