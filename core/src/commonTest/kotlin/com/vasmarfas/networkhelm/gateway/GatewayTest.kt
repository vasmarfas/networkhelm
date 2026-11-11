package com.vasmarfas.networkhelm.gateway

import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpAnnouncement
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.packs.Menu
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.snmp.PrivProtocol
import com.vasmarfas.networkhelm.snmp.SnmpUser
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import com.vasmarfas.networkhelm.transport.TrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayTest {

    private val login = RouterOsLogin("192.0.2.1", "rd-test", "secret", sshKey = byteArrayOf(0, 1, 2, -1))
    private val path = MenuPath.parse("ip/address")
    private val trust = TrustPolicy(MemoryTrustStore()) { false }

    private class MemoryWire(private val out: Channel<String>, private val into: Channel<String>) : Wire {
        override suspend fun send(text: String) = out.send(text)

        override suspend fun receive() = into.receiveCatching().getOrNull()

        override fun close() {
            out.close()
            into.close()
        }
    }

    private fun wires(): Pair<Wire, Wire> {
        val toGateway = Channel<String>(Channel.UNLIMITED)
        val toBrowser = Channel<String>(Channel.UNLIMITED)
        return MemoryWire(toGateway, toBrowser) to MemoryWire(toBrowser, toGateway)
    }

    private fun TestScope.gateway(
        router: DeviceSession = LabSession(),
        store: TrustStore = MemoryTrustStore(),
        documents: GatewayStore? = null,
        reach: Reach = Reach(),
        connect: suspend (RouterOsLogin, TrustPolicy) -> DeviceSession = { _, _ -> router },
    ): GatewayClient {
        val (browser, gateway) = wires()
        backgroundScope.launch { GatewayHost(gateway, store, { login, trust, _ -> connect(login, trust) }, { _, _ -> router }, documents, reach = reach).serve() }
        return GatewayClient(browser, backgroundScope)
    }

    private class MemoryStore : GatewayStore {
        val documents = HashMap<String, String>()
        val secrets = HashMap<String, String>()

        override suspend fun read(name: String) = documents[name]

        override suspend fun write(name: String, text: String) {
            documents[name] = text
        }

        override suspend fun secret(key: String) = secrets[key]

        override suspend fun keep(key: String, value: String) {
            secrets[key] = value
        }

        override suspend fun forget(key: String) {
            secrets.remove(key)
        }
    }

    @Test
    fun documentsAndSecretsLiveOnTheGateway() = runTest {
        val store = MemoryStore()
        val client = gateway(documents = store)
        assertNull(client.readDocument("routers.json"))
        client.writeDocument("routers.json", "[{\"address\":\"192.0.2.1\"}]")
        assertEquals("[{\"address\":\"192.0.2.1\"}]", client.readDocument("routers.json"))
        client.writeSecret("password/192.0.2.1/rd-test", "ünïcode ✓")
        assertEquals("ünïcode ✓", store.secrets["password/192.0.2.1/rd-test"])
        assertEquals("ünïcode ✓", client.readSecret("password/192.0.2.1/rd-test"))
        client.deleteSecret("password/192.0.2.1/rd-test")
        assertNull(client.readSecret("password/192.0.2.1/rd-test"))

        val without = gateway()
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { without.readDocument("routers.json") }.category)
    }

    @Test
    fun callsReachTheRouterAndTheAnswersComeBackWhole() = runTest {
        val router = LabSession()
        var seen: RouterOsLogin? = null
        val session = gateway(router) { login, _ ->
            seen = login
            router
        }.connect(login, trust)
        assertContentEquals(byteArrayOf(0, 1, 2, -1), seen?.sshKey)
        assertEquals("lab", session.device.identity)
        assertEquals("lab-id", session.device.id)
        assertTrue(session.device.canWrite)
        assertTrue(session.capabilities.terminal)

        val schema = session.describe(path)
        assertEquals(path, schema.path)
        assertEquals(ValueType.Many(ValueType.Ip4Prefix), schema.argument("address")?.type)
        assertEquals(listOf("ether1", "bridge1"), schema.argument("interface")?.choices)
        assertEquals("ünïcode ✓", session.get(path, "*1")?.get("comment"))
        assertNull(session.get(path, "*404"))
        assertEquals(1, session.count(path, Filter.And(listOf(Filter.Equals("disabled", "true"), Filter.Not(Filter.Has("dynamic"))))))
        assertEquals("*9", session.add(path, mapOf("address" to "192.0.2.9/24")))
        session.set(path, "*9", mapOf("comment" to null, "disabled" to "yes"))
        session.move(path, listOf("*9"), null)
        session.remove(path, listOf("*9"))
        assertEquals(
            listOf("add ip/address {address=192.0.2.9/24}", "set ip/address *9 {comment=null, disabled=yes}", "move ip/address [*9] null", "remove ip/address [*9]"),
            router.calls,
        )
        assertTrue(session.export(path).startsWith("/ip address"))
        assertEquals(listOf("0", "1"), session.run(path, "ping", mapOf("address" to "192.0.2.1")).toList().map { it["seq"] })
        assertEquals(listOf(mapOf("*1" to mapOf("bytes" to "100"))), session.counters(path, listOf("bytes")).toList())
        assertEquals(listOf(mapOf("*2" to mapOf("bytes" to "100"))), session.counters(path, listOf("bytes"), Filter.Equals(".id", "*2")).toList())
        assertEquals(bundledPacks().values.take(2).map { it.window.title }, session.packs().map { it.window.title })
        assertEquals("admin/status/overview", session.nativeMenu()?.menu?.items?.single()?.items?.single()?.page)
        assertEquals(listOf(LinkState.Up), session.link.toList())
    }

    @Test
    fun anOpenWrtRouterOpensTheSameWay() = runTest {
        val session = gateway(LabSession(Platform.OpenWrt)).connect(OpenWrtLogin("192.0.2.2", "root", "secret"), trust)
        assertEquals(Platform.OpenWrt, session.device.platform)
    }

    @Test
    fun theOtherPlatformsOpenWithTheirLogins() = runTest {
        val seen = mutableListOf<Login>()
        val (browser, gateway) = wires()
        backgroundScope.launch {
            GatewayHost(gateway, MemoryTrustStore(), { _, _, _ -> LabSession() }, { _, _ -> LabSession() }, connectLogin = { login, _ ->
                seen += login
                LabSession(Platform.Vyos)
            }).serve()
        }
        val client = GatewayClient(browser, backgroundScope)
        assertEquals(Platform.Vyos, client.connect(Login.Vyos("192.0.2.3", "rd-test-key", 443), trust).device.platform)
        client.connect(Login.Ssh("192.0.2.4", 2222, "rd-test", "", byteArrayOf(0, 1, 2, -1)), trust)
        client.connect(Login.Keenetic("192.0.2.5", "", "rd-test-token", tls = true, port = 443), trust)
        client.connect(Login.Snmp("192.0.2.6", "", 161, SnmpUser("rd-test", AuthProtocol.Md5, "rd-test-auth", PrivProtocol.Aes, "rd-test-privacy")), trust)
        val vyos = seen[0] as Login.Vyos
        val ssh = seen[1] as Login.Ssh
        val keenetic = seen[2] as Login.Keenetic
        val snmp = (seen[3] as Login.Snmp).user
        assertEquals(listOf("192.0.2.3", "rd-test-key", "443"), listOf(vyos.host, vyos.key, "${vyos.port}"))
        assertContentEquals(byteArrayOf(0, 1, 2, -1), ssh.key)
        assertEquals(listOf("2222", "rd-test", "false"), listOf("${ssh.port}", ssh.user, "${ssh.agent}"))
        assertTrue(keenetic.tls && keenetic.user.isEmpty())
        assertEquals(listOf("rd-test", "Md5", "rd-test-auth", "Aes", "rd-test-privacy"), listOf(snmp?.name, "${snmp?.auth}", snmp?.authPassword, "${snmp?.priv}", snmp?.privPassword))
    }

    @Test
    fun aListStreamsUntilTheWindowStopsWatching() = runTest {
        val router = LabSession()
        val session = gateway(router).connect(login, trust)
        val events = session.watch(path).take(2).toList()
        assertEquals(ListEvent.Snapshot(listOf(Item("*1", mapOf("address" to "192.0.2.1/24")))), events[0])
        assertEquals(ListEvent.Upsert(Item("*2", mapOf("address" to "198.51.100.1/24")), ""), events[1])
        router.stopped.await()
    }

    @Test
    fun aFailureKeepsTheCategoryAndTheTextOfTheRouter() = runTest {
        val session = gateway().connect(login, trust)
        val failure = assertFailsWith<DeviceException> { session.describe(MenuPath.parse("ip/missing")) }
        assertEquals(ErrorCategory.MissingItem, failure.category)
        assertEquals("no such command prefix", failure.message)
    }

    @Test
    fun anUnknownCertificateIsAskedAboutInTheBrowser() = runTest {
        val store = MemoryTrustStore()
        val client = gateway(store = store) { _, trust ->
            if (!trust.confirm("192.0.2.1", 8729, TrustKind.Tls, "SHA256:new", null)) throw DeviceException(ErrorCategory.PermissionDenied, "not accepted")
            LabSession()
        }
        val asked = mutableListOf<TrustRequest>()
        val refused = assertFailsWith<DeviceException> {
            client.connect(login, TrustPolicy(MemoryTrustStore()) { request -> asked += request; false })
        }
        assertEquals(ErrorCategory.PermissionDenied, refused.category)
        assertNull(store.get("192.0.2.1", 8729, TrustKind.Tls))

        client.connect(login, TrustPolicy(MemoryTrustStore()) { request -> asked += request; true })
        assertEquals("SHA256:new", store.get("192.0.2.1", 8729, TrustKind.Tls))
        assertEquals(listOf("SHA256:new", "SHA256:new"), asked.map { it.fingerprint })
    }

    @Test
    fun aTerminalCarriesEveryByteBothWays() = runTest {
        val router = LabSession()
        val shell = assertNotNull(gateway(router).connect(login, trust).ssh).shell("xterm-256color", 80, 24)
        val bytes = ByteArray(256) { it.toByte() }
        shell.write(bytes)
        val read = ByteArray(512)
        var total = 0
        while (total < bytes.size) total += shell.read(read, total, read.size - total)
        assertContentEquals(bytes, read.copyOf(total))
        shell.resize(120, 40)
        assertEquals(120 to 40, router.terminal.resized.await())
        shell.close()
        router.terminal.closed.await()
    }

    @Test
    fun filesTravelWholeAndTheirListComesBack() = runTest {
        val ssh = assertNotNull(gateway().connect(login, trust).ssh)
        val sftp = ssh.sftp()
        val bytes = ByteArray(70_000) { (it * 31).toByte() }
        sftp.write("/flash/rd-test.bin", bytes)
        assertContentEquals(bytes, sftp.read("/flash/rd-test.bin"))
        assertEquals(listOf("rd-test.bin" to 70_000L), sftp.list("/flash").map { it.name to it.size })
        sftp.remove("/flash/rd-test.bin")
        assertEquals(emptyList(), sftp.list("/flash"))
        assertEquals("rd-test", ssh.exec("/system identity print").output)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { ssh.forward("127.0.0.1", 8728) }.category)
    }

    @Test
    fun safeModeIsEnteredAndKeptThroughTheGateway() = runTest {
        val router = LabSession()
        val safe = assertNotNull(gateway(router).connect(login, trust).safeMode)
        assertTrue(safe.enter())
        assertEquals(listOf(false, true), safe.active.toList())
        assertEquals(listOf(0, 2), safe.actions.toList())
        safe.commit()
        safe.rollback()
        assertEquals(listOf(true, false), safe.stranded.toList())
        safe.settle(keep = false)
        assertEquals(listOf("commit", "rollback", "settle false"), router.calls)
    }

    @Test
    fun whenTheWireGoesWhatWaitsFailsAndTheLinkCloses() = runTest {
        val router = LabSession()
        val (browser, gateway) = wires()
        val host = backgroundScope.launch { GatewayHost(gateway, MemoryTrustStore(), { _, _, _ -> router }, { _, _ -> router }).serve() }
        val client = GatewayClient(browser, backgroundScope)
        val session = client.connect(login, trust)
        val waiting = async { assertFailsWith<DeviceException> { session.describe(MenuPath.parse("ip/slow")) } }
        router.slow.await()
        gateway.close()
        assertEquals(ErrorCategory.ConnectionLost, waiting.await().category)
        assertEquals(LinkState.Closed, session.link.last())
        assertEquals(ErrorCategory.ConnectionLost, client.ended.await().category)
        host.join()
        assertTrue(router.closed)
    }

    @Test
    fun aRouterConnectedAfterTheHostIsCancelledIsClosed() = lateConnectionIsClosed(cancelHost = true)

    @Test
    fun aRouterConnectedAfterItsRequestIsCancelledIsClosed() = lateConnectionIsClosed(cancelHost = false)

    private fun lateConnectionIsClosed(cancelHost: Boolean) = runTest {
        val router = LabSession()
        val opening = CompletableDeferred<Job>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val (browser, gateway) = wires()
        val wire = object : Wire by gateway {
            override fun close() {
                gateway.close()
                stopped.complete(Unit)
            }
        }
        val host = backgroundScope.launch {
            GatewayHost(wire, MemoryTrustStore(), { _, _, _ ->
                opening.complete(currentCoroutineContext()[Job] ?: error("no connection job"))
                try {
                    release.await()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                router
            }, { _, _ -> router }).serve()
        }
        browser.receive()
        browser.send(gatewayJson.encodeToString(ToGateway.serializer(), ToGateway.Ask(1, Request.ConnectRouterOs(login))))
        val request = opening.await()
        if (cancelHost) {
            host.cancel()
            stopped.await()
        } else {
            browser.send(gatewayJson.encodeToString(ToGateway.serializer(), ToGateway.Cancel(1)))
        }
        cancelled.await()
        release.complete(Unit)
        request.join()
        if (cancelHost) host.join()
        assertTrue(router.closed)
    }

    @Test
    fun aConnectionGivenUpAsItsAnswerArrivesIsClosedOnTheGateway() = runTest {
        val router = LabSession()
        val (browser, gateway) = wires()
        backgroundScope.launch { GatewayHost(gateway, MemoryTrustStore(), { _, _, _ -> router }, { _, _ -> router }).serve() }
        lateinit var connecting: Job
        val client = GatewayClient(object : Wire by browser {
            override suspend fun receive() = browser.receive()?.also { if ("\"type\":\"value\"" in it) connecting.cancel() }
        }, backgroundScope)
        connecting = launch { client.connect(login, trust) }
        connecting.join()
        assertFailsWith<DeviceException> { client.readDocument("routers.json") }
        assertTrue(router.closed)
    }

    @Test
    fun aFrameTheGatewayCannotReadEndsTheWireAndIsNotQuoted() = runTest {
        val (browser, gateway) = wires()
        browser.send("{\"type\":\"ask\",\"id\":1,\"request\":{\"type\":\"connect-routeros\",\"login\":{\"host\":\"192.0.2.1\",\"user\":\"rd-test\",\"password\":\"rd-test-secret\",\"port\":\"none\"}}}")
        val failure = assertFailsWith<DeviceException> { GatewayHost(gateway, MemoryTrustStore(), { _, _, _ -> LabSession() }, { _, _ -> LabSession() }).serve() }
        assertFalse("rd-test-secret" in failure.stackTraceToString())
        browser.receive()
        assertNull(browser.receive())
    }

    @Test
    fun aGatewayOfAnotherProtocolIsRefused() = runTest {
        val (browser, gateway) = wires()
        gateway.send(gatewayJson.encodeToString(FromGateway.serializer(), FromGateway.Hello(GATEWAY_PROTOCOL + 1)))
        val failure = assertFailsWith<DeviceException> { GatewayClient(browser, backgroundScope).connect(login, trust) }
        assertEquals(ErrorCategory.Unsupported, failure.category)
    }

    @Test
    fun theHelloTellsWhatTheMachineOfTheGatewayOffers() = runTest {
        val full = gateway(reach = Reach(
            connectNative = { _, _ -> LabSession() },
            connectMacTelnet = { _, _ -> LabSession() },
            interfaces = { listOf("eth0, 192.0.2.7 (00:AA:BB:CC:DD:EE)") },
            neighbors = { emptyFlow() },
            scan = { emptyFlow() },
        ))
        full.hello()
        assertTrue(full.offers.native && full.offers.neighbors && full.offers.scan)
        assertEquals(listOf("eth0, 192.0.2.7 (00:AA:BB:CC:DD:EE)"), full.offers.interfaces)

        val bare = gateway()
        bare.hello()
        assertFalse(bare.offers.native || bare.offers.neighbors || bare.offers.scan)
        assertEquals(emptyList(), bare.offers.interfaces)
        val without = gateway(reach = Reach(connectMacTelnet = { _, _ -> LabSession() }))
        without.hello()
        assertEquals(emptyList(), without.offers.interfaces)
    }

    @Test
    fun thePortOfWinBoxAndTheMacAddressAreReachedFromTheGateway() = runTest {
        var native: NativeLogin? = null
        var mac: MacTelnetLogin? = null
        val client = gateway(reach = Reach(
            connectNative = { login, _ ->
                native = login
                LabSession(nativeFiles = true, tunnels = false)
            },
            connectMacTelnet = { login, _ ->
                mac = login
                LabSession(nativeFiles = true, tunnels = false)
            },
            interfaces = { listOf("eth0") },
        ))
        val session = client.connect(NativeLogin("192.0.2.8", 8292, "rd-test", "secret", ApiEncoding.Windows1251))
        assertEquals(listOf("192.0.2.8", "8292", "rd-test", "secret", "Windows1251"), native?.let { listOf(it.host, "${it.port}", it.user, it.password, "${it.encoding}") })
        assertEquals("lab", session.device.identity)
        client.connect(MacTelnetLogin("00:AA:BB:CC:DD:EE", "rd-test", "secret", "eth0"))
        assertEquals(listOf("00:AA:BB:CC:DD:EE", "rd-test", "eth0", "Utf8"), mac?.let { listOf(it.mac, it.user, it.interfaceName, "${it.encoding}") })
    }

    @Test
    fun aGatewayWithoutThePartsRefusesTheRequestsForThem() = runTest {
        val client = gateway()
        suspend fun refused(block: suspend () -> Unit) = assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { block() }.category)
        refused { client.connect(NativeLogin("192.0.2.8", 8291, "rd-test", "secret")) }
        refused { client.connect(MacTelnetLogin("00:AA:BB:CC:DD:EE", "rd-test", "secret", null)) }
        refused { client.neighbors().toList() }
        refused { client.refreshNeighbors() }
        refused { client.scan(listOf("192.0.2.1")).toList() }
    }

    @Test
    fun theAnnouncementsOfMndpAndTheSweepOfAddressesComeThroughTheGateway() = runTest {
        val sighting = MndpSighting(
            "192.0.2.9",
            MndpAnnouncement("00:AA:BB:CC:DD:EE", "rd-test", "7.26", "MikroTik", 3600, null, "CHR", "ether1", "192.0.2.9", null, mapOf(21 to byteArrayOf(1, 2, -1))),
        )
        var refreshed = 0
        var swept: List<String>? = null
        val client = gateway(reach = Reach(
            neighbors = { flowOf(sighting) },
            refreshNeighbors = { refreshed++ },
            scan = { addresses ->
                swept = addresses
                flowOf(Found(addresses.first(), listOf(22, 8291)))
            },
        ))
        val heard = client.neighbors().toList().single()
        assertEquals(listOf("192.0.2.9", "rd-test", "7.26", "ether1"), listOf(heard.sourceAddress, heard.announcement.identity, heard.announcement.version, heard.announcement.interfaceName))
        assertContentEquals(byteArrayOf(1, 2, -1), heard.announcement.other.getValue(21))
        client.refreshNeighbors()
        assertEquals(1, refreshed)
        val found = client.scan(listOf("192.0.2.1", "192.0.2.2")).toList().single()
        assertEquals(listOf("192.0.2.1", listOf(22, 8291)), listOf(found.address, found.ports))
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), swept)
        val failure = assertFailsWith<DeviceException> { client.scan(List(1025) { "192.0.2.1" }).toList() }
        assertEquals(ErrorCategory.ArgumentValue, failure.category)
    }

    @Test
    fun aSessionKeepsTheKindOfItsSshAndItsOwnFileChannel() = runTest {
        val router = LabSession(nativeFiles = true, tunnels = false, channel = true)
        val session = gateway(router).connect(login, trust)
        val ssh = assertNotNull(session.ssh)
        assertTrue(ssh.fileStore)
        assertFalse(ssh.canForward)
        assertEquals(SshReach.Open, ssh.reach())
        assertEquals(SshReach.Closed(2222), ssh.reach(fresh = true))
        val channel = assertNotNull(session.fileChannel)
        channel.mkdir("/flash/rd-test")
        channel.rename("/flash/rd-test", "/flash/rd-test-2")
        ssh.sftp().rename("/flash/a", "/flash/b")
        assertEquals(listOf("mkdir /flash/rd-test", "rename /flash/rd-test /flash/rd-test-2", "rename /flash/a /flash/b"), router.calls)

        val plain = gateway().connect(login, trust)
        assertFalse(assertNotNull(plain.ssh).fileStore)
        assertTrue(plain.ssh?.canForward == true)
        assertNull(plain.fileChannel)
    }

    @Test
    fun theStructureOfARouterIsOutlinedAndForgottenOnTheGateway() = runTest {
        val router = LabSession()
        val session = gateway(router).connect(login, trust)
        assertEquals(listOf("print"), session.outline(path).commands)
        session.forgetStructure()
        assertEquals(listOf("outline ip/address", "forget"), router.calls)
    }

    private class LabSession(
        platform: Platform = Platform.RouterOs,
        private val nativeFiles: Boolean = false,
        private val tunnels: Boolean = true,
        channel: Boolean = false,
    ) : DeviceSession {
        val calls = mutableListOf<String>()
        val stopped = CompletableDeferred<Unit>()
        val slow = CompletableDeferred<Unit>()
        val terminal = EchoTerminal()
        var closed = false
        private val files = linkedMapOf<String, ByteArray>()

        override val device = DeviceInfo(platform, "lab", "7.26", "x86_64", "CHR", setOf("routeros"), setOf("read", "write"), id = "lab-id")
        override val capabilities = Capabilities(true, true, true, SafeModeKind.ApiSession, terminal = true, files = true, export = true)

        override suspend fun describe(path: MenuPath): MenuSchema = when (path.toString()) {
            "ip/missing" -> throw DeviceException(ErrorCategory.MissingItem, "no such command prefix")
            "ip/slow" -> {
                slow.complete(Unit)
                awaitCancellation()
            }
            else -> MenuSchema(
                path,
                NodeKind.List,
                emptyList(),
                listOf("add", "print", "remove", "set"),
                listOf(Argument("address", ValueType.Many(ValueType.Ip4Prefix)), Argument("interface", ValueType.Enumeration(), listOf("ether1", "bridge1"))),
            )
        }

        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
            try {
                emit(ListEvent.Snapshot(listOf(Item("*1", mapOf("address" to "192.0.2.1/24")))))
                emit(ListEvent.Upsert(Item("*2", mapOf("address" to "198.51.100.1/24")), ""))
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }

        override suspend fun outline(path: MenuPath): MenuSchema {
            calls += "outline $path"
            return MenuSchema(path, NodeKind.List, emptyList(), listOf("print"), emptyList())
        }

        override suspend fun forgetStructure() {
            calls += "forget"
        }

        override suspend fun get(path: MenuPath, id: String?, properties: List<String>?) = if (id == "*1") Item("*1", mapOf("comment" to "ünïcode ✓")) else null

        override suspend fun count(path: MenuPath, filter: Filter?) = if (filter is Filter.And) 1 else 3

        override suspend fun add(path: MenuPath, values: Map<String, String>): String {
            calls += "add $path $values"
            return "*9"
        }

        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            calls += "set $path $id $changes"
        }

        override suspend fun remove(path: MenuPath, ids: List<String>) {
            calls += "remove $path $ids"
        }

        override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
            calls += "move $path $ids $before"
        }

        override fun run(path: MenuPath, command: String, arguments: Map<String, String>) =
            flowOf(Item("", mapOf("seq" to "0", "time" to "1ms")), Item("", mapOf("seq" to "1", "time" to "2ms")))

        override suspend fun export(path: MenuPath) = "/ip address\nadd address=192.0.2.1/24 interface=ether1\n"

        override fun counters(path: MenuPath, properties: List<String>, filter: Filter?) =
            flowOf(mapOf(((filter as? Filter.Equals)?.value ?: "*1") to mapOf("bytes" to "100")))

        override val link = flowOf(LinkState.Up)

        override suspend fun packs(store: TextStore?): List<Pack> = bundledPacks().values.take(2)

        override suspend fun nativeMenu() =
            NativeMenu(Menu(platform = "openwrt", items = listOf(MenuEntry("Status", items = listOf(MenuEntry("Overview", page = "admin/status/overview"))))), emptyList())

        override val safeMode = object : SafeMode {
            override val active = flowOf(false, true)
            override val actions = flowOf(0, 2)

            override suspend fun enter() = true

            override suspend fun commit() {
                calls += "commit"
            }

            override suspend fun rollback() {
                calls += "rollback"
            }

            override val stranded = flowOf(true, false)

            override suspend fun settle(keep: Boolean) {
                calls += "settle $keep"
            }
        }

        override val ssh = object : SshSession {
            override suspend fun exec(command: String) = ExecResult("rd-test", "", 0)

            override suspend fun process(command: String): ByteStream = error("not used")

            override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = terminal

            override suspend fun sftp() = sftpClient()

            override suspend fun forward(host: String, port: Int): ByteStream = error("not used")

            override val fileStore = nativeFiles

            override val canForward = tunnels

            override suspend fun reach(fresh: Boolean) = if (fresh) SshReach.Closed(2222) else SshReach.Open

            override fun close() = Unit
        }

        override val fileChannel: SftpClient? = if (channel) sftpClient() else null

        private fun sftpClient() = object : SftpClient {
            override suspend fun list(path: String) = files.filterKeys { it.startsWith("$path/") }.map { (name, bytes) ->
                RemoteFile(name.substringAfterLast('/'), bytes.size.toLong(), false, 0)
            }

            override suspend fun read(path: String) = files.getValue(path)

            override suspend fun write(path: String, bytes: ByteArray) {
                files[path] = bytes
            }

            override suspend fun remove(path: String) {
                files.remove(path)
            }

            override suspend fun rename(from: String, to: String) {
                calls += "rename $from $to"
            }

            override suspend fun mkdir(path: String) {
                calls += "mkdir $path"
            }

            override fun close() = Unit
        }

        override fun close() {
            closed = true
        }
    }

    private class EchoTerminal : ShellChannel {
        private val typed = Channel<ByteArray>(Channel.UNLIMITED)
        val resized = CompletableDeferred<Pair<Int, Int>>()
        val closed = CompletableDeferred<Unit>()

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = typed.receiveCatching().getOrNull() ?: return -1
            bytes.copyInto(buffer, offset)
            return bytes.size
        }

        override suspend fun write(bytes: ByteArray) = typed.send(bytes)

        override fun resize(columns: Int, rows: Int) {
            resized.complete(columns to rows)
        }

        override fun close() {
            typed.close()
            closed.complete(Unit)
        }
    }
}
