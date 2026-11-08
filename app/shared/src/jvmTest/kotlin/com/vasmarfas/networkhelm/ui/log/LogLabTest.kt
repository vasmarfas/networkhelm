package com.vasmarfas.networkhelm.ui.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.connectOpenWrtWithForms
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.routeros.connectRouterOs
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.session.watchSettings
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.ui.main.MainScreen
import com.vasmarfas.networkhelm.ui.main.RouterTab
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class LogLabTest {

    private fun lab(): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        return Properties().apply { file?.inputStream()?.use(::load) }
    }

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.present(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.drawn(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun SkikoComposeUiTest.shown(name: String, path: String, session: DeviceSession) {
        val rows = runBlocking { withTimeoutOrNull(30.seconds) { session.watch(MenuPath.parse(path)).rows().first { it.isNotEmpty() } } }.orEmpty()
        val clock = runBlocking { readClock(session) }
        val entries = rows.map { it.toEntry(clock.today) }
        println("LOG[$name] ${entries.size} lines: ${entries.count { it.stamp == null }} without a stamp, today ${clock.today}, skew of the clock ${clock.skew} s, severities ${entries.severityCounts()}")
        for (entry in entries.take(3) + entries.takeLast(3)) {
            println("LOG[$name] time '${entry.time}' topics '${entry.topics}' stamp ${entry.stamp?.date} ${entry.stamp?.clock} ${entry.severity}: ${entry.message.take(90)}")
        }
        val storage = MemoryStorage()
        val tab = RouterTab(session, "lab", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalClipboard provides FakeClipboard()) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Log", path))
        waitUntil(timeoutMillis = 60_000) { present("log-list") && !present("log-empty") }
        picture("log-lab-$name")
        assertTrue(entries.isNotEmpty() && !drawn("log-count").startsWith("0 "), "the log of $name has lines")
    }

    private fun routerOs(prefix: String, test: SkikoComposeUiTest.(DeviceSession) -> Unit) = runDesktopComposeUiTest(1200, 700) {
        val lab = lab()
        val host = lab.getProperty("$prefix.host").orEmpty()
        assumeTrue("$prefix.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val login = RouterOsLogin(host, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), RouterOsProtocol.Api)
        val session = runBlocking { connectRouterOs(login, TrustPolicy(MemoryTrustStore()) { true }, scope) }
        try {
            test(session)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    private fun openWrt(prefix: String, test: SkikoComposeUiTest.(DeviceSession) -> Unit) = runDesktopComposeUiTest(1200, 700) {
        val lab = lab()
        val host = lab.getProperty("$prefix.host").orEmpty()
        assumeTrue("$prefix.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val key = lab.getProperty("$prefix.ssh.hostKey")
        val login = OpenWrtLogin(host, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"))
        val session = runBlocking { connectOpenWrtWithForms(login, TrustPolicy(MemoryTrustStore()) { it.fingerprint == key }) }
        try {
            test(session)
        } finally {
            session.close()
        }
    }

    private fun clockOf(name: String, session: DeviceSession) {
        val clock = runBlocking { readClock(session) }
        // the clock gives the local time of the router, so a router outside UTC is ahead or behind by its offset
        val offset = if (name.startsWith("openwrt")) 0 else runBlocking { session.watchSettings(MenuPath.parse("system/clock"), listOf("gmt-offset")).first()["gmt-offset"] }?.let(::seconds) ?: 0
        println("LOG[$name] clock of the router: today ${clock.today}, skew ${clock.skew} s, offset $offset s")
        assertTrue(abs(clock.skew - offset) < 600, "the clock of $name is read from the router: ${clock.skew}")
    }

    private fun seconds(offset: String): Long {
        val (hours, minutes) = offset.drop(1).split(':').map { it.toLong() }
        return (if (offset.startsWith('-')) -1 else 1) * (hours * 3600 + minutes * 60)
    }

    @Test
    fun theClockOfRouterOs7IsRead() = routerOs("chr") { clockOf("chr", it) }

    @Test
    fun theClockOfRouterOs6IsRead() = routerOs("chr6") { clockOf("chr6", it) }

    @Test
    fun theClockOfOpenWrtIsRead() = openWrt("openwrt") { clockOf("openwrt", it) }

    @Test
    fun theLogOfRouterOs7IsReadAsLines() = routerOs("chr") { shown("chr", "log", it) }

    @Test
    fun theLogOfRouterOs6IsReadAsLines() = routerOs("chr6") { shown("chr6", "log", it) }

    @Test
    fun theLogOfOpenWrtIsReadAsLines() = openWrt("openwrt") { shown("openwrt", "status/log", it) }

    @Test
    fun theKernelLogOfOpenWrtIsReadAsLines() = openWrt("openwrt") { shown("openwrt-kernel", "status/kernel-log", it) }
}
