package com.vasmarfas.networkhelm.nettools

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.CannedStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanScanTest {

    private val addresses = (1..6).map { "192.0.2.$it" }

    private fun refused(): Nothing = throw DeviceException(ErrorCategory.ConnectionLost, "refused")

    @Test
    fun everyAddressIsAnsweredAndTheOnesThatRepliedAreNamed() = runTest {
        val found = scanLan(
            addresses,
            ping = { address -> if (address == "192.0.2.1") PingReply(2.0, 64, address) else PingReply(null, null, null) },
            reverse = { address -> if (address == "192.0.2.1") "gw.lan" else "nas.lan" },
            open = { host, port, _ -> if (host == "192.0.2.2" && port in listOf(22, 80)) CannedStream("") else refused() },
        ).toList()
        assertEquals(addresses.size, found.size)
        val hosts = found.filterNotNull().sortedBy { it.address }
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), hosts.map { it.address })
        assertEquals(LanHost("192.0.2.1", 2.0, emptyList(), "gw.lan"), hosts[0])
        assertEquals(listOf(22, 80), hosts[1].ports)
        assertEquals("nas.lan", hosts[1].name)
        assertTrue(assertNotNull(hosts[1].timeMillis) >= 0.0)
    }

    @Test
    fun withoutPingTheOpenPortsFindTheHostsAndThereAreNoNames() = runTest {
        val hosts = scanLan(
            addresses,
            ping = null,
            reverse = null,
            open = { host, port, _ -> if (host == "192.0.2.4" && port == 8291) CannedStream("") else refused() },
        ).toList().filterNotNull()
        assertEquals("192.0.2.4", hosts.single().address)
        assertEquals(listOf(8291), hosts.single().ports)
        assertNull(hosts.single().name)
    }

    @Test
    fun theHostsInFlightAreBounded() = runTest {
        var running = 0
        var most = 0
        val found = scanLan(
            addresses,
            ping = {
                running++
                most = maxOf(most, running)
                try {
                    delay(10)
                    PingReply(1.0, 64, it)
                } finally {
                    running--
                }
            },
            reverse = null,
            parallel = 2,
            open = { _, _, _ -> refused() },
        ).toList()
        assertEquals(addresses, found.filterNotNull().map { it.address }.sorted())
        assertTrue(most <= 2, "at most 2 at once, $most")
    }

    @Test
    fun everyAddressGetsEveryProbePort() = runTest {
        val asked = mutableListOf<Pair<String, Int>>()
        scanLan(
            listOf("192.0.2.1"),
            ping = null,
            reverse = null,
            open = { host, port, _ ->
                asked += host to port
                refused()
            },
        ).toList()
        assertEquals(LanPorts.map { "192.0.2.1" to it }, asked.sortedBy { LanPorts.indexOf(it.second) })
    }

    @Test
    fun aPlatformWithoutTcpFailsTheScan() = runTest {
        val failure = assertFailsWith<DeviceException> {
            scanLan(
                addresses,
                ping = null,
                reverse = null,
                open = { _, _, _ -> throw DeviceException(ErrorCategory.Unsupported, "a browser cannot open TCP connections") },
            ).toList()
        }
        assertEquals(ErrorCategory.Unsupported, failure.category)
    }

    @Test
    fun aPingThatCannotRunFailsTheScan() = runTest {
        assertFailsWith<IllegalStateException> {
            scanLan(addresses, ping = { throw IllegalStateException("no ping binary") }, reverse = null, open = { _, _, _ -> refused() }).toList()
        }
    }

    @Test
    fun aNetworkIsScannedWithoutItsOwnAddressAndTheBroadcast() {
        fun span(text: String) = scanSpan(text)?.let { (first, last) -> "${Ipv4.format(first)}-${Ipv4.format(last)}" }
        assertEquals("192.168.1.1-192.168.1.254", span("192.168.1.0/24"))
        assertEquals("192.168.1.1-192.168.1.254", span("192.168.1.77 255.255.255.0"))
        assertEquals("192.168.1.10-192.168.1.100", span("192.168.1.10-100"))
        assertEquals("10.1.2.3-10.1.2.3", span("10.1.2.3"))
        assertEquals("10.0.0.0-10.0.0.1", span("10.0.0.0/31"))
        assertEquals(4094L, scanSpan("10.0.16.0/20")?.let { (first, last) -> last - first + 1 })
        assertNull(span("nonsense"))
    }

    @Test
    fun theHomeBlockComesBeforeTheOnesOfBridgesAndTunnels() {
        val tunnel = LocalNetwork("tun0", "100.64.0.5", 10)
        val bridge = LocalNetwork("vEthernet", "172.22.112.1", 20)
        val office = LocalNetwork("eth1", "10.1.2.3", 16)
        val wifi = LocalNetwork("wlan0", "192.168.1.57", 24)
        assertEquals(wifi, defaultNetwork(listOf(tunnel, bridge, office, wifi)))
        assertEquals(office, defaultNetwork(listOf(tunnel, bridge, office)))
        assertEquals(bridge, defaultNetwork(listOf(tunnel, bridge)))
        assertEquals(tunnel, defaultNetwork(listOf(tunnel)))
        assertNull(defaultNetwork(emptyList()))
    }

    @Test
    fun onlyTheThreePrivateBlocksBeatAnAddressOutsideThem() {
        fun picks(address: String) = defaultNetwork(listOf(LocalNetwork("x", "169.254.0.1", 16), LocalNetwork("y", address, 24)))?.name == "y"
        assertTrue(picks("10.255.0.1"))
        assertTrue(picks("172.16.0.1"))
        assertTrue(picks("172.31.255.1"))
        assertTrue(picks("192.168.0.1"))
        assertFalse(picks("172.32.0.1"))
        assertFalse(picks("192.169.0.1"))
        assertFalse(picks("100.64.0.1"))
    }

    @Test
    fun aNetworkIsScannedAsTheA24AroundTheAddressOfTheDevice() {
        assertEquals("192.168.1.0/24", LocalNetwork("wlan0", "192.168.1.57", 24).scanRange())
        assertEquals("10.1.2.0/24", LocalNetwork("eth1", "10.1.2.3", 16).scanRange())
        assertEquals("192.168.43.0/30", LocalNetwork("tun1", "192.168.43.1", 32).scanRange())
    }

    @Test
    fun aMobileInterfaceIsNoNetworkToScan() {
        assertTrue(listOf("rmnet_data1", "rmnet0", "v4-rmnet_data0", "ccmni1", "pdp_ip0", "wwan0").all(::cellular))
        assertFalse(listOf("wlan0", "eth0", "en0", "swlan0", "bridge1").any(::cellular))
    }
}
