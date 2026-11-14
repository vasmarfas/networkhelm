package com.vasmarfas.networkhelm.nettools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OptionHexTest {

    @Test
    fun addressesBecomeTheBytesOfTheOption() {
        val addresses = assertNotNull(OptionHex.addresses("10.103.0.250, 192.168.88.1"))
        assertEquals("0x0A6700FAC0A85801", OptionHex.format(OptionHex.bytesOf(addresses)))
        assertEquals("0x01040A6700FA", OptionHex.format(assertNotNull(OptionHex.wrapped(1, OptionHex.bytesOf(listOf(addresses.first()))))))
        assertNull(OptionHex.addresses("10.103.0.256"))
        assertNull(OptionHex.addresses(" "))
    }

    @Test
    fun theValueOfTheRouterIsReadBackAsAddressesAndSubOptions() {
        val bytes = assertNotNull(OptionHex.bytes("0x01040A6700FA"))
        assertNull(OptionHex.addressesIn(bytes))
        val option = assertNotNull(OptionHex.subOptions(bytes)).single()
        assertEquals(1, option.code)
        assertEquals(listOf("10.103.0.250"), OptionHex.addressesIn(option.value))
        assertEquals(listOf("10.103.0.250", "10.0.0.1"), OptionHex.addressesIn(assertNotNull(OptionHex.bytes("0a:67:00:fa 0a 00 00 01"))))
    }

    @Test
    fun hexThatIsNoWholeBytesOrNoSubOptionsIsSaidSo() {
        assertNull(OptionHex.bytes("0x0A6"))
        assertNull(OptionHex.bytes("0xZZ"))
        assertNull(OptionHex.subOptions(listOf(1, 5, 10, 103)))
        assertEquals("tftp.lan", OptionHex.text(assertNotNull(OptionHex.bytes("746674702E6C616E"))))
        assertNull(OptionHex.text(listOf(10, 103)))
    }
}
