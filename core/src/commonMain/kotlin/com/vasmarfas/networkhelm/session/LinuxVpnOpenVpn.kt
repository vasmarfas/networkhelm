package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// OpenVPN of a Linux host: its servers with the PKI of easy-rsa they keep the certificates of their clients in, and the client
// connections of the host itself. A server made here has its PKI in /etc/openvpn/server/<name>/, a device of its own name and a
// management socket the panel ends sessions through. Keys leave the host only in the configuration of a client asked for; a file with
// a secret goes to the host as input. A start, and the restart after a change, goes under the guard and fails when the service is not
// active 5 s later, which puts the old file back
internal class LinuxVpnOpenVpn(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val guard: LinuxGuard,
) {
    private val nat = LinuxVpnNat(root, tools, guard)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/openvpn" to HostTable(
            SERVER, { state().serverRows() }, slow = true, arguments = { SERVER_ARGUMENTS }, add = ::addServer, set = ::setServer,
            remove = ::removeServers, commands = listOf("up", "down"), run = { command, arguments -> bring(command, arguments) { host, id -> host.server(id) } },
            present = ::present,
        ),
        "vpn/openvpn/client" to HostTable(
            CLIENT, { state().clientRows() }, slow = true, arguments = ::clientArguments, add = ::addClient, set = ::setClient, remove = ::removeClients,
            commands = listOf("client-config"), run = { _, arguments -> clientConfig(arguments) }, present = ::present,
        ),
        "vpn/openvpn/connection" to HostTable(
            CONNECTION, { state().connectionRows() }, slow = true, arguments = { CONNECTION_ARGUMENTS }, add = ::addConnection, set = ::setConnection,
            remove = ::removeConnections, commands = listOf("up", "down"), run = { command, arguments -> bring(command, arguments) { host, id -> host.connection(id) } },
            present = ::present,
        ),
        "vpn/openvpn/server" to HostTable(
            WIZARD_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { WIZARD_ARGUMENTS }, commands = listOf("create"),
            run = { _, values -> create(values) }, present = ::present,
        ),
        "vpn/openvpn/import" to HostTable(
            IMPORT_ARGUMENTS.map { it.name }, { listOf(Item("", mapOf("name" to freeName(state(), "client"), "autostart" to "true", "keep-session" to "true"))) },
            settings = true, arguments = { IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importConnection(values) }, present = ::present,
        ),
    )

    private suspend fun present() = "openvpn" in tools()

    suspend fun state() = parseOpenVpn(root.masked(OPENVPN_FILES))

    private suspend fun systemd() = "systemctl" in tools()

    // a new server: a PKI whose CA has no password, decision 3 of the owner, a certificate of the server, a CRL and a key of tls-crypt;
    // then its configuration, which the settings given change as a change of the server would. It starts at once with its port open
    // where ufw or firewalld keeps the rules, as a server left stopped is one nobody reaches, and at boot only once the guard let it start
    private suspend fun addServer(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!SERVER_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val host = state()
        if (host.confs.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an OpenVPN configuration $name already")
        val given = values.filterKeys { it in SERVER_SETTINGS }.filterValues { it.isNotEmpty() }
        given.forEach { (field, value) -> checkServer(field, value) }
        val systemd = systemd()
        if (!systemd && !host.openRc) throw noService()
        if (values["nat"] in ValueType.Flag.on && !systemd && nat.manager() == null) throw DeviceException(ErrorCategory.Unsupported, "NAT for the clients of $name needs ufw, firewalld or systemd")
        val dir = "$SERVER_DIR/$name"
        if (root.exec("[ -e ${quote(dir)} ]").exitStatus == 0) throw DeviceException(ErrorCategory.ArgumentValue, "the folder $dir for the PKI is there already")
        val file = if (systemd) "$SERVER_DIR/$name.conf" else "$OPENVPN_DIR/$name.conf"
        val (socket, status) = if (systemd) "/run/openvpn-server/$name.sock" to "/run/openvpn-server/status-$name.log" else "/run/openvpn.$name.sock" to "/run/openvpn.$name.status"
        val text = OvpnText(
            listOf(
                "port 1194", "proto udp", "dev tun-$name", "topology subnet", "server 10.8.0.0 255.255.255.0", "ca $dir/pki/ca.crt",
                "cert $dir/pki/issued/server.crt", "key $dir/pki/private/server.key", "dh none", "crl-verify $dir/crl.pem", "tls-crypt $dir/tc.key",
                "tls-version-min 1.2", "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", "keepalive 10 120", "persist-key", "persist-tun",
                "client-config-dir $dir/ccd", "management $socket unix", "status $status 1", "status-version 3", "explicit-exit-notify 1", "verb 3",
            ).joinToString("\n"),
        )
        for (field in SERVER_SETTINGS) given[field]?.let { text.put(field, it) }
        try {
            root.masked(
                "d=${quote(dir)}; mkdir -p \"\$d/ccd\" && chmod 711 \"\$d\" && chmod 755 \"\$d/ccd\" || exit 1; " +
                    easyrsa("$dir/pki", "init-pki", "--req-cn=${quote("$name CA")} build-ca nopass", "build-server-full server nopass", "gen-crl") +
                    " && install -m 644 \"\$d/pki/crl.pem\" \"\$d/crl.pem\" && { openvpn --genkey secret \"\$d/tc.key\" 2>/dev/null || openvpn --genkey --secret \"\$d/tc.key\"; }",
            )
            root.write(file, text.text(), "true")
        } catch (e: DeviceException) {
            root.masked("rm -rf ${quote(dir)}")
            throw e
        }
        val made = OvpnConf(name, "inactive", false, file, true).also { it.lines += text.lines }
        if (values["nat"] in ValueType.Flag.on) setNat(made, true)
        nat.open(made.mark(), listOf(made.port()))
        guard.guarded(made.backup(systemd)) { start(made, made.start(systemd)) }
        if (values["autostart"] in ValueType.Flag.on) autostart(made, true)
        return name
    }

    // NAT follows a new network of a server that has it, the open port its port and protocol and each change of its NAT
    private suspend fun setServer(id: String?, changes: Map<String, String?>) {
        val host = state()
        val conf = host.server(id)
        val fields = changes.filterKeys { it in SERVER_SETTINGS }.mapValues { it.value.orEmpty() }
        fields.forEach { (field, value) -> if (value.isNotEmpty()) checkServer(field, value) }
        if (fields.isNotEmpty()) change(conf) { text -> for (field in SERVER_SETTINGS) fields[field]?.let { text.put(field, it) } }
        changes["autostart"]?.let { autostart(conf, it in ValueType.Flag.on) }
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on } ?: true.takeIf { host.natted(conf) && fields.keys.any { it in NAT_FIELDS } }
        if (natOn == null && "port" !in fields && "proto" !in fields) return
        val now = state().server(conf.name)
        natOn?.let { setNat(now, it) }
        nat.open(now.mark(), listOf(now.port()))
    }

    // a server goes with its service, its NAT and the folder of the PKI made here for it
    private suspend fun removeServers(ids: List<String>) {
        val host = state()
        val confs = ids.map(host::server)
        for (conf in confs) if (conf.running) conf.directive("dev")?.let { keepSession(ssh, it) }
        val systemd = systemd()
        for (conf in confs) {
            if (host.natted(conf)) setNat(conf, false)
            nat.open(conf.mark(), emptyList())
            val own = "$SERVER_DIR/${conf.name}"
            root.masked(listOfNotNull(conf.drop(systemd), "rm -rf ${quote(own)}".takeIf { conf.pki == "$own/pki" }).joinToString(" && "))
        }
    }

    // a start goes under the guard; a server this session comes in by is not stopped
    private suspend fun bring(command: String, arguments: Map<String, String>, find: (OpenVpnState, String) -> OvpnConf): List<Item> {
        val host = state()
        val systemd = systemd()
        for (conf in ids(arguments["numbers"]).map { find(host, it) }) {
            when (command) {
                "up" -> if (!conf.running) guard.guarded(conf.backup(systemd)) { start(conf, conf.start(systemd)) }
                "down" -> if (conf.running) {
                    conf.directive("dev")?.let { keepSession(ssh, it) }
                    root.masked(conf.stop(systemd))
                }
            }
        }
        return emptyList()
    }

    private suspend fun autostart(conf: OvpnConf, on: Boolean) {
        val systemd = systemd()
        root.masked(if (on) conf.enable(systemd) else conf.disable(systemd))
    }

    // NAT for a device of a name of its own, which a dynamic tun is not: through ufw or firewalld where one keeps the rules, else by a
    // unit of its own beside the unit of the server; forwarding comes on with it
    private suspend fun setNat(conf: OvpnConf, on: Boolean) {
        val dev = conf.directive("dev").orEmpty()
        val named = dev.isNotEmpty() && dev != "tun" && dev != "tap"
        val manager = nat.manager()
        if (!on) {
            if (named && manager != null) nat.set(manager, dev, emptyList(), emptyList(), false)
            if (systemd()) hook(conf, emptyList())
            return
        }
        if (manager == null && !systemd()) throw DeviceException(ErrorCategory.Unsupported, "NAT for the clients of ${conf.name} needs ufw, firewalld or systemd")
        if (!named) throw DeviceException(ErrorCategory.ArgumentValue, "NAT for the clients of ${conf.name} needs a device with a name of its own, such as tun-${conf.name}")
        val networks = conf.networks()
        nat.forward(networks.any { ':' in it })
        if (manager != null) nat.set(manager, dev, networks, emptyList(), true) else hook(conf, networks)
    }

    // NAT where neither ufw nor firewalld keeps the rules: a unit of its own, bound to the one of the server and wanted by it, puts them in
    // once the server has started and takes them out as it stops. The unit of the server is no place for them: OpenVPN limits its
    // processes, LimitNPROC=10 on Debian 12, and the root of an unprivileged container, which has more processes than that, cannot fork
    // a shell there. A running server swaps the rules before for the new ones at once; no networks take the unit away
    private suspend fun hook(conf: OvpnConf, networks: List<String>) {
        val unit = conf.natUnit()
        val file = quote("$UNITS/$unit")
        val n = quote(unit)
        if (networks.isEmpty()) {
            root.masked("[ ! -e $file ] || { systemctl disable --now $n; rm -f $file && systemctl daemon-reload; }")
            return
        }
        val dev = conf.directive("dev").orEmpty()
        val (up, down) = natHooks(networks, "iptables-save" in tools()).toList().map { it.substringBefore(" # ").replace("%i", dev) }
        val server = "${conf.unitName()}.service"
        root.masked("systemctl stop $n 2>/dev/null; true")
        root.write(
            "$UNITS/$unit",
            "[Unit]\nDescription=NAT for the clients of OpenVPN ${conf.name}\nBindsTo=$server\nAfter=$server\n\n[Service]\nType=oneshot\nRemainAfterExit=yes\n" +
                "ExecStart=/bin/sh -c \"$up\"\nExecStop=-/bin/sh -c \"$down\"\n\n[Install]\nWantedBy=$server",
            "systemctl daemon-reload 2>&1", "644",
        )
        root.masked("systemctl enable $n && if systemctl is-active -q ${conf.unit()}; then systemctl start $n; fi")
    }

    // the whole file, keys and all, is read as root and edited line by line; a running service restarts under the guard
    private suspend fun change(conf: OvpnConf, edit: (OvpnText) -> Unit) {
        val before = root.masked("cat ${quote(conf.file)}")
        val text = OvpnText(before)
        edit(text)
        if (text.text() == before) return
        if (!conf.running) {
            root.write(conf.file, text.text(), "true")
            return
        }
        conf.directive("dev")?.let { keepSession(ssh, it) }
        val systemd = systemd()
        guard.guarded(conf.backup(systemd)) {
            root.write(conf.file, text.text(), "true")
            start(conf, conf.restart(systemd))
        }
    }

    // a service not active 5 s after its start failed, and the end of its journal says why
    private suspend fun start(conf: OvpnConf, line: String) {
        val systemd = systemd()
        val result = root.exec("$line; sleep 5; if ${conf.active(systemd)}; then exit 0; fi; ${conf.journal(systemd)}; exit 1")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, "${conf.name} did not start: ${hidden(result.output.trim().ifEmpty { result.error.trim() })}")
    }

    private suspend fun clientArguments(): List<Argument> {
        val servers = state().servers.filter { it.pki != null }.map { it.name }
        return listOf(Argument("server", ValueType.Enumeration(closed = true), servers, default = servers.firstOrNull(), fixed = true)) + CLIENT_ARGUMENTS
    }

    // a client is a certificate easy-rsa issues with a key without a password, which stays on the host for the configuration
    suspend fun addClient(values: Map<String, String>): String {
        val conf = state().server(values["server"])
        val pki = conf.pki ?: throw noPki(conf)
        val name = values["name"].orEmpty()
        if (!CLIENT_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (name == conf.serverCertificate() || conf.certificates().any { it.name == name && it.valid }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "${conf.name} has the client $name already")
        }
        val changes = values.filterKeys { it in CLIENT_SETTINGS }
        changes["address"]?.takeIf { it.isNotEmpty() }?.let { checkAddress(conf, it) }
        root.masked(easyrsa(pki, "build-client-full ${quote(name)} nopass"))
        ccd(conf, name, changes)
        return "${conf.name}/$name"
    }

    // a client turned off loses its live sessions as well
    suspend fun setClient(id: String?, changes: Map<String, String?>) {
        val (conf, name) = state().client(id)
        val wanted = changes.filterKeys { it in CLIENT_SETTINGS }
        wanted["address"]?.takeIf { it.isNotEmpty() }?.let { checkAddress(conf, it) }
        ccd(conf, name, wanted)
        if (wanted["disabled"] in ValueType.Flag.on) endSessions(conf, name)
    }

    // a client goes revoked, with the CRL made again where its server reads it, its file of client configuration and its sessions
    suspend fun removeClients(ids: List<String>) {
        val host = state()
        for ((conf, names) in ids.map(host::client).groupBy({ it.first }, { it.second })) {
            val pki = conf.pki ?: throw noPki(conf)
            val valid = names.filter { name -> conf.certificates().any { it.name == name && it.valid } }
            val crl = conf.directive("crl-verify")?.split(BLANKS)?.first()?.let(conf::path)?.takeIf { it != "$pki/crl.pem" }
            val folder = conf.directive("client-config-dir")?.let(conf::path)
            root.masked(
                listOfNotNull(
                    easyrsa(pki, *(valid.map { "revoke ${quote(it)}" } + "gen-crl").toTypedArray()),
                    crl?.let { "install -m 644 ${quote("$pki/crl.pem")} ${quote(it)}" },
                    folder?.let { dir -> "rm -f " + names.joinToString(" ") { quote("$dir/$it") } },
                ).joinToString(" && "),
            )
            for (name in valid) endSessions(conf, name)
        }
    }

    // the file of client configuration keeps the fixed address of a client and whether it is turned off, beside what else it holds
    private suspend fun ccd(conf: OvpnConf, name: String, changes: Map<String, String?>) {
        val old = conf.ccd[name].orEmpty().filter { it.isNotBlank() }
        var lines = old
        changes["address"]?.let { address ->
            lines = lines.filterNot { it.trim().startsWith("ifconfig-push") } + listOfNotNull(address.ifEmpty { null }?.let { "ifconfig-push $it ${conf.mask()}" })
        }
        changes["disabled"]?.let { off -> lines = lines.filterNot { it.trim() == "disable" } + listOfNotNull("disable".takeIf { off in ValueType.Flag.on }) }
        if (lines == old) return
        val dir = conf.directive("client-config-dir")?.let(conf::path) ?: throw DeviceException(ErrorCategory.Unsupported, "${conf.name} keeps no files of client configuration")
        if (lines.isEmpty()) root.masked("rm -f ${quote("$dir/$name")}") else root.write("$dir/$name", lines.joinToString("\n"), "true", "644")
    }

    // a fixed address goes by ifconfig-push, which takes an address and the mask of the network with topology subnet
    private fun checkAddress(conf: OvpnConf, address: String) {
        val network = conf.directive("server")?.words(2)?.let(Ipv4::parseSubnet)
        if (conf.directive("topology") != "subnet" || network == null) throw DeviceException(ErrorCategory.Unsupported, "${conf.name} gives fixed addresses only with topology subnet")
        val value = Ipv4.parse(address)
        if (value == null || value and network.mask != network.network) throw DeviceException(ErrorCategory.ArgumentValue, "address: $address is not a value it takes")
    }

    // the live sessions of a client end through the management interface of its server; a server not running has none
    private suspend fun endSessions(conf: OvpnConf, name: String) {
        if (!conf.running || conf.directive("management") == null) return
        val answer = root.manage(conf, "kill $name").lines()
        if (answer.none { it.startsWith("SUCCESS:") || it.startsWith("ERROR: common name") }) {
            throw DeviceException(ErrorCategory.Api, answer.firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: "OpenVPN did not end the session ${conf.name}/$name")
        }
    }

    // the configuration of a client with its certificate, its key and the key of tls-crypt or tls-auth inline, to the server at the
    // address its settings give clients, else as this session reaches it
    suspend fun clientConfig(arguments: Map<String, String>): List<Item> {
        val (conf, name) = state().client(arguments["number"])
        val pki = conf.pki ?: throw noPki(conf)
        if (conf.directive("tls-crypt-v2") != null) throw DeviceException(ErrorCategory.Unsupported, "${conf.name} uses tls-crypt-v2, whose keys of clients are not made here")
        val tls = listOf("tls-crypt", "tls-auth").firstNotNullOfOrNull { kind -> conf.directive(kind)?.let { kind to conf.path(it.split(BLANKS).first()) } }
        val parts = root.masked(
            "cat ${quote("$pki/ca.crt")} && echo $PART && sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' ${quote("$pki/issued/$name.crt")} && " +
                "echo $PART && cat ${quote("$pki/private/$name.key")}" + tls?.let { " && echo $PART && cat ${quote(it.second)}" }.orEmpty(),
        ).split("$PART\n").map { it.trim() }
        val remote = conf.clientRemote() ?: localAddress(ssh)
        val lines = buildList {
            add("client")
            add("dev ${if (conf.directive("dev").orEmpty().startsWith("tap")) "tap" else "tun"}")
            add("proto ${if (conf.directive("proto").orEmpty().startsWith("tcp")) "tcp" else "udp"}")
            if (remote.isNotEmpty()) add("remote $remote ${conf.directive("port") ?: "1194"}")
            addAll(listOf("resolv-retry infinite", "nobind", "persist-key", "persist-tun", "remote-cert-tls server"))
            for (kept in listOf("auth", "cipher", "compress", "comp-lzo")) conf.directive(kept)?.let { add("$kept $it".trim()) }
            add("verb 3")
            for ((at, tag) in listOf("ca", "cert", "key").withIndex()) addAll(listOf("<$tag>", parts.getOrElse(at) { "" }, "</$tag>"))
            tls?.let { (kind, _) ->
                if (kind == "tls-auth") add("key-direction 1")
                addAll(listOf("<$kind>", parts.getOrElse(3) { "" }, "</$kind>"))
            }
        }
        return listOf(Item("", mapOf("conf" to lines.joinToString("\n"))))
    }

    // a connection of the host from an .ovpn already on it, which root alone reads once copied in; a login and its password go to a
    // file of their own beside it
    private suspend fun addConnection(values: Map<String, String>): String {
        val source = values["import"].orEmpty()
        if (source.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "import: a value is needed")
        if (!PATH.matches(source)) throw DeviceException(ErrorCategory.ArgumentValue, "import: $source is not a value it takes")
        val conf = connection(values, "import: $source is the configuration of a server") { OvpnText(root.masked("cat ${quote(source)}")) }
        if (values["autostart"] in ValueType.Flag.on) autostart(conf, true)
        return conf.name
    }

    // the configuration of a client pasted or read from a file; it starts at once under the guard, as redirect-gateway takes the routes
    // of the host, and at boot only once the guard let it start. The way to the end of this session keeps outside the tunnel by a route
    // of its own through the gateway the host had
    private suspend fun importConnection(values: Map<String, String>): List<Item> {
        // net_gateway is the gateway of the default route, so a way without a gateway stays as it is
        val way = if (values["keep-session"] in ValueType.Flag.on) sessionWay(ssh)?.takeIf { ':' !in it.address && it.via != null } else null
        val conf = connection(values, "text: this is the configuration of a server") {
            OvpnText(values["text"].orEmpty()).also { text -> way?.let { text.set("route", text.values("route") + "route ${it.address} 255.255.255.255 net_gateway") } }
        }
        val systemd = systemd()
        guard.guarded(conf.backup(systemd)) { start(conf, conf.start(systemd)) }
        if (values["autostart"] in ValueType.Flag.on) autostart(conf, true)
        return listOf(Item(conf.name, mapOf("name" to conf.name)))
    }

    // a connection of the host from the text of an .ovpn, written where root alone reads it, with a login and its password in a file of
    // their own beside it; the text of a server or of no remote is no connection
    private suspend fun connection(values: Map<String, String>, server: String, read: suspend () -> OvpnText): OvpnConf {
        val name = values["name"].orEmpty()
        if (!CONNECTION_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val host = state()
        if (host.confs.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an OpenVPN configuration $name already")
        val user = values["user"].orEmpty()
        val password = values["password"].orEmpty()
        checkLogin(user, password)
        val systemd = systemd()
        if (!systemd && !host.openRc) throw noService()
        val text = read()
        if (SERVER_KEYS.any { text.values(it).isNotEmpty() } || text.values("mode").any { it.trim().split(BLANKS).getOrNull(1) == "server" }) {
            throw DeviceException(ErrorCategory.ArgumentValue, server)
        }
        if (text.values("remote").isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the configuration names no server by remote")
        val file = if (systemd) "$CLIENT_DIR/$name.conf" else "$OPENVPN_DIR/$name.conf"
        if (user.isNotEmpty()) text.set("auth-user-pass", listOf("auth-user-pass ${login(file)}"))
        root.write(file, text.text(), "true")
        if (user.isNotEmpty()) root.write(login(file), "$user\n$password", "true")
        return OvpnConf(name, "inactive", false, file, false).also { it.lines += text.lines }
    }

    // what a new server takes unless the user says otherwise: the next free name, the first free port of UDP from 1194, the first network
    // 10.N.0.0/24 nothing of the host overlaps, the address of the interface of the default route for clients, DNS and the default route
    // for them, NAT, a start at boot and a first client
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan(root)
        val ports = scan.ports + host.servers.mapNotNull { it.directive("port")?.toIntOrNull() }
        val taken = scan.networks + host.servers.mapNotNull { conf -> conf.directive("server")?.let(Ipv4::parseSubnet) }
        return Item(
            "",
            linkedMapOf(
                "name" to freeName(host, "server"), "port" to "${(1194..65535).first { it !in ports }}", "proto" to "udp", "client-remote" to scan.address,
                "autostart" to "true", "network" to freeNetwork(taken)?.cidr.orEmpty(), "dns" to CLIENT_DNS, "redirect-gateway" to "true", "nat" to "true",
                "client-to-client" to "false", "client" to "client1",
            ),
        )
    }

    // a server in one step: the server as a new one is made, then its first client, whose configuration comes back
    private suspend fun create(values: Map<String, String>): List<Item> {
        val name = values["name"].orEmpty()
        val client = values["client"].orEmpty()
        if (client.isNotEmpty() && !CLIENT_NAME.matches(client)) throw DeviceException(ErrorCategory.ArgumentValue, "client: $client is not a value it takes")
        addServer(values)
        if (client.isEmpty()) return listOf(Item(name, mapOf("name" to name)))
        addClient(mapOf("server" to name, "name" to client))
        return listOf(Item(name, mapOf("name" to name) + clientConfig(mapOf("number" to "$name/$client")).single().values))
    }

    // an empty password leaves the one the file has; a connection without a login loses the file
    private suspend fun setConnection(id: String?, changes: Map<String, String?>) {
        val conf = state().connection(id)
        val user = changes["user"]
        val password = changes["password"]?.ifEmpty { null }
        if (user != null || password != null) {
            val file = conf.directive("auth-user-pass")?.takeIf { it.isNotEmpty() }?.let(conf::path) ?: login(conf.file)
            val old = root.exec("cat ${quote(file)}").takeIf { it.exitStatus == 0 }?.output.orEmpty().lines()
            val name = user ?: old.first()
            val secret = password ?: old.getOrElse(1) { "" }
            checkLogin(name, secret)
            if (name.isEmpty()) root.masked("rm -f ${quote(file)}") else root.write(file, "$name\n$secret", "true")
            change(conf) { text -> text.set("auth-user-pass", listOfNotNull(name.ifEmpty { null }?.let { "auth-user-pass $file" })) }
        }
        changes["autostart"]?.let { autostart(conf, it in ValueType.Flag.on) }
    }

    private suspend fun removeConnections(ids: List<String>) {
        val host = state()
        val systemd = systemd()
        for (conf in ids.map(host::connection)) root.masked("${conf.drop(systemd)} && rm -f ${quote(login(conf.file))}")
    }
}

// an OpenVPN configuration as the read finds it: a server or a client connection of the host, the state of its service and whether
// it starts at boot, its lines and the status file it wrote last; read in full, a server brings the certificates of its PKI of
// easy-rsa and its files of client configuration, and a connection the login of its file of auth-user-pass
internal class OvpnConf(val name: String, val state: String, val autostart: Boolean, val file: String, val server: Boolean) {
    val lines = mutableListOf<String>()
    var status: MutableList<String>? = null
    var pki: String? = null
    val index = mutableListOf<String>()
    val ccd = linkedMapOf<String, MutableList<String>>()
    var user: String? = null

    val directives: Map<String, List<String>> by lazy { directivesOf(lines) }

    val running get() = state == "active" || state == "started"

    fun directive(name: String) = directives[name]?.firstOrNull()

    // a path of the configuration, from the folder it is in, where its service runs
    fun path(value: String) = if (value.startsWith("/")) value else "${file.substringBeforeLast('/')}/$value"

    // what push hands clients of one kind: dhcp-option dns, route, redirect-gateway
    fun pushed(kind: String) = directives["push"].orEmpty().map { it.trim().removeSurrounding("\"").trim() }
        .filter { it.lowercase() == kind || it.lowercase().startsWith("$kind ") }

    fun network() = directive("server")?.let { Ipv4.parseSubnet(it.words(2))?.cidr ?: it }.orEmpty()

    fun mask() = directive("server")?.split(BLANKS)?.getOrNull(1)

    fun networks() = listOfNotNull(directive("server")?.let { Ipv4.parseSubnet(it.words(2))?.cidr }, directive("server-ipv6"))

    fun clientRemote() = lines.firstNotNullOfOrNull { CLIENT_REMOTE.matchEntire(it.trim())?.groupValues?.get(1) }

    fun serverCertificate() = directive("cert")?.substringAfterLast('/')?.removeSuffix(".crt")

    fun certificates() = index.mapNotNull(::certificate)
}

// a certificate as a line of index.txt of OpenSSL has it: V, R or E, when it runs out and when it was revoked, its serial and its
// subject, whose CN is the name of the client
internal class OvpnCertificate(val status: String, val expires: String, val revoked: String, val serial: String, val name: String) {
    val valid get() = status == "V"
}

// what the host has of OpenVPN at one read in full: its configurations, the devices whose NAT ufw or firewalld keeps, the servers
// whose NAT a unit of its own puts in, and whether OpenRC starts OpenVPN
internal class OpenVpnState(val confs: List<OvpnConf>, val nat: Set<String>, val openRc: Boolean, val hooked: Set<String> = emptySet()) {
    val servers: List<OvpnConf> get() = confs.filter { it.server }

    val connections: List<OvpnConf> get() = confs.filter { !it.server }

    fun server(id: String?) = servers.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no OpenVPN server $id")

    fun connection(id: String?) = connections.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no OpenVPN connection $id")

    // a client by its server and its name; the certificate of the server is none
    fun client(id: String?): Pair<OvpnConf, String> {
        val conf = server(id?.substringBefore('/'))
        val name = id?.substringAfter('/', "").orEmpty()
        if (name == conf.serverCertificate() || conf.certificates().none { it.name == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no OpenVPN client $id")
        return conf to name
    }

    fun natted(conf: OvpnConf) = conf.directive("dev") in nat || conf.name in hooked

    fun serverRows(): List<Item> = servers.map { conf ->
        Item(
            conf.name,
            linkedMapOf(
                "name" to conf.name, "state" to conf.state, "autostart" to "${conf.autostart}", "port" to conf.directive("port").orEmpty(),
                "proto" to conf.directive("proto").orEmpty(), "dev" to conf.directive("dev").orEmpty(), "network" to conf.network(),
                "network6" to conf.directive("server-ipv6").orEmpty(), "dns" to conf.pushed("dhcp-option dns").joinToString(", ") { it.substringAfterLast(' ') },
                "redirect-gateway" to "${conf.pushed("redirect-gateway").isNotEmpty()}",
                "push-routes" to (conf.pushed("route").map { Ipv4.parseSubnet(it.substringAfter(' ').words(2))?.cidr ?: it } + conf.pushed("route-ipv6").map { it.substringAfter(' ') })
                    .joinToString(", "),
                "client-to-client" to "${conf.directive("client-to-client") != null}", "duplicate-cn" to "${conf.directive("duplicate-cn") != null}",
                "max-clients" to conf.directive("max-clients").orEmpty(), "data-ciphers" to (conf.directive("data-ciphers") ?: conf.directive("ncp-ciphers")).orEmpty(),
                "tls" to listOf("tls-crypt-v2", "tls-crypt", "tls-auth").firstOrNull { conf.directive(it) != null }.orEmpty(),
                "keepalive" to conf.directive("keepalive").orEmpty(), "user" to conf.directive("user").orEmpty(), "group" to conf.directive("group").orEmpty(),
                "verb" to conf.directive("verb").orEmpty(), "client-remote" to conf.clientRemote().orEmpty(), "nat" to "${natted(conf)}",
                "management" to conf.directive("management").orEmpty(), "clients" to "${conf.certificates().count { it.valid && it.name != conf.serverCertificate() }}",
                "pki" to conf.pki.orEmpty(), "file" to conf.file,
            ),
        )
    }

    // a name issued again after its revocation shows its valid certificate
    fun clientRows(): List<Item> = servers.flatMap { conf ->
        conf.certificates().filter { it.name != conf.serverCertificate() }.groupBy { it.name }.map { (name, issued) ->
            val certificate = issued.lastOrNull { it.valid } ?: issued.last()
            val ccd = conf.ccd[name].orEmpty().map { it.trim() }
            Item(
                "${conf.name}/$name",
                linkedMapOf(
                    "server" to conf.name, "name" to name, "state" to (STATES[certificate.status] ?: certificate.status), "expires" to certificate.expires,
                    "revoked" to certificate.revoked, "serial" to certificate.serial, "disabled" to "${"disable" in ccd}",
                    "address" to ccd.firstNotNullOfOrNull { line -> line.split(BLANKS).takeIf { it.first() == "ifconfig-push" }?.getOrNull(1) }.orEmpty(),
                ),
            )
        }
    }

    fun connectionRows(): List<Item> = connections.map { conf ->
        Item(
            conf.name,
            linkedMapOf(
                "name" to conf.name, "state" to conf.state, "autostart" to "${conf.autostart}", "remote" to conf.directives["remote"].orEmpty().joinToString(", "),
                "proto" to conf.directive("proto").orEmpty(), "dev" to conf.directive("dev").orEmpty(), "user" to conf.user.orEmpty(), "password" to "", "file" to conf.file,
            ),
        )
    }
}

// the output of a read: each configuration after its line, with the sections a read in full adds after their lines of their own
internal fun parseOpenVpn(text: String): OpenVpnState {
    val confs = mutableListOf<OvpnConf>()
    val nat = mutableSetOf<String>()
    val hooked = mutableSetOf<String>()
    var openRc = false
    var body: MutableList<String>? = null
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        if (!mark.startsWith("--nh-")) {
            body?.add(line)
            continue
        }
        body = null
        val rest = line.substringAfter(' ', "").trim()
        val conf = confs.lastOrNull()
        when (mark) {
            "--nh-ovpn--", "--nh-ovpn-client--" -> {
                val head = rest.split(' ', limit = 4)
                body = OvpnConf(head[0], head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", head.getOrElse(3) { "" }, mark == "--nh-ovpn--")
                    .also { confs += it }.lines
            }
            "--nh-ovpn-status--" -> body = conf?.let { mutableListOf<String>().also { lines -> it.status = lines } }
            "--nh-ovpn-pki--" -> body = conf?.also { it.pki = rest }?.index
            "--nh-ovpn-ccd--" -> body = conf?.let { mutableListOf<String>().also { lines -> it.ccd[rest] = lines } }
            "--nh-ovpn-user--" -> conf?.user = rest
            "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            "--nh-ovpn-openrc--" -> openRc = true
            "--nh-ovpn-hook--" -> hooked += rest
        }
    }
    return OpenVpnState(confs, nat, openRc, hooked)
}

// a command to the management interface of a server and its answer; the password the interface asks for goes from its file on the
// host, and socat reaches a socket of unix, else nc where it has -U
internal suspend fun Root.manage(server: OvpnConf, command: String): String {
    val management = server.directive("management")?.split(BLANKS) ?: throw DeviceException(ErrorCategory.Unsupported, "${server.name} has no management interface to end a session with")
    val password = management.getOrNull(2)?.let { "head -n 1 ${quote(server.path(it))}; " }.orEmpty()
    val connect = if (management.getOrNull(1) == "unix") {
        val socket = quote(server.path(management[0]))
        "if command -v socat >/dev/null 2>&1; then socat - UNIX-CONNECT:$socket; else nc -U $socket; fi"
    } else {
        "nc ${quote(management[0])} ${quote(management.getOrElse(1) { "" })}"
    }
    return masked("{ ${password}printf '%s\\nquit\\n' ${quote(command)}; } | { $connect; }")
}

// the servers among the configurations of openvpn-server@ and openvpn@ with their lines that hold no key and the status file each
// wrote last, by its directive or the one the unit gives; read in full, the configurations come whole with the blocks of keys
// emptied, with the certificates of a PKI of easy-rsa beside their CA and their files of client configuration, and the client
// connections of openvpn-client@ and openvpn@ come with the login of their file of auth-user-pass, its password unread
private val OPENVPN_SCRIPT = $$"""echo "--nh-zone-- $(date +%z)"
m() { awk '{ l = tolower($0); sub(/^[ \t]+/, "", l) } h && l ~ /^<\/(key|tls-crypt|tls-crypt-v2|tls-auth|secret|pkcs12|auth-user-pass)>/ { h = 0 } !h { print } l ~ /^<(key|tls-crypt|tls-crypt-v2|tls-auth|secret|pkcs12|auth-user-pass)>/ { h = 1 }' "$1"; }
a() { awk -v k="$1" 'tolower($1) == k { print $2; exit }' "$2"; }
r() { case "$1" in ""|/*) echo "$1";; *) echo "$2/$1";; esac; }
s() { x=$(systemctl is-active "$1" 2>/dev/null); [ -n "$x" ] || x=$(rc-service "$2" status 2>/dev/null | sed -n 's/.*status: *//p'); y=$(systemctl is-enabled "$1" 2>/dev/null); [ ! -e "/etc/runlevels/default/$2" ] || y=enabled; echo "${x:-unknown} ${y:-disabled}"; }
for c in /etc/openvpn/server/*.conf /etc/openvpn/*.conf; do
[ -f "$c" ] || continue
grep -qiE '^[[:space:]]*(server|server-bridge|mode[[:space:]]+server)([[:space:]]|$)' "$c" || continue
n=${c##*/}; n=${n%.conf}; w=${c%/*}
case "$c" in /etc/openvpn/server/*) u=openvpn-server@$n; d=/run/openvpn-server/status-$n.log;; *) u=openvpn@$n; d=/run/openvpn/$n.status;; esac
echo "--nh-ovpn-- $n $(s "$u" "openvpn.$n") $c"
if [ -n "$full" ]; then
m "$c"
p=$(r "$(a ca "$c")" "$w"); p=${p%/*}
[ -z "$p" ] || [ ! -f "$p/index.txt" ] || { echo "--nh-ovpn-pki-- $p"; cat "$p/index.txt"; }
p=$(r "$(a client-config-dir "$c")" "$w")
[ -z "$p" ] || for f in "$p"/*; do [ -f "$f" ] && { echo "--nh-ovpn-ccd-- ${f##*/}"; cat "$f"; }; done
else
grep -iE '^[[:space:]]*(port|proto|dev|server|server-ipv6|status|management)[[:space:]]' "$c"
fi
p=$(r "$(a status "$c")" "$w")
for f in "$p" "$d"; do [ -n "$f" ] && [ -f "$f" ] && { echo "--nh-ovpn-status-- $f"; cat "$f"; break; }; done
done
[ -z "$full" ] || for c in /etc/openvpn/client/*.conf /etc/openvpn/*.conf; do
[ -f "$c" ] || continue
grep -qiE '^[[:space:]]*(server|server-bridge|mode[[:space:]]+server)([[:space:]]|$)' "$c" && continue
n=${c##*/}; n=${n%.conf}; w=${c%/*}
case "$c" in /etc/openvpn/client/*) u=openvpn-client@$n;; *) u=openvpn@$n;; esac
echo "--nh-ovpn-client-- $n $(s "$u" "openvpn.$n") $c"
m "$c"
p=$(r "$(a auth-user-pass "$c")" "$w")
[ -z "$p" ] || [ ! -f "$p" ] || echo "--nh-ovpn-user-- $(head -n 1 "$p")"
done
true"""

// what the panel reads every few seconds: the servers by their directives and their status files
internal val OPENVPN_STATE = "full=\n$OPENVPN_SCRIPT"

// the read in full, with the servers whose NAT a unit of its own puts in
private val OPENVPN_FILES = "full=1\n$OPENVPN_SCRIPT\n$NAT_STATE\n[ ! -e /etc/init.d/openvpn ] || echo --nh-ovpn-openrc--\n" +
    $$"""for c in $$UNITS/$$NAT_UNIT*.service; do [ -f "$c" ] && { n=${c#$$UNITS/$$NAT_UNIT}; echo "--nh-ovpn-hook-- ${n%.service}"; }; done
true"""

// the lines of the journal of OpenVPN that tell of a client coming and going, over the last month
internal const val OPENVPN_HISTORY = "date +%z; echo --nh-section--; journalctl -q -o json --no-pager --since '31 days ago' _COMM=openvpn 2>/dev/null | " +
    "grep -E 'Peer Connection Initiated|MULTI_sva|client-instance' | tail -n 20000; true"

// the clients a status file lists, by the titles OpenVPN gives its columns: the CLIENT_LIST lines of versions 2 and 3, named by their
// HEADER, or the client list of version 1 with the virtual address its routing table gives
internal fun parseOpenVpnStatus(text: String): List<Map<String, String>> {
    val lines = text.lines().map { it.trimEnd('\r') }
    val header = lines.firstOrNull { it.startsWith("HEADER") && "CLIENT_LIST" in it }
    if (header != null) {
        val separator = if ('\t' in header) '\t' else ','
        val titles = header.split(separator).drop(2)
        return lines.filter { it.startsWith("CLIENT_LIST$separator") }.map { line -> titles.zip(line.split(separator).drop(1)).toMap() }
    }
    val list = lines.indexOfFirst { it.startsWith("Common Name,") }
    if (list < 0) return emptyList()
    val titles = lines[list].split(',')
    val routing = lines.indexOf("ROUTING TABLE")
    val ends = setOf("ROUTING TABLE", "GLOBAL STATS", "END")
    val clients = lines.drop(list + 1).takeWhile { it !in ends }.filter { it.isNotBlank() }.map { titles.zip(it.split(',')).toMap() }
    val routes = if (routing < 0) emptyList() else lines.drop(routing + 2).takeWhile { it !in ends }.filter { it.isNotBlank() }.map { it.split(',') }
    return clients.map { client ->
        client + ("Virtual Address" to routes.firstOrNull { it.getOrNull(1) == client["Common Name"] && it.getOrNull(2) == client["Real Address"] }?.firstOrNull().orEmpty())
    }
}

// the sessions the journal of OpenVPN tells of, newest first: a client comes in by its address, gets its address in the tunnel and
// leaves with a signal and its reason; a session without its end is still on, or the server stopped without saying. The journal
// counts no bytes
internal fun parseOpenVpnJournal(text: String, offset: String): List<Item> {
    val open = linkedMapOf<String, MutableMap<String, String>>()
    val closed = mutableListOf<Map<String, String>>()
    for (line in text.lines()) {
        val entry = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: continue
        val seconds = (entry["__REALTIME_TIMESTAMP"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.div(1_000_000) ?: continue
        val message = (entry["MESSAGE"] as? JsonPrimitive)?.contentOrNull ?: continue
        val unit = (entry["_SYSTEMD_UNIT"] as? JsonPrimitive)?.contentOrNull.orEmpty().removeSuffix(".service")
        val service = unit.substringAfter('@', unit).ifEmpty { "openvpn" }
        CAME.find(message)?.let { match ->
            val (address, user) = match.destructured
            open.remove("$service/$user/$address")?.let { closed += it }
            open["$service/$user/$address"] = mutableMapOf("service" to service, "user" to user, "endpoint" to address, "start" to "$seconds")
        }
        POOLED.find(message)?.let { match -> open["$service/${match.groupValues[1]}/${match.groupValues[2]}"]?.put("address", match.groupValues[3]) }
        LEFT.find(message)?.let { match ->
            open.remove("$service/${match.groupValues[1]}/${match.groupValues[2]}")?.let { session ->
                closed += session + mapOf("end" to "$seconds", "reason" to match.groupValues[3])
            }
        }
    }
    return vpnJournalRows("OpenVPN", closed + open.values, offset)
}

// the rows of the sessions a journal tells of, newest first: their start and end in seconds, the journal counts no bytes
internal fun vpnJournalRows(kind: String, sessions: List<Map<String, String>>, offset: String): List<Item> =
    sessions.sortedByDescending { it["start"]?.toLongOrNull() ?: 0L }.map { session ->
        val start = session["start"]?.toLongOrNull() ?: 0L
        val end = session["end"]?.toLongOrNull()
        val user = session["user"].orEmpty()
        Item(
            "${session["service"]}/$start/$user/${session["endpoint"]}",
            linkedMapOf(
                "kind" to kind,
                "service" to session["service"].orEmpty(),
                "user" to user,
                "endpoint" to session["endpoint"].orEmpty(),
                "address" to session["address"].orEmpty(),
                "connected" to local(start, offset),
                "disconnected" to end?.let { local(it, offset) }.orEmpty(),
                "duration" to end?.let { duration((it - start).coerceAtLeast(0)) }.orEmpty(),
                "reason" to session["reason"].orEmpty(),
                "rx" to "",
                "tx" to "",
                "start" to "$start",
                "seconds" to end?.let { "${(it - start).coerceAtLeast(0)}" }.orEmpty(),
            ),
        )
    }

// a configuration of OpenVPN line by line, each kept as written: a directive goes by its name, a pushed option by push and its own
// name, and the comment with the address clients reach the server at by #client-remote
private class OvpnText(text: String) {
    val lines = text.lines().toMutableList()

    fun text() = lines.joinToString("\n")

    fun values(key: String): List<String> {
        val keys = keys()
        return lines.filterIndexed { at, _ -> keys[at] == key }
    }

    // the lines of a key replaced by new ones at the place of the first, or after the last directive, before the inline blocks
    fun set(key: String, values: List<String>) {
        val keys = keys()
        val first = keys.indexOf(key)
        val at = if (first >= 0) first else keys.indexOfLast { it != null } + 1
        val kept = lines.filterIndexed { line, _ -> keys[line] != key }
        lines.clear()
        lines += kept
        lines.addAll(at - (0 until at).count { keys[it] == key }, values)
    }

    private fun keys(): List<String?> {
        var block = false
        return lines.map { line ->
            val text = line.trim()
            when {
                text.startsWith("</") -> {
                    block = false
                    null
                }
                text.startsWith("<") -> {
                    block = true
                    null
                }
                block -> null
                CLIENT_REMOTE.matches(text) -> "#client-remote"
                text.isEmpty() || text[0] == '#' || text[0] == ';' -> null
                else -> key(text)
            }
        }
    }

    private fun key(text: String): String {
        val name = text.split(BLANKS).first().lowercase()
        if (name != "push") return name
        val pushed = text.substringAfter(' ').trim().removeSurrounding("\"").trim().split(BLANKS)
        val option = pushed.first().lowercase()
        return if (option == "dhcp-option") "push dhcp-option ${pushed.getOrElse(1) { "" }.lowercase()}" else "push $option"
    }
}

// a setting of a server as the directives OpenVPN takes; an empty value, or no, takes them out
private fun OvpnText.put(field: String, value: String) {
    val on = value in ValueType.Flag.on
    when (field) {
        "network" -> set("server", listOfNotNull(Ipv4.parseSubnet(value)?.takeIf { value.isNotEmpty() }?.let { "server ${Ipv4.format(it.network)} ${Ipv4.format(it.mask)}" }))
        // the default route of IPv6 goes to clients with the network of IPv6 and leaves with it
        "network6" -> {
            set("server-ipv6", listOfNotNull(value.ifEmpty { null }?.let { "server-ipv6 $it" }))
            if (values("push redirect-gateway").isNotEmpty()) put("redirect-gateway", "yes")
        }
        "dns" -> set("push dhcp-option dns", items(value).map { "push \"dhcp-option DNS $it\"" })
        "redirect-gateway" -> set(
            "push redirect-gateway",
            if (on) listOf("push \"redirect-gateway def1${if (values("server-ipv6").isNotEmpty()) " ipv6" else ""} bypass-dhcp\"") else emptyList(),
        )
        "push-routes" -> {
            val (six, four) = items(value).partition { ':' in it }
            set("push route", four.mapNotNull(Ipv4::parseSubnet).map { "push \"route ${Ipv4.format(it.network)} ${Ipv4.format(it.mask)}\"" })
            set("push route-ipv6", six.map { "push \"route-ipv6 $it\"" })
        }
        "client-to-client", "duplicate-cn" -> set(field, if (on) listOf(field) else emptyList())
        "client-remote" -> set("#client-remote", listOfNotNull(value.ifEmpty { null }?.let { "# ClientRemote = $it" }))
        "proto" -> {
            set("proto", listOfNotNull(value.ifEmpty { null }?.let { "proto $it" }))
            if (value.startsWith("tcp")) set("explicit-exit-notify", emptyList())
        }
        else -> set(field, listOfNotNull(value.ifEmpty { null }?.let { "$field $it" }))
    }
}

private fun checkServer(field: String, value: String) {
    val valid = when (field) {
        "port", "max-clients" -> value.toIntOrNull() in 1..65535
        "verb" -> value.toIntOrNull() in 0..11
        "proto" -> value in PROTOCOLS
        "network" -> Ipv4.parseSubnet(value)?.takeIf { '/' in value }?.prefix in 8..30
        "network6" -> NETWORK6.matches(value)
        "dns" -> items(value).all(HOST::matches)
        "push-routes" -> items(value).all { if (':' in it) NETWORK6.matches(it) else '/' in it && Ipv4.parseSubnet(it) != null }
        "data-ciphers" -> CIPHERS.matches(value)
        "keepalive" -> KEEPALIVE.matches(value)
        "user", "group" -> ACCOUNT.matches(value)
        "client-remote" -> HOST.matches(value)
        else -> value in ValueType.Flag.on || value in OFF
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

// a line break would make a login and its password three lines of the file
private fun checkLogin(user: String, password: String) {
    if (user.any { it.isISOControl() } || user.length > 256) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
    if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
}

// what the service of a configuration is: by its folder a unit of systemd, or openvpn.<name> of OpenRC, which reads /etc/openvpn/<name>.conf
private fun OvpnConf.unitName() = when {
    file.startsWith("$SERVER_DIR/") -> "openvpn-server@$name"
    file.startsWith("$CLIENT_DIR/") -> "openvpn-client@$name"
    else -> "openvpn@$name"
}

private fun OvpnConf.unit() = quote(unitName())

private fun OvpnConf.natUnit() = "$NAT_UNIT$name.service"

private fun OvpnConf.service() = quote("openvpn.$name")

// what the port of a server opened apart from its NAT goes by, and the port as 1194/udp
private fun OvpnConf.mark() = "openvpn-$name"

private fun OvpnConf.port() = "${directive("port") ?: "1194"}/${if (directive("proto").orEmpty().startsWith("tcp")) "tcp" else "udp"}"

private fun OvpnConf.start(systemd: Boolean) = if (systemd) "systemctl start ${unit()}" else "ln -sf openvpn /etc/init.d/${service()} && rc-service ${service()} start"

private fun OvpnConf.restart(systemd: Boolean) = if (systemd) "systemctl restart ${unit()}" else "rc-service ${service()} restart"

private fun OvpnConf.stop(systemd: Boolean) = if (systemd) "systemctl stop ${unit()}" else "rc-service ${service()} stop"

private fun OvpnConf.active(systemd: Boolean) = if (systemd) "systemctl is-active -q ${unit()}" else "rc-service ${service()} status >/dev/null 2>&1"

private fun OvpnConf.enable(systemd: Boolean) = if (systemd) "systemctl enable ${unit()}" else "ln -sf openvpn /etc/init.d/${service()} && rc-update add ${service()} default"

private fun OvpnConf.disable(systemd: Boolean) = if (systemd) "systemctl disable ${unit()}" else "rc-update del ${service()} default"

private fun OvpnConf.journal(systemd: Boolean) = if (systemd) "journalctl -u ${unit()} -n 12 --no-pager -o cat" else "grep -i openvpn /var/log/messages 2>/dev/null | tail -n 12"

// a configuration and its service gone, whether or not it ran
private fun OvpnConf.drop(systemd: Boolean) =
    "{ ${stop(systemd)}; ${disable(systemd)}; true; } >/dev/null 2>&1 && rm -f ${quote(file)}" + if (systemd) "" else " /etc/init.d/${service()}"

// what the guard keeps of a configuration: its file and whether its service ran; taking a change back stops the service, puts the file
// back and starts it again where it ran
private fun OvpnConf.backup(systemd: Boolean): LinuxGuard.Backup {
    val conf = quote(file)
    return LinuxGuard.Backup(
        "vpn",
        "if [ -e $conf ]; then cp -p $conf \"\$B/conf\"; fi; if ${active(systemd)}; then touch \"\$B/up\"; fi",
        "${stop(systemd)}; if [ -e \"\$1/conf\" ]; then cp -p \"\$1/conf\" $conf; fi; if [ -e \"\$1/up\" ]; then ${start(systemd)}; fi",
    )
}

// easy-rsa 3 wherever the distribution puts it, in batch mode on one PKI: keys of EC, certificates and the CRL good for ten years, as a
// CRL that runs out locks every client out. Version 3.0 looks for its x509-types beside its script, which is off the path there. The
// name of the CA goes as --req-cn to build-ca alone: 3.1 refuses build-server-full and build-client-full with EASYRSA_REQ_CN set
private fun easyrsa(pki: String, vararg steps: String) =
    "umask 077; e=\$(command -v easyrsa 2>/dev/null || ls /usr/share/easy-rsa/easyrsa /usr/share/easy-rsa/3/easyrsa 2>/dev/null | head -n 1); " +
        "[ -n \"\$e\" ] || { echo 'the host has no easy-rsa' >&2; exit 1; }; case \"\$e\" in /usr/share/*) export EASYRSA=\"\${e%/*}\";; esac; " +
        "export EASYRSA_BATCH=1 EASYRSA_PKI=${quote(pki)} EASYRSA_ALGO=ec EASYRSA_CURVE=prime256v1 EASYRSA_CERT_EXPIRE=3650 EASYRSA_CRL_DAYS=3650 && " +
        steps.joinToString(" && ") { "\"\$e\" $it >/dev/null" }

// a line of index.txt: status, expiry, revocation with its reason, serial, file name and subject, by tabs
private fun certificate(line: String): OvpnCertificate? {
    val fields = line.split('\t')
    if (fields.size < 6) return null
    return OvpnCertificate(fields[0], utc(fields[1]), utc(fields[2].substringBefore(',')), fields[3], fields[5].substringAfterLast("/CN=").substringBefore('/'))
}

// a time of OpenSSL, 351115120000Z or 20351115120000Z, in UTC
private fun utc(text: String): String {
    val digits = text.removeSuffix("Z")
    if (!digits.all(Char::isDigit)) return text
    val full = when (digits.length) {
        12 -> (if (digits.take(2).toInt() < 50) "20" else "19") + digits
        14 -> digits
        else -> return text
    }
    return "${full.substring(0, 4)}-${full.substring(4, 6)}-${full.substring(6, 8)} ${full.substring(8, 10)}:${full.substring(10, 12)}"
}

// the directives of a configuration by name with the rest of their lines; comments and what stands between <ca> and </ca> are none
private fun directivesOf(lines: List<String>): Map<String, List<String>> {
    val found = linkedMapOf<String, MutableList<String>>()
    var block = false
    for (line in lines) {
        val text = line.trim()
        when {
            text.startsWith("</") -> block = false
            text.startsWith("<") -> block = true
            block || text.isEmpty() || text[0] == '#' || text[0] == ';' -> Unit
            else -> {
                val words = text.substringBefore(" #").substringBefore(" ;").trim().split(BLANKS, limit = 2)
                found.getOrPut(words.first().lowercase()) { mutableListOf() } += words.getOrElse(1) { "" }.trim()
            }
        }
    }
    return found
}

private fun String.words(count: Int) = split(BLANKS).take(count).joinToString(" ")

private fun items(text: String) = text.split(LIST).filter { it.isNotEmpty() }

private fun ids(text: String?) = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun login(file: String) = "${file.removeSuffix(".conf")}.auth"

// the first of name, name2, name3 and on that no configuration of OpenVPN has
private fun freeName(host: OpenVpnState, name: String) = (sequenceOf(name) + (2..99).asSequence().map { "$name$it" }).first { free -> host.confs.none { it.name == free } }

private fun noService() = DeviceException(ErrorCategory.Unsupported, "the host has neither systemd nor the OpenRC service of OpenVPN")

private fun noPki(conf: OvpnConf) = DeviceException(ErrorCategory.Unsupported, "${conf.name} has no PKI of easy-rsa beside its CA")

// 203.0.113.5:51234 [client1] Peer Connection Initiated with [AF_INET]203.0.113.5:51234, the address led by client1/ once it has a name
private val CAME = Regex("""^(?:\S+/)?(\S+) \[([^\]]+)\] Peer Connection Initiated""")

// client1/203.0.113.5:51234 MULTI_sva: pool returned IPv4=10.8.0.2, IPv6=(Not enabled)
private val POOLED = Regex("""^(\S+)/(\S+) MULTI_sva: pool returned IPv4=([^,]*)""")

// client1/203.0.113.5:51234 SIGTERM[soft,remote-exit] received, client-instance exiting
private val LEFT = Regex("""^(\S+)/(\S+) SIG\w+\[\w+,([^\]]*)\] received, client-instance (?:exiting|restarting)""")

private const val SERVER_DIR = "/etc/openvpn/server"
private const val CLIENT_DIR = "/etc/openvpn/client"
private const val OPENVPN_DIR = "/etc/openvpn"
private const val UNITS = "/etc/systemd/system"
private const val NAT_UNIT = "networkhelm-nat-openvpn-"
private const val PART = "--nh-part--"

private val BLANKS = Regex("\\s+")
private val LIST = Regex("[,;\\s]+")
private val SERVER_NAME = Regex("[A-Za-z0-9_-]{1,11}")
private val CLIENT_NAME = Regex("[A-Za-z0-9_.@-]{1,64}")
private val CONNECTION_NAME = Regex("[A-Za-z0-9_-]{1,32}")
private val PATH = Regex("/[^\\x00-\\x1f]*")
private val HOST = Regex("[A-Za-z0-9.:-]+")
private val NETWORK6 = Regex("[0-9A-Fa-f:]+/\\d{1,3}")
private val CIPHERS = Regex("[A-Za-z0-9_:-]+")
private val KEEPALIVE = Regex("\\d{1,5} \\d{1,5}")
private val ACCOUNT = Regex("[A-Za-z0-9_.-]{1,32}")
private val CLIENT_REMOTE = Regex("#\\s*ClientRemote\\s*=\\s*(\\S+)")
private val OFF = setOf("0", "no", "off", "false", "disabled")
private val PROTOCOLS = listOf("udp", "tcp", "udp6", "tcp6")
private val SERVER_KEYS = listOf("server", "server-bridge", "server-ipv6")
private val STATES = mapOf("V" to "valid", "R" to "revoked", "E" to "expired")
private val NAT_FIELDS = setOf("network", "network6")
private val CLIENT_SETTINGS = setOf("address", "disabled")
private val SERVER_SETTINGS = listOf(
    "port", "proto", "network", "network6", "dns", "redirect-gateway", "push-routes", "client-to-client", "duplicate-cn", "max-clients", "data-ciphers",
    "keepalive", "user", "group", "verb", "client-remote",
)

private val SERVER = listOf(
    "name", "state", "autostart", "port", "proto", "dev", "network", "network6", "dns", "redirect-gateway", "push-routes", "client-to-client", "duplicate-cn",
    "max-clients", "data-ciphers", "tls", "keepalive", "user", "group", "verb", "client-remote", "nat", "management", "clients", "pki", "file",
)
private val CLIENT = listOf("server", "name", "state", "expires", "revoked", "serial", "disabled", "address")
private val CONNECTION = listOf("name", "state", "autostart", "remote", "proto", "dev", "user", "password", "file")

private val SERVER_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("port", ValueType.Integer(1, 65535), optional = true, placeholder = "1194"),
    Argument("proto", ValueType.Enumeration(closed = true), PROTOCOLS, optional = true, placeholder = "udp"),
    Argument("network", ValueType.Ip4Prefix, optional = true, placeholder = "10.8.0.0/24"),
    Argument("network6", ValueType.Ip6Prefix, optional = true, placeholder = "fd42:8::/64"),
    Argument("dns", ValueType.Text(), optional = true, placeholder = "1.1.1.1, 9.9.9.9"),
    Argument("redirect-gateway", ValueType.YesNo, optional = true),
    Argument("push-routes", ValueType.Text(), optional = true, placeholder = "192.168.1.0/24"),
    Argument("client-to-client", ValueType.YesNo, optional = true),
    Argument("duplicate-cn", ValueType.YesNo, optional = true),
    Argument("max-clients", ValueType.Integer(1, 65535), optional = true),
    Argument("data-ciphers", ValueType.Text(), optional = true, placeholder = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305"),
    Argument("keepalive", ValueType.Text(), optional = true, placeholder = "10 120"),
    Argument("user", ValueType.Text(), optional = true),
    Argument("group", ValueType.Text(), optional = true),
    Argument("verb", ValueType.Integer(0, 11), optional = true),
    Argument("client-remote", ValueType.Text(), optional = true, placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
)
private val CLIENT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("address", ValueType.Ip4, optional = true),
    Argument("disabled", ValueType.YesNo),
)
private val CONNECTION_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("import", ValueType.Text(), fixed = true, placeholder = "/root/client.ovpn"),
    Argument("user", ValueType.Text(), optional = true),
    Argument("password", ValueType.Secret, optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes
private val WIZARD_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name", tab = "general"),
    Argument("port", ValueType.Integer(1, 65535), label = "Port", tab = "general"),
    Argument("proto", ValueType.Enumeration(closed = true), PROTOCOLS, label = "Protocol", tab = "general"),
    Argument("client-remote", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("network", ValueType.Ip4Prefix, label = "IPv4 Network", tab = "network", placeholder = "10.8.0.0/24"),
    Argument("network6", ValueType.Ip6Prefix, optional = true, label = "IPv6 Network", tab = "network", placeholder = "fd42:8::/64"),
    Argument("dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = CLIENT_DNS),
    Argument("redirect-gateway", ValueType.YesNo, label = "All Traffic Through VPN", tab = "dns"),
    Argument("push-routes", ValueType.Text(), optional = true, label = "Routes for Clients", tab = "dns", placeholder = "192.168.1.0/24"),
    Argument("data-ciphers", ValueType.Text(), optional = true, label = "Data Ciphers", tab = "keys", placeholder = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305"),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("client", ValueType.Text(), optional = true, label = "First Client", tab = "clients"),
    Argument("client-to-client", ValueType.YesNo, label = "Client to Client", tab = "clients"),
    Argument("max-clients", ValueType.Integer(1, 65535), optional = true, label = "Max Clients", tab = "clients"),
)

private val IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot"),
    Argument("keep-session", ValueType.YesNo, label = "Keep This Session Outside the Tunnel"),
    Argument("user", ValueType.Text(), optional = true, label = "User"),
    Argument("password", ValueType.Secret, optional = true, label = "Password"),
    Argument("text", ValueType.Secret, label = "Configuration"),
)
