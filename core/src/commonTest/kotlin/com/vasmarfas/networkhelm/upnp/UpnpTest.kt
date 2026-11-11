package com.vasmarfas.networkhelm.upnp

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.UdpLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpnpTest {

    private class Device(private val answer: (String) -> Pair<Int, String>) {
        val requests = mutableListOf<String>()

        fun open(): ByteStream = object : ByteStream {
            private val written = StringBuilder()
            private var reply: ByteArray? = null
            private var at = 0

            override suspend fun write(bytes: ByteArray) {
                written.append(bytes.decodeToString())
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val bytes = reply ?: run {
                    requests += written.toString()
                    val (status, body) = answer(written.toString())
                    val head = if (status == 401) "WWW-Authenticate: Digest realm=\"F!Box SOAP-Auth\", nonce=\"rd-test-nonce\", algorithm=MD5, qop=\"auth\"\r\n" else ""
                    "HTTP/1.1 $status X\r\n${head}Content-Length: ${body.encodeToByteArray().size}\r\n\r\n$body".encodeToByteArray().also { reply = it }
                }
                if (at == bytes.size) return -1
                val count = minOf(length, bytes.size - at)
                bytes.copyInto(buffer, offset, at, at + count)
                at += count
                return count
            }

            override fun close() {}
        }
    }

    private fun soap(action: String, arguments: Map<String, String>) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"x\">" +
            arguments.entries.joinToString("") { (name, value) -> "<$name>$value</$name>" } + "</u:${action}Response></s:Body></s:Envelope>"

    private val fault = 500 to "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>713</errorCode><errorDescription>SpecifiedArrayIndexInvalid</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    private val gateway = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>""" +
        """<friendlyName>OpenWrt router</friendlyName><manufacturer>OpenWrt</manufacturer><modelDescription>OpenWrt with MiniUPnPd version 2.3.9 router</modelDescription><modelName>OpenWrt router</modelName>""" +
        """<deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType><friendlyName>WANDevice</friendlyName><serviceList><service>""" +
        """<serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType><controlURL>/ctl/CmnIfCfg</controlURL></service></serviceList>""" +
        """<deviceList><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>""" +
        """<controlURL>http://192.168.99.1:5000/ctl/IPConn</controlURL></service></serviceList></device></deviceList></device></deviceList></device></root>"""

    private fun action(request: String) = Regex("SOAPAction: \"[^#]*#(\\w+)\"").find(request)?.groupValues?.get(1)

    private fun router(bytes: () -> Long = { 1_000 }) = Device { request ->
        when {
            request.startsWith("GET /rootDesc.xml") -> 200 to gateway
            request.startsWith("GET ") -> 404 to ""
            else -> when (action(request)) {
                "GetCommonLinkProperties" -> 200 to soap("GetCommonLinkProperties", mapOf("NewWANAccessType" to "Ethernet", "NewLayer1UpstreamMaxBitRate" to "1000000000", "NewLayer1DownstreamMaxBitRate" to "1000000000", "NewPhysicalLinkStatus" to "Up"))
                "GetTotalBytesReceived" -> 200 to soap("GetTotalBytesReceived", mapOf("NewTotalBytesReceived" to "${bytes()}"))
                "GetTotalBytesSent" -> 200 to soap("GetTotalBytesSent", mapOf("NewTotalBytesSent" to "2000"))
                "GetTotalPacketsReceived" -> 200 to soap("GetTotalPacketsReceived", mapOf("NewTotalPacketsReceived" to "10"))
                "GetTotalPacketsSent" -> 200 to soap("GetTotalPacketsSent", mapOf("NewTotalPacketsSent" to "20"))
                "GetExternalIPAddress" -> 200 to soap("GetExternalIPAddress", mapOf("NewExternalIPAddress" to "192.0.2.82"))
                "GetStatusInfo" -> 200 to soap("GetStatusInfo", mapOf("NewConnectionStatus" to "Connected", "NewUptime" to "3725"))
                "GetGenericPortMappingEntry" -> if ("<NewPortMappingIndex>0</NewPortMappingIndex>" in request) {
                    200 to soap("GetGenericPortMappingEntry", mapOf("NewRemoteHost" to "", "NewExternalPort" to "50999", "NewProtocol" to "TCP", "NewInternalPort" to "22",
                        "NewInternalClient" to "192.168.99.10", "NewEnabled" to "1", "NewPortMappingDescription" to "rd-test &amp; co", "NewLeaseDuration" to "0"))
                } else {
                    fault
                }
                "DeletePortMapping" -> 200 to soap("DeletePortMapping", emptyMap())
                else -> 500 to "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            }
        }
    }

    private suspend fun connect(device: Device) = connectUpnp(UpnpLogin("192.0.2.1", 5000)) { _, _ -> device.open() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aDeviceThatGoesSilentEndsTheRequestInsteadOfHoldingItForever() = runTest {
        val closed = CompletableDeferred<Unit>()
        val silent = object : ByteStream {
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                closed.await()
                return -1
            }

            override suspend fun write(bytes: ByteArray) = Unit

            override fun close() {
                closed.complete(Unit)
            }
        }
        assertFailsWith<DeviceException> { UpnpClient("192.0.2.1", 5000, { _, _ -> silent }).description("/rootDesc.xml") }
        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun theDescriptionNamesTheDeviceAndTheControlsOfItsServices() {
        val description = descriptionOf(gateway)
        assertEquals(listOf("OpenWrt router", "OpenWrt", "OpenWrt router"), listOf(description.name, description.manufacturer, description.model))
        assertEquals(
            mapOf("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1" to "/ctl/CmnIfCfg", "urn:schemas-upnp-org:service:WANIPConnection:1" to "/ctl/IPConn"),
            description.controls,
        )
    }

    @Test
    fun theWanThePortMappingsAndTheResourcesComeInTheNamesOfRouterOs() = runTest {
        val device = router()
        val session = connect(device)
        assertEquals("OpenWrt router", session.device.identity)
        val wan = session.get(MenuPath.parse("interface"), "wan")
        assertEquals(listOf("ethernet", "up", "true", "1Gbps", "1000", "2000"), listOf("type", "status", "running", "max-download", "rx-byte", "tx-byte").map { wan?.get(it) })
        val mappings = (session.watch(MenuPath.parse("ip/firewall/nat")).first() as ListEvent.Snapshot).items
        assertEquals(1, mappings.size)
        assertEquals(
            listOf("dstnat", "dst-nat", "tcp", "50999", "192.168.99.10", "22", "rd-test & co", "false", "true"),
            listOf("chain", "action", "protocol", "dst-port", "to-addresses", "to-ports", "comment", "disabled", "dynamic").map { mappings.single()[it] },
        )
        val resource = session.get(MenuPath.parse("system/resource"), null)
        assertEquals(listOf("192.0.2.82", "Connected", "1h2m5s"), listOf("external-address", "connection-status", "connection-uptime").map { resource?.get(it) })
        val asked = device.requests.first { "GetGenericPortMappingEntry" in it }
        assertTrue("POST /ctl/IPConn HTTP/1.1" in asked && "Host: 192.0.2.1:5000" in asked, asked)
        assertTrue("SOAPAction: \"urn:schemas-upnp-org:service:WANIPConnection:1#GetGenericPortMappingEntry\"" in asked, asked)
        assertFailsWith<DeviceException> { session.describe(MenuPath.parse("ip/dhcp-server/lease")) }
    }

    @Test
    fun aPortMappingIsRemovedByItsProtocolAndPort() = runTest {
        val device = router()
        val session = connect(device)
        val nat = MenuPath.parse("ip/firewall/nat")
        assertEquals(listOf("print", "remove"), session.describe(nat).commands)
        val mapping = (session.watch(nat).first() as ListEvent.Snapshot).items.single()
        assertEquals("TCP/50999", mapping.id)
        session.remove(nat, listOf(mapping.id))
        val asked = device.requests.single { "#DeletePortMapping" in it }
        assertTrue("<NewRemoteHost></NewRemoteHost><NewExternalPort>50999</NewExternalPort><NewProtocol>TCP</NewProtocol>" in asked, asked)
        assertFailsWith<DeviceException> { session.remove(nat, listOf("50999")) }
        assertFailsWith<DeviceException> { session.add(nat, mapOf("dst-port" to "50999")) }
    }

    @Test
    fun tr064TakesTheUserAndPasswordByDigest() = runTest {
        val device = Device { request ->
            if ("Authorization: Digest" !in request) {
                401 to ""
            } else {
                200 to soap("GetInfo", mapOf("NewSoftwareVersion" to "154.07.29"))
            }
        }
        val client = UpnpClient("192.0.2.1", 49000, { _, _ -> device.open() }, "rd-test", "secret")
        assertEquals("154.07.29", client.call("urn:dslforum-org:service:DeviceInfo:1", "/upnp/control/deviceinfo", "GetInfo")["NewSoftwareVersion"])
        val authorization = device.requests.last().lineSequence().first { it.startsWith("Authorization: ") }
        val fields = Regex("""(\w+)="?([^",]*)"?""").findAll(authorization.substringAfter("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }
        fun hex(text: String) = md5(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val expected = hex("${hex("rd-test:F!Box SOAP-Auth:secret")}:rd-test-nonce:00000001:${fields["cnonce"]}:auth:${hex("POST:/upnp/control/deviceinfo")}")
        assertEquals(listOf("rd-test", "F!Box SOAP-Auth", "rd-test-nonce", "/upnp/control/deviceinfo", expected), listOf("username", "realm", "nonce", "uri", "response").map { fields[it] })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theRatesOfTheWanComeFromItsCounters() = runTest {
        var received = 0xFFFF_FF00L
        val device = router { received }
        val client = UpnpClient("192.0.2.1", 5000, { _, _ -> device.open() })
        val session = UpnpSession(client, descriptionOf(gateway), DeviceInfo(Platform.Upnp, "OpenWrt router", "", "", "", emptySet(), setOf("read")), time = testScheduler.timeSource)
        val rounds = mutableListOf<Item>()
        backgroundScope.launch { session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "wan")).collect { rounds += it } }
        advanceTimeBy(500)
        received = 0x0000_0100L + 125_000 - 0x200
        advanceTimeBy(1_000)
        assertEquals(listOf("wan" to "1000000"), rounds.map { it["name"] to it["rx-bits-per-second"] })
    }

    @Test
    fun ssdpGivesTheLocationOfTheDescription() = runTest {
        val sent = mutableListOf<String>()
        val link = object : UdpLink {
            override suspend fun send(datagram: ByteArray) {
                sent += datagram.decodeToString()
            }

            override suspend fun receive(timeoutMillis: Int): ByteArray? =
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nLOCATION: http://192.168.99.1:5000/rootDesc.xml\r\n\r\n".encodeToByteArray()

            override fun close() {}
        }
        assertEquals("http://192.168.99.1:5000/rootDesc.xml", search(link, "192.168.99.1"))
        assertTrue(sent.single().startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 192.168.99.1:1900\r\n"))
    }
}
