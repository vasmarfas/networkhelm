package com.vasmarfas.networkhelm.snmp

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.routeros.api.md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsmTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val engine = ByteArray(12).also { it[11] = 2 }

    @Test
    fun aPasswordLocalizesToTheKeysOfRfc3414() {
        assertEquals("526f5eed9fcce26f8964c2930787d82b", hex(localized(::md5, "maplesyrup", engine)))
        assertEquals("6695febc9288e36282235fc7151f128497b38f3f", hex(localized(::sha1, "maplesyrup", engine)))
    }

    @Test
    fun privacyTakesAuthentication() {
        assertFailsWith<IllegalArgumentException> { SnmpUser("rd-test", priv = PrivProtocol.Aes, privPassword = "rd-test-privacy") }
    }

    @Test
    fun anAnswerBelowTheSecurityOfTheUserIsRefusedAndAReportIsHeard() {
        val user = SnmpUser("rd-test", AuthProtocol.Sha1, "rd-test-auth", PrivProtocol.Aes, "rd-test-privacy")
        val clear = Usm(SnmpUser("rd-test"), Engine(engine, 3, 1000))
        val signed = Usm(SnmpUser("rd-test", AuthProtocol.Sha1, "rd-test-auth"), Engine(engine, 3, 1000))
        val names = listOf(oid("1.3.6.1.2.1.1.5.0"))
        assertFailsWith<DeviceException> { Usm(user, Engine(engine, 3, 1000)).open(clear.request(pdu(GET, 42, 0, 0, names), 42)) }
        assertFailsWith<DeviceException> { Usm(user, Engine(engine, 3, 1000)).open(signed.request(pdu(GET, 42, 0, 0, names), 42)) }
        assertEquals(REPORT, Usm(user, Engine(engine, 3, 1000)).open(clear.request(pdu(REPORT, 42, 0, 0, names), 42)).second.tag)
    }

    @Test
    fun aSealedRequestOpensAgain() {
        for (priv in listOf(PrivProtocol.Des, PrivProtocol.Aes)) {
            val user = SnmpUser("rd-test", AuthProtocol.Sha1, "rd-test-auth", priv, "rd-test-privacy")
            val usm = Usm(user, Engine(engine, 3, 1000))
            val get = pdu(GET, 42, 0, 0, listOf(oid("1.3.6.1.2.1.1.5.0")))
            val sealed = usm.request(get, 42)
            assertEquals(false, hex(sealed).contains(hex(get)), "$priv hides the PDU")
            val (msgId, opened) = Usm(user, Engine(engine, 3, 1000)).open(sealed)
            assertEquals(42, msgId)
            assertEquals(hex(get), hex(opened.whole()))
        }
    }
}
