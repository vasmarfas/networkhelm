package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognizeTest {

    @Test
    fun theApiPortsOrTheBannerTellRouterOs() {
        assertEquals(Platform.RouterOs, recognized(setOf(8728, 22), null, emptyList()))
        assertEquals(Platform.RouterOs, recognized(setOf(22), "SSH-2.0-ROSSSH", emptyList()))
    }

    @Test
    fun aPageThatNamesItsSystemTellsIt() {
        assertEquals(Platform.Opnsense, recognized(setOf(443, 22), null, listOf("server: OPNsense\n<title>Login | OPNsense</title>")))
        assertEquals(Platform.Pfsense, recognized(setOf(443, 22), null, listOf("server: nginx\n<title>pfSense - Login</title>")))
        val vyos = """{"success": true, "data": {"banner": "Welcome to VyOS", "hostname": "vyos"}, "error": null}"""
        assertEquals(Platform.Vyos, recognized(setOf(443, 22), null, listOf("server: nginx\n<title>403 Forbidden</title>", vyos)))
        assertEquals(Platform.OpenWrt, recognized(setOf(80), null, listOf("""<meta http-equiv="refresh" content="0; URL=cgi-bin/luci/" />""")))
    }

    @Test
    fun aBannerOfDropbearIsTheLastClue() {
        assertEquals(Platform.OpenWrt, recognized(setOf(22, 80, 443), "SSH-2.0-dropbear", emptyList()))
        assertEquals(Platform.Keenetic, recognized(setOf(22, 80), "SSH-2.0-dropbear", listOf("<title>Keenetic Giga</title>")))
        assertNull(recognized(setOf(22, 443), "SSH-2.0-OpenSSH_9.6", listOf("server: nginx\n<title>403 Forbidden</title>")))
    }

    @Test
    fun aNameOfKeenDnsOrCrazeDnsIsAKeeneticBehindTheCloud() {
        assertTrue(keeneticCloud("home.keenetic.link"))
        assertTrue(keeneticCloud("Router.NetCraze.Link."))
        assertTrue(keeneticCloud("lab.crazedns.ru"))
        assertTrue(keeneticCloud("abc.netcraze.io"))
        assertFalse(keeneticCloud("netcraze.link"))
        assertFalse(keeneticCloud("my.keenetic.net"))
        assertFalse(keeneticCloud("keenetic.link.example.org"))
        assertEquals(Platform.Keenetic, recognized(setOf(443), null, listOf("<title>Netcraze Hopper</title>")))
    }
}
