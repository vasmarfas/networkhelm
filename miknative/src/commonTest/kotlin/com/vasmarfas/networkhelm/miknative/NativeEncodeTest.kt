package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.NATIVE_WRITABLE_KINDS
import com.vasmarfas.networkhelm.packs.PackNativeField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeEncodeTest {

    private fun field(kind: String, letter: String, names: Map<Long, String> = emptyMap(), scale: Int? = null, postfix: String? = null, flag: Int? = null, negation: Int? = null) =
        PackNativeField("x", 0x10, kind, names, scale, postfix, letter = letter, flag = flag, negation = negation, writable = true)

    private fun value(field: PackNativeField, text: String, resolved: Map<Long, String>? = null) = encoded(field, text, resolved).single { it.first == field.key }.second

    // what the router would show for the value that goes out
    private fun shown(field: PackNativeField, text: String, resolved: Map<Long, String>? = null) = written(field, value(field, text, resolved), resolved)

    @Test
    fun aValueTypedIsWrittenInTheTypeOfTheIdOfTheFieldAndReadsBackTheSame() {
        val cases = listOf(
            Triple(field("string", "s"), "rd-test", "rd-test"),
            Triple(field("bool", "b"), "yes", "true"),
            Triple(field("bool", "u"), "off", "false"),
            Triple(field("number", "u", postfix = " MHz"), "3417 MHz", "3417 MHz"),
            Triple(field("number", "u"), "0x10", "16"),
            Triple(field("integer", "u"), "-5", "-5"),
            Triple(field("bignumber", "q"), "5368709120", "5368709120"),
            Triple(field("enm", "u", names = mapOf(0L to "Eth1", 1L to "SFP1")), "sfp1", "SFP1"),
            Triple(field("enm", "u", names = mapOf(16777215L to "255.255.255.0 (/24)")), "255.255.255.0 (/24)", "255.255.255.0 (/24)"),
            Triple(field("set", "u", names = mapOf(0L to "read", 1L to "write", 5L to "test")), "read, test", "read,test"),
            Triple(field("interval", "u"), "1d 02:03:04", "1d2h3m4s"),
            Triple(field("interval", "u"), "1w2d3h4m5s", "1w2d3h4m5s"),
            Triple(field("interval", "u", scale = 1000), "30s", "30s"),
            Triple(field("interval", "u", names = mapOf(0xFFFFFFFFL to "none")), "none", "none"),
            Triple(field("fixedpoint", "u", scale = 1000, postfix = "s"), "1.5s", "1.5s"),
            Triple(field("clocktime", "u"), "12:30", "12:30"),
            Triple(field("clocktime", "u"), "01:01:01", "01:01:01"),
            Triple(field("clockdate", "u"), "2026-07-25", "2026-07-25"),
            Triple(field("dateandtime", "u"), "2026-11-03 22:36:19", "2026-11-03 22:36:19"),
            Triple(field("timezone", "u"), "-03:30", "-03:30"),
            Triple(field("ipaddr", "u"), "192.168.88.1", "192.168.88.1"),
            Triple(field("netmask", "u"), "255.255.255.0", "255.255.255.0"),
            Triple(field("macaddr", "r"), "02:5a:3c:91:0f:e7", "02:5A:3C:91:0F:E7"),
            Triple(field("ip6addr", "a"), "fe80::1", "fe80::1"),
            Triple(field("fixedpoint", "u", scale = 100), "12.5", "12.5"),
            Triple(field("kbytes", "u"), "536870912", "536870912"),
            Triple(field("multistring", "S"), "a, b", "a,b"),
            Triple(field("multinumber", "U"), "1, 20", "1,20"),
            Triple(field("multiipaddr", "U"), "10.0.0.1,10.0.0.2", "10.0.0.1,10.0.0.2"),
            Triple(field("multiip6addr", "A"), "fe80::1,2001:db8::2", "fe80::1,2001:db8::2"),
        )
        for ((field, typed, expected) in cases) assertEquals(expected, shown(field, typed), "${field.kind} $typed")
    }

    @Test
    fun theWireTypeFollowsTheLetterOfTheId() {
        assertEquals(M2Value.U32(7), value(field("number", "u"), "7"))
        assertEquals(M2Value.U64(7), value(field("number", "q"), "7"))
        assertEquals(M2Value.Bool(true), value(field("bool", "b"), "true"))
        assertEquals(M2Value.U32(1), value(field("bool", "u"), "true"))
        assertEquals(M2Value.U32(0xFFFFFFFB), value(field("integer", "u"), "-5"))
        assertEquals(M2Value.U32(1_000), value(field("interval", "u", scale = 1000), "1s"))
        assertEquals(M2Value.U32(0x0F02000A), value(field("ipaddr", "u"), "10.0.2.15"))
    }

    @Test
    fun anOptionalFieldCarriesItsFlagAndAnEmptyValueLowersTheFlagAlone() {
        val optional = field("string", "s", flag = 0x20)
        assertEquals(listOf(0x20 to M2Value.Bool(true), 0x10 to M2Value.Text("a")), encoded(optional, "a", null))
        assertEquals(listOf(0x20 to M2Value.Bool(false)), encoded(optional, "", null))
        assertEquals(listOf(0x20 to M2Value.Bool(false)), encoded(optional, null, null))
    }

    @Test
    fun aNegatedFieldCarriesItsFlagAndTheBangComesOffTheValue() {
        val negated = field("string", "s", negation = 0x21)
        assertEquals(listOf(0x21 to M2Value.Bool(true), 0x10 to M2Value.Text("a")), encoded(negated, "!a", null))
        assertEquals(listOf(0x21 to M2Value.Bool(false), 0x10 to M2Value.Text("a")), encoded(negated, "a", null))
    }

    @Test
    fun aNameOfTheTableTheFieldRefersToStandsForItsNumber() {
        assertEquals(M2Value.U32(3), value(field("enm", "u"), "ether2", mapOf(2L to "ether1", 3L to "ether2")))
        assertEquals(M2Value.U32(9), value(field("enm", "u"), "9", null))
    }

    @Test
    fun aUnionTakesTheMemberThatTakesTheText() {
        val startup = PackNativeField("x", 0x12, "enm", mapOf(0xFFFFFFFFL to "startup"), letter = "u", writable = true)
        val clock = PackNativeField("x", 0x12, "clocktime", letter = "u", writable = true)
        val union = PackNativeField("x", 0x12, "union", members = listOf(startup, clock), writable = true)
        assertEquals(listOf(0x12 to M2Value.U32(0xFFFFFFFF)), encoded(union, "startup", null))
        assertEquals(listOf(0x12 to M2Value.U32(3_661)), encoded(union, "01:01:01", null))
        assertFailsWith<DeviceException> { encoded(union, "never", null) }
    }

    @Test
    fun aValueThatDoesNotFitItsKindIsRefusedBeforeItLeaves() {
        val refused = listOf(
            field("number", "u") to "many",
            field("number", "u") to "-1",
            field("number", "u") to "4294967296",
            field("bool", "b") to "maybe",
            field("ipaddr", "u") to "10.0.0",
            field("ipaddr", "u") to "10.0.0.256",
            field("macaddr", "r") to "02:5a:3c",
            field("ip6addr", "a") to "fe80::1::2",
            field("interval", "u") to "soon",
            field("clocktime", "u") to "25:00",
            field("clockdate", "u") to "2026-13-01",
            field("timezone", "u") to "3",
            field("set", "u") to "swim",
            field("fixedpoint", "u", scale = 100) to "1.234",
        )
        for ((field, text) in refused) {
            val failure = assertFailsWith<DeviceException>("${field.kind} $text") { encoded(field, text, null) }
            assertEquals(ErrorCategory.ArgumentValue, failure.category)
        }
    }

    private fun address(text: String, interfaces: Map<Long, String>? = null) = ((value(field("addr", "m"), text, interfaces)) as M2Value.Message).value.fields.map { it.key to it.value }

    @Test
    fun anAddressIsAMessageWithAKeyForTheShapeOfTheHostAndTheMarksOfTheConsoleBehindIt() {
        assertEquals(listOf(ADDRESS_V4 to M2Value.U32(0x0202000A)), address("10.0.2.2"))
        assertEquals(listOf(ADDRESS_V4 to M2Value.U32(0x0100007F)), address("127.0.0.1"))
        assertEquals(listOf(ADDRESS_NAME to M2Value.Text("example.com")), address("example.com"))
        assertEquals(listOf(ADDRESS_V6), address("2001:db8::1").map { it.first })
        assertEquals(listOf(ADDRESS_MAC), address("02:5a:3c:91:0f:e7").map { it.first })
        assertEquals(listOf(ADDRESS_V6, ADDRESS_INTERFACE), address("fe80::1%ether1", mapOf(2L to "ether1")).map { it.first })
        assertEquals(M2Value.U32(2), address("fe80::1%ether1", mapOf(2L to "ether1")).last().second)
        assertEquals(listOf(ADDRESS_V4, ADDRESS_LENGTH, ADDRESS_VRF), address("10.0.0.0/8@5").map { it.first })
        // an interface alone, as the gateway of a route
        assertEquals(listOf(ADDRESS_INTERFACE to M2Value.U32(2)), address("ether1", mapOf(2L to "ether1")))
        assertEquals(listOf(ADDRESS_INTERFACE to M2Value.U32(2)), address("%ether1", mapOf(2L to "ether1")))
        assertEquals(listOf(ADDRESS_NAME to M2Value.Text("example.com")), address("example.com", mapOf(2L to "ether1")))
    }

    @Test
    fun anAddressReadsBackAsItWasTyped() {
        val field = field("addr", "m")
        for (text in listOf("10.0.2.2", "example.com", "2001:db8::1", "02:5A:3C:91:0F:E7", "10.0.0.0/8")) {
            assertEquals(text, written(field, value(field, text), null), text)
        }
    }

    @Test
    fun aHostThatIsNoAddressAtAllIsTakenForAName() {
        assertEquals(listOf(ADDRESS_NAME), address("router").map { it.first })
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { address("") }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { address("10.0.0.1@x") }.category)
    }

    @Test
    fun aNumberWithAPrefixOfTheUnitStepsByAThousand() {
        val rate = PackNativeField("x", 0x10, "bigunit", postfix = "bps", letter = "q", writable = true)
        assertEquals(M2Value.U64(10_000_000), value(rate, "10M"))
        assertEquals(M2Value.U64(1_500_000_000), value(rate, "1.5G"))
        assertEquals(M2Value.U64(500_000), value(rate, "500k"))
        assertEquals(M2Value.U64(2_000_000), value(rate, "2Mbps"))
        assertEquals(M2Value.U64(1234), value(rate, "1234"))
        assertEquals("10.0 Mbps", written(rate, value(rate, "10M"), null))
        for (text in listOf("1.5", "x", "-1M", "1.2.3M")) assertFailsWith<DeviceException>(text) { value(rate, text) }
    }

    @Test
    fun aRangeOfNumbersGoesOutAsItsTwoEnds() {
        val range = PackNativeField("x", 0x29, "numberrange", letter = "u", pair = 0x2A, writable = true)
        assertEquals(listOf(0x29 to M2Value.U32(1000), 0x2A to M2Value.U32(1500)), encoded(range, "1000..1500", null))
        assertEquals(listOf(0x29 to M2Value.U32(1500), 0x2A to M2Value.U32(1500)), encoded(range, "1500", null))
        assertEquals("1000..1500", written(range, M2Value.U32(1000), null, M2Value.U32(1500)))
    }

    @Test
    fun aNetworkGoesOutAsItsAddressAndItsMaskInTheByteOrderOfTheAddress() {
        val network = PackNativeField("x", 2, "network", letter = "u", pair = 3, writable = true)
        assertEquals(listOf(2 to M2Value.U32(0x0000000A), 3 to M2Value.U32(0x000000FF)), encoded(network, "10.0.0.0/8", null))
        assertEquals(listOf(2 to M2Value.U32(0x0000A8C0), 3 to M2Value.U32(0x00FFFFFF)), encoded(network, "192.168.0.0/24", null))
        assertEquals(listOf(2 to M2Value.U32(0x0201A8C0), 3 to M2Value.U32(0xFFFFFFFF)), encoded(network, "192.168.1.2", null))
        assertEquals("192.168.0.0/24", written(network, M2Value.U32(0x0000A8C0), null, M2Value.U32(0x00FFFFFF)))
        val range = PackNativeField("x", 2, "network", letter = "u", pair = 3, range = true, writable = true)
        assertEquals(listOf(2 to M2Value.U32(0x0000000A), 3 to M2Value.U32(0xFFFFFF0A)), encoded(range, "10.0.0.0/8", null))
        assertEquals("10.0.0.0/8", written(range, M2Value.U32(0x0000000A), null, M2Value.U32(0xFFFFFF0A)))
        assertEquals(listOf(2 to M2Value.U32(0x0100000A), 3 to M2Value.U32(0x0900000A)), encoded(range, "10.0.0.1-10.0.0.9", null))
    }

    @Test
    fun aListOfFiltersGoesOutAsAnArrayOfMessagesWithTheNegationBesideTheValue() {
        val part = PackNativeField("x", 2, "network", letter = "u", pair = 3, negation = 1, writable = true)
        val list = PackNativeField("x", 0xCC, "multi", letter = "M", parts = listOf(part), writable = true)
        val sent = encoded(list, "10.0.0.0/8, !192.168.0.0/16", null).single()
        assertEquals(0xCC, sent.first)
        val elements = (sent.second as M2Value.Messages).value.map { message -> message.fields.map { it.key to it.value } }
        assertEquals(
            listOf(
                listOf(1 to M2Value.Bool(false), 2 to M2Value.U32(0x0A), 3 to M2Value.U32(0xFF)),
                listOf(1 to M2Value.Bool(true), 2 to M2Value.U32(0xA8C0), 3 to M2Value.U32(0xFFFF)),
            ),
            elements,
        )
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { encoded(list, "nothing", null) }.category)
    }

    @Test
    fun aMacAddressWithAMaskGoesOutAsTwoRawValuesAndWithoutOneAsTheWholeAddress() {
        val network = PackNativeField("x", 2, "macnetwork", letter = "r", pair = 3, writable = true)
        val bytes = encoded(network, "02:00:00:00:00:01/FF:FF:FF:00:00:00", null).map { (it.second as M2Value.Raw).value.map { b -> b.toInt() and 0xFF } }
        assertEquals(listOf(listOf(2, 0, 0, 0, 0, 1), listOf(255, 255, 255, 0, 0, 0)), bytes)
        assertEquals(List(6) { 255 }, (encoded(network, "02:00:00:00:00:01", null)[1].second as M2Value.Raw).value.map { it.toInt() and 0xFF })
    }

    @Test
    fun aNetworkOfIpv6GoesOutAsItsAddressAndTheLengthOfItsPrefix() {
        val network = PackNativeField("x", 0x12, "network6", letter = "a", pair = 0x13, writable = true)
        val sent = encoded(network, "2001:db8::/32", null)
        assertEquals(listOf(0x12, 0x13), sent.map { it.first })
        assertEquals(M2Value.U32(32), sent[1].second)
        assertEquals(M2Value.U32(128), encoded(network, "::1", null)[1].second)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { encoded(network, "::1/129", null) }.category)
    }

    @Test
    fun aFieldThatCannotBeWrittenIsRefusedAndEveryKindOfTheListIsKnownToTheWriter() {
        val readOnly = PackNativeField("x", 0x10, "string", letter = "s")
        assertEquals("x is read-only", assertFailsWith<DeviceException> { encoded(readOnly, "a", null) }.message)
        val letters = mapOf("macaddr" to "r", "ip6addr" to "a", "multistring" to "S", "multinumber" to "U", "multiipaddr" to "U", "multiip6addr" to "A", "bignumber" to "q", "bigbytes" to "q", "bigbitrate" to "q", "bigdecimal" to "q")
        val samples = mapOf("macaddr" to "02:00:00:00:00:01", "ip6addr" to "::1", "multiip6addr" to "::1", "multiipaddr" to "1.2.3.4", "clocktime" to "10:00", "clockdate" to "2026-01-01", "dateandtime" to "2026-01-01 10:00:00", "timezone" to "+01:00", "interval" to "1s", "ipaddr" to "1.2.3.4", "netmask" to "255.0.0.0", "bool" to "yes", "flag" to "yes", "set" to "1")
        for (kind in NATIVE_WRITABLE_KINDS) {
            val names = mapOf(1L to "1")
            val sample = samples[kind] ?: if (kind == "string" || kind == "script" || kind == "multistring") "a" else "1"
            assertTrue(encoded(field(kind, letters[kind] ?: "u", names = names), sample, null).isNotEmpty(), kind)
        }
    }
}
