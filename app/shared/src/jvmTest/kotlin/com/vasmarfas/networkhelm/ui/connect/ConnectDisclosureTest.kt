package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Secrets
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import eu.anifantakis.lib.ksafe.KSafeProtectionInfo
import eu.anifantakis.lib.ksafe.KSafeProtectionLevel
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConnectDisclosureTest {
    @Test
    fun rememberedPasswordsShowTheNegotiatedProtectionAndAnUnavailableStore() = runDesktopComposeUiTest(1100, 900) {
        Locale.setDefault(Locale.ENGLISH)
        var currentProtection by mutableStateOf(KSafeProtectionInfo(KSafeProtectionLevel.SANDBOX_PROTECTED, KSafeProtectionLevel.SOFTWARE, "Software", emptyList()))
        val secrets = object : Secrets by MemorySecrets() {
            override val protection get() = currentProtection
        }
        val storage = MemoryStorage()
        val services = Services(storage, MemoryTrustStore(), null, secrets, scan = null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("secrets-protection").assertDoesNotExist()
        onNodeWithText("Remember the password").performClick()
        onNodeWithTag("secrets-protection").assertTextEquals("Remembered passwords use software encryption; the key is kept in the app’s data folder.")
        runOnIdle { currentProtection = currentProtection.copy(effectiveLevel = KSafeProtectionLevel.SANDBOX_PROTECTED) }
        onNodeWithTag("secrets-protection").assertTextEquals("Remembered passwords use the system’s protected storage.")
        runOnIdle { currentProtection = currentProtection.copy(notes = listOf("jvm_os_vault_degraded")) }
        onNodeWithTag("secrets-protection").assertTextEquals("Protected storage is unavailable; passwords cannot be remembered.")
    }

    @Test
    fun documentationOnlyDriversAreNamedWithoutMarkingTheOtherDrivers() = runDesktopComposeUiTest(1100, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val services = Services(storage, MemoryTrustStore(), null, scan = null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("platform-unverified").assertDoesNotExist()
        for ((platform, driver) in listOf("OpenWrt" to "RutOS (Teltonika)", "Keenetic" to "Keenetic / Netcraze")) {
            onNodeWithTag("platform").performClick()
            onAllNodes(hasText(platform)).onLast().performClick()
            onNodeWithTag("platform-unverified").assertTextEquals("$driver: written from the vendor documentation, not yet tried on a real device.")
        }
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("Other device: SSH, SNMP, UPnP")).onLast().performClick()
        onNodeWithTag("platform-unverified").assertDoesNotExist()
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("UPnP IGD and TR-064, read only (SSDP 1900)")).onLast().performClick()
        onNodeWithTag("platform-unverified").assertTextEquals("TR-064: written from the vendor documentation, not yet tried on a real device.")
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("OPNsense")).onLast().performClick()
        onNodeWithTag("platform-unverified").assertDoesNotExist()
    }
}
