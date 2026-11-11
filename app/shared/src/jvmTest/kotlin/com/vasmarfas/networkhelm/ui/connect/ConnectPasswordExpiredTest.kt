package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectPasswordExpiredTest {
    private class Expired(private val refusal: String? = null) : DeviceSession by FakeRouter() {
        val changes = mutableListOf<Pair<String, String>>()
        var closed = false

        override val passwordExpired = true

        override suspend fun changePassword(old: String, new: String) {
            refusal?.let { throw DeviceException(ErrorCategory.ArgumentValue, it) }
            changes += old to new
        }

        override fun close() {
            closed = true
        }
    }

    private fun SkikoComposeUiTest.connectTo(router: Expired, secrets: MemorySecrets): () -> DeviceSession? {
        var opened: DeviceSession? = null
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, secrets) { _, _, _ -> router }
        setContent {
            NetworkHelmTheme(dark = true) {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.7")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Remember the password").performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("renewal")).fetchSemanticsNodes().isNotEmpty() }
        return { opened }
    }

    @Test
    fun aRouterThatAsksForANewPasswordGetsADialogAndTheNewOneIsRemembered() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val secrets = MemorySecrets()
        val router = Expired()
        val opened = connectTo(router, secrets)
        assertNull(opened())
        onNodeWithTag("renewal-new").performTextInput("Nw-123456")
        onNodeWithTag("renewal-repeat").performTextInput("Nw-1234")
        onNodeWithText("The two passwords differ").assertExists()
        onNodeWithText("Change").assertIsNotEnabled()
        onNodeWithTag("renewal-repeat").performTextInput("56")
        onNodeWithText("Change").performClick()
        waitUntil(timeoutMillis = 5_000) { opened() != null }
        assertEquals(listOf("secret" to "Nw-123456"), router.changes)
        assertEquals("Nw-123456", runBlocking { secrets.get(passwordKey("192.0.2.7", "admin")) })
    }

    @Test
    fun afterTheChangeTheSessionOfTheOldPasswordIsClosedAndTheRouterIsEnteredWithTheNewOne() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val first = Expired()
        val second = FakeRouter()
        val entered = mutableListOf<String>()
        var opened: DeviceSession? = null
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            connectTcp8291 = { _, _, _, pass, _, _ ->
                entered += pass
                if (entered.size == 1) first else second
            },
        )
        setContent {
            NetworkHelmTheme(dark = true) {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.7")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("renewal")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("renewal-new").performTextInput("Nw-123456")
        onNodeWithTag("renewal-repeat").performTextInput("Nw-123456")
        onNodeWithText("Change").performClick()
        waitUntil(timeoutMillis = 5_000) { opened != null }
        assertEquals(listOf("secret" to "Nw-123456"), first.changes)
        assertTrue(first.closed)
        assertEquals(listOf("secret", "Nw-123456"), entered)
        assertSame(second, opened)
    }

    @Test
    fun skippingTheQuestionGoesOnWithTheOldPassword() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val secrets = MemorySecrets()
        val router = Expired()
        val opened = connectTo(router, secrets)
        onNodeWithText("Skip").performClick()
        waitUntil(timeoutMillis = 5_000) { opened() != null }
        assertTrue(router.changes.isEmpty())
        assertEquals("secret", runBlocking { secrets.get(passwordKey("192.0.2.7", "admin")) })
    }

    @Test
    fun aPasswordTheRouterRefusesKeepsTheDialogWithTheTextOfTheRouter() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val router = Expired(refusal = "failure: password is too short")
        val opened = connectTo(router, MemorySecrets())
        onNodeWithTag("renewal-new").performTextInput("a")
        onNodeWithTag("renewal-repeat").performTextInput("a")
        onNodeWithText("Change").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("failure: password is too short")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(opened())
        onNodeWithText("Skip").performClick()
        waitUntil(timeoutMillis = 5_000) { opened() != null }
    }
}
