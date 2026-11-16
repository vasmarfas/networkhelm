package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNetworkConfigTest {

    // the login is root; a command gets the first answer it starts with, a read of a file what the writes left there
    private class Host(private val answers: List<Pair<String, String>>, val files: MutableMap<String, String> = mutableMapOf()) : SshSession {
        val ran = mutableListOf<String>()

        override suspend fun exec(command: String): ExecResult {
            ran += command
            when {
                command == "id -u" -> return ExecResult("0\n", "", 0)
                command.startsWith("[ ! -e '") -> return ExecResult(files[command.substringAfter("'").substringBefore("'")].orEmpty(), "", 0)
                command.startsWith("mkdir -p ") -> {
                    val path = command.substringAfter("networkhelm-new' '", "").substringBefore("'")
                    if (path.isEmpty()) files.remove(command.substringBefore(" || {").substringAfterLast("rm -f '").substringBefore("'")) else files[path] = written(command)
                    return ExecResult("", "", 0)
                }
            }
            return answers.firstOrNull { command.startsWith(it.first) }?.let { ExecResult(it.second, "", 0) } ?: ExecResult("", "not found", 127)
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit

        // the text printf got, as quote made it
        private fun written(command: String) =
            command.substringAfter("printf '%s' ").substringBefore(" > '").removePrefix("'").removeSuffix("'").replace("'\\''", "'")
    }

    private fun config(host: Host, vararg tools: String) = LinuxNetworkConfig(host, Root(host, null)) { tools.toSet() }

    private val debian = listOf(
        "# The loopback network interface",
        "auto lo",
        "iface lo inet loopback",
        "",
        "# The primary network interface",
        "allow-hotplug eth0",
        "iface eth0 inet static",
        "\taddress 192.0.2.50/24",
        "\tgateway 192.0.2.1",
        "\t# dns-* options are implemented by the resolvconf package",
        "\tdns-nameservers 192.0.2.1",
        "",
        "source /etc/network/interfaces.d/*",
    ).joinToString("\n", postfix = "\n")

    @Test
    fun aStanzaTakesAndGivesUpItsOptionsAndTheOtherLinesStay() {
        val options = listOf("post-up ip -4 addr add 198.51.100.5/24 dev eth0", "pre-down ip -4 addr del 198.51.100.5/24 dev eth0")
        val added = interfacesWith(debian, "eth0", options)
        assertEquals(debian.replace("\tdns-nameservers 192.0.2.1\n", "\tdns-nameservers 192.0.2.1\n\t${options[0]}\n\t${options[1]}\n"), added)
        assertEquals(added, interfacesWith(added, "eth0", options))
        assertEquals(debian, interfacesEdit(added, "eth0") { option -> option.takeUnless { "198.51.100.5" in it } })
        assertNull(interfacesEdit(debian, "eth0") { option -> option.takeUnless { "198.51.100.5" in it } })

        val manual = interfacesAuto(debian, "eth0", false)
        assertEquals(debian.replace("allow-hotplug eth0\n", ""), manual)
        assertEquals(debian.replace("allow-hotplug eth0\n", "auto eth0\n"), interfacesAuto(manual, "eth0", true))
        assertEquals(debian, interfacesAuto(debian, "eth0", true))

        val vlan = interfacesWith(debian, "vlan10", listOf("pre-up ip link add link eth0 name vlan10 type vlan id 10", "post-down ip link del vlan10"))
        assertEquals(debian + "\nauto vlan10\niface vlan10 inet manual\n    pre-up ip link add link eth0 name vlan10 type vlan id 10\n    post-down ip link del vlan10\n", vlan)
        assertEquals(debian, interfacesWithout(vlan, "vlan10"))
        assertNull(interfacesWithout(debian, "vlan10"))

        assertEquals("/etc/network/interfaces.d/networkhelm", interfacesFile(debian))
        assertEquals("/etc/network/interfaces.d/networkhelm.cfg", interfacesFile("source interfaces.d/*.cfg\n"))
        assertEquals("/etc/network/interfaces", interfacesFile("auto lo\niface lo inet loopback\n"))
    }

    @Test
    fun ifupdownRunsTheSameIpCommandsWhenTheInterfaceComesUp() = runTest {
        val start = "auto lo\niface lo inet loopback\n\nauto eth0\niface eth0 inet dhcp\n"
        val host = Host(
            listOf(
                "systemctl is-active systemd-networkd" to "active\nn/a\n",
                "PATH=\$PATH:/usr/sbin:/sbin ifup --version" to "ifup version 0.8.41\n--nh-file-- /etc/network/interfaces\n$start\n",
            ),
            mutableMapOf("/etc/network/interfaces" to start),
        )
        val config = config(host, "networkctl", "ifup", "systemctl")
        assertEquals("ifupdown", config.manager("eth0"))
        config.addAddress("eth0", "192.0.2.5/24")
        config.addRoute("198.51.100.0/24", "192.0.2.1", "eth0", "", "")
        config.setMtu("eth0", 1400)
        config.setMtu("eth0", 1450)
        assertEquals(
            start + "    post-up ip -4 addr add 192.0.2.5/24 dev eth0\n    pre-down ip -4 addr del 192.0.2.5/24 dev eth0\n" +
                "    post-up ip -4 route add 198.51.100.0/24 via 192.0.2.1 dev eth0\n    pre-down ip -4 route del 198.51.100.0/24 via 192.0.2.1 dev eth0\n" +
                "    post-up ip link set dev eth0 mtu 1450\n",
            host.files["/etc/network/interfaces"],
        )
        assertTrue(host.ran.filter { it.startsWith("mkdir -p") }.all { "ifquery --list" in it }, "${host.ran}")

        config.removeRoute("198.51.100.0/24", "192.0.2.1", "eth0", "0", "main")
        config.removeAddress("eth0", "192.0.2.5/24")
        config.setEnabled("eth0", false)
        assertEquals("auto lo\niface lo inet loopback\n\niface eth0 inet dhcp\n    post-up ip link set dev eth0 mtu 1450\n", host.files["/etc/network/interfaces"])
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { config.removeAddress("eth0", "192.0.2.5/24") }.category)
    }

    @Test
    fun systemdNetworkdTakesADropInBesideTheFileOfTheInterface() = runTest {
        val pve = "[Match]\nName = eth0\n\n[Network]\nDescription = Interface eth0 autoconfigured by PVE\nDHCP = ipv4\nIPv6AcceptRA = false\n"
        val networkd = "systemctl is-active systemd-networkd; f=\$(networkctl status"
        val host = Host(
            listOf(
                "for f in /lib/netplan/*.yaml" to "",
                "$networkd 'eth0'" to "active\n/etc/systemd/network/eth0.network\n--nh-file-- /etc/systemd/network/eth0.network\n$pve\n",
                "$networkd 'eth1'" to "active\n/usr/lib/systemd/network/89-ethernet.network\n--nh-file-- /usr/lib/systemd/network/89-ethernet.network\n" +
                    "[Match]\nKind=!*\nType=ether\nName=en* eth*\n\n[Network]\nDHCP=yes\n\n",
                "$networkd 'eth2'" to "active\nn/a\n",
                "ip route show default" to "default via 192.0.2.1 dev eth0 proto dhcp src 192.0.2.50 metric 1024\n",
            ),
            mutableMapOf("/etc/systemd/network/eth0.network" to pve),
        )
        val config = config(host, "netplan", "networkctl", "systemctl")
        assertEquals("systemd-networkd", config.manager("eth0"))
        config.addAddress("eth0", "192.0.2.5/24")
        config.addRoute("default", "192.0.2.1", "eth0", "", "100")
        config.setMtu("eth0", 1400)
        config.setEnabled("eth0", false)
        config.setMtu("eth0", 1450)
        val dropIn = "/etc/systemd/network/eth0.network.d/networkhelm.conf"
        assertEquals(
            "[Address]\nAddress=192.0.2.5/24\n\n[Route]\nDestination=0.0.0.0/0\nGateway=192.0.2.1\nTable=100\n\n[Link]\nActivationPolicy=down\nMTUBytes=1450\n",
            host.files[dropIn],
        )
        config.removeAddress("eth0", "192.0.2.5/24")
        config.removeRoute("0.0.0.0/0", "192.0.2.1", "eth0", "1024", "100")
        assertEquals("[Link]\nActivationPolicy=down\nMTUBytes=1450\n", host.files[dropIn])
        assertEquals(pve, host.files["/etc/systemd/network/eth0.network"])
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { config.addRoute("192.0.2.0/24", "192.0.2.1", "eth0", "", "uplink") }.category)

        config.addAddress("eth2", "192.0.2.7/24")
        assertEquals("[Match]\nName=eth2\n", host.files["/etc/systemd/network/00-networkhelm-eth2.network"])
        assertEquals("[Address]\nAddress=192.0.2.7/24\n", host.files["/etc/systemd/network/00-networkhelm-eth2.network.d/networkhelm.conf"])

        assertNull(config.manager("eth1"))
        assertTrue("more interfaces" in assertFailsWith<DeviceException> { config.addAddress("eth1", "192.0.2.6/24") }.message.orEmpty())
        assertTrue(host.ran.none { "networkctl reload" in it || "restart" in it }, "${host.ran}")
    }

    @Test
    fun theSectionsOfANetworkFileComeAndGoWithTheirEntries() {
        assertEquals(listOf("eth0"), networkdNames("[Match]\nName = eth0\n\n[Network]\nDHCP = ipv4\n"))
        assertEquals(listOf("en*", "eth*"), networkdNames("[Match]\nName=en*\nName=eth*\n"))
        assertEquals(listOf("eth1"), networkdNames("[Match]\nName=eth0\nName=\nName=eth1\n"))

        val main = "# by hand\n[Match]\nName=eth0\n\n[Network]\nAddress=192.0.2.5/24\nGateway=192.0.2.1\nDNS=192.0.2.1\n\n[Address]\nAddress=198.51.100.5/24\nLabel=eth0:1\n"
        assertEquals(
            "# by hand\n\n[Match]\nName=eth0\n\n[Network]\nGateway=192.0.2.1\nDNS=192.0.2.1\n\n[Address]\nAddress=198.51.100.5/24\nLabel=eth0:1\n",
            networkdWithout(main, { _, _ -> false }) { section, key, value -> section == "Network" && key == "Address" && value == "192.0.2.5/24" },
        )
        assertEquals(
            "# by hand\n\n[Match]\nName=eth0\n\n[Network]\nAddress=192.0.2.5/24\nGateway=192.0.2.1\nDNS=192.0.2.1\n",
            networkdWithout(main, { section, entries -> section == "Address" && ("Address" to "198.51.100.5/24") in entries }) { _, _, _ -> false },
        )
        assertNull(networkdWithout(main, { section, _ -> section == "Route" }) { _, _, _ -> false })
        assertEquals("", networkdWithout("[Network]\nVLAN=vlan10\n", { _, _ -> false }) { _, key, value -> key == "VLAN" && value == "vlan10" })

        assertEquals("[Link]\nMTUBytes=1500\n", networkdSet("[Link]\nMTUBytes=1400\n", "Link", "MTUBytes", "1500"))
        assertEquals("[Address]\nAddress=192.0.2.5/24\n\n[Link]\nMTUBytes=1500\n", networkdSet("[Address]\nAddress=192.0.2.5/24\n", "Link", "MTUBytes", "1500"))
        assertEquals("[Address]\nAddress=192.0.2.5/24\n", networkdWith("[Address]\nAddress=192.0.2.5/24\n", "Address", listOf("Address" to "192.0.2.5/24")))
    }

    @Test
    fun netplanTakesAFileOfItsOwnThatGenerateChecks() = runTest {
        val cloud = "network:\n    ethernets:\n        lan:\n            dhcp4: true\n            match:\n                macaddress: 52:54:00:12:34:56\n            set-name: eth0\n    version: 2\n"
        val host = Host(
            listOf(
                "for f in /lib/netplan/*.yaml" to "/etc/netplan/50-cloud-init.yaml\n",
                "for f in '/etc/netplan/50-cloud-init.yaml'" to "--nh-file-- /etc/netplan/50-cloud-init.yaml\n$cloud\n",
            ),
        )
        val config = config(host, "netplan", "networkctl", "systemctl")
        assertEquals("netplan (systemd-networkd)", config.manager("eth0"))
        config.addAddress("eth0", "192.0.2.5/24")
        config.addRoute("198.51.100.0/24", "192.0.2.1", "eth0", "100", "")
        config.setMtu("eth0", 1400)
        val own = "/etc/netplan/90-networkhelm.yaml"
        val lan = Json.parseToJsonElement(host.files.getValue(own)).jsonObject.getValue("network").jsonObject.getValue("ethernets").jsonObject.getValue("lan").jsonObject
        assertEquals(listOf("192.0.2.5/24"), lan.getValue("addresses").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(mapOf("to" to "198.51.100.0/24", "via" to "192.0.2.1", "metric" to "100"), lan.getValue("routes").jsonArray.single().jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals("1400", lan.getValue("mtu").jsonPrimitive.content)
        val write = host.ran.last { it.startsWith("mkdir -p") }
        assertTrue("chmod 600" in write && "netplan generate" in write && "mv -f '/etc/netplan/.90-networkhelm.yaml.networkhelm-old' '$own'" in write, write)

        assertTrue("other netplan files" in assertFailsWith<DeviceException> { config.removeAddress("eth0", "192.0.2.9/24") }.message.orEmpty())
        config.removeAddress("eth0", "192.0.2.5/24")
        config.removeRoute("198.51.100.0/24", "192.0.2.1", "eth0", "", "")
        config.setEnabled("eth0", false)
        assertEquals(
            JsonObject(mapOf("mtu" to JsonPrimitive(1400), "activation-mode" to JsonPrimitive("manual"))),
            Json.parseToJsonElement(host.files.getValue(own)).jsonObject.getValue("network").jsonObject.getValue("ethernets").jsonObject.getValue("lan"),
        )
    }

    @Test
    fun netplanFilesNameTheirDevicesAndRenderers() {
        val devices = parseNetplan(
            listOf(
                "network:\n  version: 2\n  renderer: NetworkManager\n  ethernets:\n    id0:\n      match:\n        name: \"en*\"\n      dhcp4: true\n    lan:\n      match:\n" +
                    "        macaddress: 52:54:00:12:34:56\n  bridges:\n    br0: # the bridge\n      interfaces: [id0]\n      renderer: networkd\n      addresses:\n" +
                    "        - 192.0.2.5/24\n      routes:\n        - to: default\n          via: 192.0.2.1\n",
                "{\"network\": {\"version\": 2, \"vlans\": {\"vlan10\": {\"id\": 10, \"link\": \"lan\"}}}}",
            ),
        )
        assertEquals(
            listOf("ethernets/id0/en*/NetworkManager", "ethernets/lan//NetworkManager", "bridges/br0/br0/networkd", "vlans/vlan10/vlan10/NetworkManager"),
            devices.map { "${it.group}/${it.id}/${it.name}/${it.renderer}" },
        )

        val bridge = netplanWith("", "bridges", "br0") { JsonObject(emptyMap()) }
        assertEquals(Json.parseToJsonElement("""{"network": {"version": 2, "bridges": {"br0": {}}}}"""), Json.parseToJsonElement(bridge))
        assertEquals("", netplanWith(bridge, "bridges", "br0") { null })
        assertEquals("", netplanWith("", "ethernets", "eth0") { JsonObject(emptyMap()) })
        assertEquals(ErrorCategory.Api, assertFailsWith<DeviceException> { netplanWith("network:\n  version: 2\n", "ethernets", "eth0") { it } }.category)
    }

    @Test
    fun networkManagerChangesTheProfileOfTheInterface() = runTest {
        val uuid = "9711db1b-cb9a-4d39-85ee-c114cffc51f1"
        val own = "5f0d3b9e-7c2a-4e61-9b8d-3a1f6c2e4d07"
        val host = Host(
            listOf(
                "nmcli -t -f RUNNING general; nmcli -t -f GENERAL.STATE,GENERAL.CON-UUID device show 'lo'" to
                    "running\nGENERAL.STATE:100 (connected (externally))\nGENERAL.CON-UUID:a758ab6b-3c93-494a-969e-621516e64358\n",
                "nmcli -t -f RUNNING general; nmcli -t -f GENERAL.STATE,GENERAL.CON-UUID device show 'rdtest0'" to
                    "running\nGENERAL.STATE:100 (connected (externally))\nGENERAL.CON-UUID:2c1e4a7f-81d8-4c63-a4d5-8a9b0f6b1e21\n",
                "d='rdtest0'; nmcli -t -f UUID,FILENAME connection show" to "$own\n",
                "nmcli -t -f RUNNING general" to "running\nGENERAL.STATE:100 (connected)\nGENERAL.CON-UUID:$uuid\n",
                "nmcli -e no -g ipv4.method connection show" to "disabled\n",
                "nmcli -e no -g ipv4.method,ipv4.addresses" to "manual\n192.0.2.5/24\n",
                "nmcli -e no -g ipv4.routes,ipv4.gateway" to "198.51.100.0/24 192.0.2.1 100, 203.0.113.0/24 192.0.2.1\n192.0.2.1\n",
                "nmcli -e no -g connection.type" to "802-3-ethernet\n",
                "nmcli connection" to "",
                "ip route show default" to "default via 192.0.2.1 dev eth0 proto dhcp src 192.0.2.50 metric 100\n",
            ),
        )
        val config = config(host, "nmcli", "systemctl")
        assertEquals("NetworkManager", config.manager("eth0"))
        config.addAddress("eth0", "192.0.2.5/24")
        config.removeRoute("198.51.100.0/24", "192.0.2.1", "eth0", "100", "")
        config.removeRoute("0.0.0.0/0", "192.0.2.1", "eth0", "", "")
        config.removeAddress("eth0", "192.0.2.5/24")
        config.setMtu("eth0", 1400)
        config.setEnabled("eth0", false)
        config.addLink("vlan", "vlan10", "eth0", 10)
        assertEquals(
            listOf(
                "nmcli connection modify $uuid ipv4.method manual +ipv4.addresses 192.0.2.5/24",
                "nmcli connection modify $uuid ipv4.routes '203.0.113.0/24 192.0.2.1'",
                "nmcli connection modify $uuid ipv4.gateway ''",
                "nmcli connection modify $uuid -ipv4.addresses 192.0.2.5/24 ipv4.method disabled",
                "nmcli connection modify $uuid 802-3-ethernet.mtu 1400",
                "nmcli connection modify $uuid connection.autoconnect no",
                "nmcli connection add type vlan con-name 'vlan10' ifname 'vlan10' dev 'eth0' id 10 ipv4.method disabled ipv6.method ignore",
            ),
            host.ran.filter { it.startsWith("nmcli connection") },
        )
        assertTrue(host.ran.none { " up " in it || "reapply" in it }, "${host.ran}")
        assertNull(config.manager("lo"))
        config.addAddress("rdtest0", "198.51.100.77/32")
        assertEquals("nmcli connection modify $own ipv4.method manual +ipv4.addresses 198.51.100.77/32", host.ran.last { it.startsWith("nmcli connection") })
    }

    @Test
    fun theManagerIsTheOneThatHoldsTheInterface() = runTest {
        val interfaces = "auto lo\niface lo inet loopback\n\nauto eth0\niface eth0 inet dhcp\n"
        val bookworm = Host(
            listOf(
                "systemctl is-active systemd-networkd" to "active\nn/a\n",
                "PATH=\$PATH:/usr/sbin:/sbin ifup --version" to "ifup version 0.8.41\n--nh-file-- /etc/network/interfaces\n$interfaces\n",
            ),
        )
        assertEquals("ifupdown", config(bookworm, "networkctl", "ifup", "systemctl").manager("eth0"))

        val alpine = Host(
            listOf(
                "PATH=\$PATH:/usr/sbin:/sbin ifup --version" to "ifupdown-ng 0.13.0\n--nh-file-- /etc/network/interfaces\n$interfaces\n",
                "ip route show default" to "default via 192.0.2.1 dev eth0 proto dhcp src 192.0.2.50 metric 1002\n",
            ),
        )
        val config = config(alpine, "ifup", "rc-service")
        assertEquals("ifupdown-ng", config.manager("eth0"))
        assertEquals("ifupdown-ng", config.manager("eth1"))
    }

    @Test
    fun withoutAManagerTheChangeLastsUntilAReboot() = runTest {
        val host = Host(listOf("ip route show default" to "default via 192.0.2.1 dev eth0\n"))
        val config = config(host, "systemctl", "ss")
        assertNull(config.manager("eth0"))
        val refused = assertFailsWith<DeviceException> { config.addAddress("eth0", "192.0.2.5/24") }
        assertEquals(ErrorCategory.Unsupported, refused.category)
        assertEquals("this change lasts until a reboot: the host has none of NetworkManager, netplan, systemd-networkd and ifupdown", refused.message)
        assertTrue(host.ran.none { it.startsWith("mkdir -p") }, "${host.ran}")
    }
}
