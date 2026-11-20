package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// strongSwan of a Linux host through swanctl: the connections the files of /etc/swanctl hold, and the servers of IKEv2 made here for the
// clients built into Windows, macOS, iOS and Android: a certificate of a CA of their own for the server and EAP-MSCHAPv2 for the users,
// whose secrets one file of their own keeps, a user turned off behind #[disabled]. The host as a client of another server has a
// connection of IKEv2 in a file of its own with its secret, brought up under the guard. A file goes through swanctl --load-all or
// --load-creds and comes back as it was when that fails. Connections of other files are shown and stay as they are
internal class LinuxVpnIpsec(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    private val nat = LinuxVpnNat(root, tools, guard)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/ipsec" to HostTable(
            CONNECTION, { state().rows() }, slow = true, arguments = { ARGUMENTS }, add = ::add, set = ::set, remove = ::remove, commands = listOf("load"),
            run = { _, _ -> listOf(Item("", mapOf("output" to hidden(root.masked("swanctl --load-all 2>&1")).trim()))) }, present = ::present,
        ),
        "vpn/ipsec/client" to HostTable(
            CLIENT, ::clients, slow = true, arguments = { CLIENT_ARGUMENTS }, add = ::addClient, set = ::setClient, remove = ::removeClients,
            commands = listOf("up", "down"), run = ::bringClients, present = ::present,
        ),
        "vpn/ipsec/server" to HostTable(
            SERVER_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { SERVER_ARGUMENTS }, commands = listOf("create"),
            run = { _, values -> create(values) }, present = ::present,
        ),
        "vpn/ipsec/import" to HostTable(
            IMPORT_ARGUMENTS.map { it.name }, { listOf(Item("", mapOf("name" to freeName(state(), "client"), "networks" to "0.0.0.0/0"))) }, settings = true,
            arguments = { IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importClient(values) }, present = ::present,
        ),
    )

    private suspend fun present() = "swanctl" in tools()

    suspend fun state() = parseIpsec(root.masked(IPSEC_STATE))

    // a new server: a CA and the certificate of the server with the name or address clients reach it by, as macOS and iOS want it with
    // serverAuth and ikeIntermediate, then the connection and its pool of addresses, its start at boot, NAT and ports; a step that fails
    // takes the server away again
    private suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!NAME.matches(name) || RESERVED.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val host = state()
        if (host.connections.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an IPsec connection $name already")
        val server = IkeServer(
            name, values["server-id"].orEmpty().ifEmpty { localAddress(ssh) }, values["pool"].orEmpty().ifEmpty { "10.10.10.0/24" }, values["pool6"].orEmpty(),
            values["dns"].orEmpty().ifEmpty { "1.1.1.1" }, values["local-ts"].orEmpty(), optionsOf(values),
        ).also(::validate)
        // charon gives a client the first connection that answers as the identity it asks for, with the certificate of that one
        host.connections.firstOrNull { !it.client && it.serverId == server.id && it.settings["remote_addrs"].orEmpty().let { addrs -> addrs.isEmpty() || "%any" in addrs } }?.let {
            throw DeviceException(
                ErrorCategory.ArgumentValue,
                "the server ${it.name} already answers as ${server.id}: clients would get its certificate; give the new server another name or address for clients, or change ${it.name}",
            )
        }
        val withNat = values["nat"] in ValueType.Flag.on
        if (withNat && host.unit == null && nat.manager() == null) throw noNat(name)
        val n = "$PREFIX$name"
        val sans = listOf(server.id) + listOfNotNull(localAddress(ssh).takeIf { it.isNotEmpty() && it != server.id })
        if (root.exec("[ -e /etc/swanctl/x509/$n.crt ]").exitStatus == 0) throw DeviceException(ErrorCategory.ArgumentValue, "the certificate /etc/swanctl/x509/$n.crt is there already")
        try {
            root.masked(
                "umask 077; command -v pki >/dev/null || { echo 'the host has no pki of strongSwan' >&2; exit 1; }; cd /etc/swanctl && mkdir -p networkhelm private x509 x509ca && " +
                    "pki --gen --type rsa --size 3072 --outform pem > networkhelm/$n-ca.key && " +
                    "pki --self --ca --lifetime 3650 --in networkhelm/$n-ca.key --type rsa --dn ${quote("CN=$name CA")} --outform pem > x509ca/$n-ca.crt && " +
                    "pki --gen --type rsa --size 3072 --outform pem > private/$n.key && pki --pub --in private/$n.key --type rsa | " +
                    "pki --issue --lifetime 3650 --cacert x509ca/$n-ca.crt --cakey networkhelm/$n-ca.key --dn ${quote("CN=${server.id}")} " +
                    sans.joinToString(" ") { "--san ${quote(it)}" } + " --flag serverAuth --flag ikeIntermediate --outform pem > x509/$n.crt && chmod 644 x509ca/$n-ca.crt x509/$n.crt",
            )
            running(host)
            plugins(host)
            load(conf(name), server.text())
            if (values["autostart"] in ValueType.Flag.on) autostart(host, true)
            if (withNat) natFor(host, name, server.pools(), true)
            nat.open("ipsec-$name", IKE_PORTS)
        } catch (e: DeviceException) {
            undoing(e) {
                if (withNat) natFor(host, name, server.pools(), false)
                root.masked("cd /etc/swanctl && rm -f conf.d/$n.conf networkhelm/$n-ca.key x509ca/$n-ca.crt private/$n.key x509/$n.crt && { swanctl --load-all >/dev/null 2>&1; true; }")
            }
        }
        return name
    }

    // charon starts at boot for every connection or for none, so Start at Boot of a connection is that of its service
    private suspend fun autostart(host: IpsecState, on: Boolean) {
        root.masked(host.unit?.let { "systemctl ${if (on) "enable" else "disable"} ${quote(it)}" } ?: "rc-update ${if (on) "add" else "del"} strongswan default")
    }

    // a server made here takes a new pool, DNS, networks it gives clients and options, the rest as its file has them; the name clients
    // reach it by is in its certificate. NAT follows new pools
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        val current = own(host, id)
        val server = IkeServer(
            current.name, current.serverId, changes["pool"] ?: current.pool, changes["pool6"] ?: current.pool6, changes["dns"] ?: current.dns,
            changes["local-ts"] ?: current.localTs, optionsOf(changes, current.serverOptions()),
        ).also(::validate)
        load(conf(current.name), server.text())
        changes["autostart"]?.let { autostart(host, it in ValueType.Flag.on) }
        val natted = "ipsec-${current.name}" in host.nat
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on } ?: natted.takeIf { it && (changes["pool"] != null || changes["pool6"] != null) }
        if (natOn == null) return
        if (natted) natFor(host, current.name, listOf(current.pool, current.pool6).filter { it.isNotEmpty() }, false)
        if (natOn) natFor(host, current.name, server.pools(), true)
        // the NAT of a server made before held its ports under its own mark
        nat.open("ipsec-${current.name}", IKE_PORTS)
    }

    // NAT for the pools of a server by the policy of IPsec: through ufw or firewalld where one keeps the rules of the host, else by an
    // addition to the unit of charon; turned off, it goes from both
    private suspend fun natFor(host: IpsecState, name: String, pools: List<String>, on: Boolean) {
        val manager = nat.manager()
        val unit = host.unit
        if (!on) {
            if (manager != null) nat.set(manager, "ipsec-$name", pools, emptyList(), false, policy = true)
            if (unit != null) root.natUnit(unit, hookFile(unit, name), null)
            return
        }
        if (manager == null && unit == null) throw noNat(name)
        nat.forward(pools.any { ':' in it })
        if (manager != null) nat.set(manager, "ipsec-$name", pools, emptyList(), true, policy = true) else unit?.let { root.natUnit(it, hookFile(it, name), ipsecNat(name, pools, "iptables-save" in tools())) }
    }

    private suspend fun remove(ids: List<String>) {
        val host = state()
        val gone = ids.map { own(host, it) }
        for (connection in gone) {
            if ("ipsec-${connection.name}" in host.nat) natFor(host, connection.name, listOf(connection.pool, connection.pool6).filter { it.isNotEmpty() }, false)
            nat.open("ipsec-${connection.name}", emptyList())
        }
        root.masked(
            "cd /etc/swanctl && rm -f " + gone.joinToString(" ") { "conf.d/$PREFIX${it.name}.conf networkhelm/$PREFIX${it.name}-ca.key x509ca/$PREFIX${it.name}-ca.crt private/$PREFIX${it.name}.key x509/$PREFIX${it.name}.crt" } +
                " && swanctl --load-all >/dev/null",
        )
    }

    private fun own(host: IpsecState, id: String?): IpsecConnection {
        val connection = host.connections.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no IPsec connection $id")
        if (connection.client) throw DeviceException(ErrorCategory.Unsupported, "${connection.name} is a client connection, which its tab keeps")
        if (!connection.own) throw DeviceException(ErrorCategory.Unsupported, "${connection.file} is no file of NetworkHelm, and it stays as it is")
        if (connection.l2tp) throw DeviceException(ErrorCategory.Unsupported, "${connection.name} is the IPsec of L2TP, which its window keeps")
        return connection
    }

    // swanctl talks to charon, which its service starts
    private suspend fun running(host: IpsecState) {
        if (host.active) return
        root.masked(host.unit?.let { "systemctl start ${quote(it)}" } ?: "rc-service strongswan start")
    }

    // charon takes its plugins as it starts, so the ones a package brought later need a restart; without them the server answers its
    // clients EAP_FAILURE
    private suspend fun plugins(host: IpsecState) {
        suspend fun missing() = EAP_PLUGINS - root.masked("swanctl --stats 2>/dev/null; true").lines().firstOrNull { it.trim().startsWith("loaded plugins:") }
            ?.substringAfter(':')?.trim()?.split(' ')?.toSet().orEmpty()
        if (missing().isEmpty()) return
        root.masked(host.unit?.let { "systemctl restart ${quote(it)}" } ?: "rc-service strongswan restart")
        missing().ifEmpty { null }?.let { throw DeviceException(ErrorCategory.Unsupported, "charon has no plugin ${it.joinToString(", ")}, which libcharon-extauth-plugins brings on Debian and Ubuntu") }
    }

    // a file loaded at once; a connection or secret that does not load puts the old file back, loaded again
    private suspend fun load(path: String, text: String, check: String = "swanctl --load-all") {
        try {
            root.write(path, text, "$check 2>&1", failed = ::swanFailed)
        } catch (e: DeviceException) {
            root.exec("$check >/dev/null 2>&1")
            throw e
        }
    }

    // the users of EAP: their names and whether they are off; the secrets stay on the host
    suspend fun eapUsers(): List<Pair<String, Boolean>> = state().users

    suspend fun servers(): List<IpsecConnection> = state().connections.filter { it.own && !it.l2tp }

    suspend fun newSecret() = root.newSecret()

    suspend fun startAtBoot() = autostart(state(), true)

    // the IPsec of L2TP: IKEv1 with a preshared key in transport mode for UDP 1701, with the proposals of the clients built into Windows,
    // macOS and iOS, modp1024 and 3DES among them
    suspend fun setL2tp(psk: String) {
        if (psk.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        running(state())
        val text = listOf(
            "# the IPsec of the L2TP server of NetworkHelm", "connections {", "    networkhelm-l2tp {", "        version = 1", "        proposals = $L2TP_PROPOSALS",
            "        dpd_delay = 30s", "        local {", "            auth = psk", "        }", "        remote {", "            auth = psk", "        }",
            "        children {", "            l2tp {", "                mode = transport", "                local_ts = dynamic[udp/1701]",
            "                esp_proposals = $L2TP_ESP", "                dpd_action = clear", "            }", "        }",
            "    }", "}", "secrets {", "    ike-networkhelm-l2tp {", "        secret = ${swanQuoted(psk)}", "    }", "}",
        )
        load(L2TP_IPSEC, text.joinToString("\n"))
    }

    // the IPsec of a connection of the host to a server of L2TP: the same in transport mode from the port of its xl2tpd to UDP 1701 of the
    // server, put in as a trap that charon brings up as xl2tpd first sends. The secret has no id, as the server shows its address, which a
    // name given for it is not
    suspend fun setL2tpClient(name: String, server: String, port: String, psk: String) {
        if (psk.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        running(state())
        val connection = "$L2TP_PREFIX-$name"
        val text = listOf(
            "# the IPsec of an L2TP connection of NetworkHelm", "connections {", "    $connection {", "        version = 1", "        remote_addrs = $server",
            "        proposals = $L2TP_PROPOSALS", "        dpd_delay = 30s", "        local {", "            auth = psk", "        }", "        remote {", "            auth = psk", "        }",
            "        children {", "            $connection {", "                mode = transport", "                local_ts = dynamic[udp/$port]", "                remote_ts = dynamic[udp/1701]",
            "                esp_proposals = $L2TP_ESP", "                start_action = trap", "                dpd_action = clear", "            }", "        }",
            "    }", "}", "secrets {", "    ike-$connection {", "        secret = ${swanQuoted(psk)}", "    }", "}",
        )
        load(l2tpClientConf(name), text.joinToString("\n"))
    }

    suspend fun removeL2tpClient(name: String) =
        root.masked("${ikeTerminate("$L2TP_PREFIX-$name")} && rm -f ${quote(l2tpClientConf(name))} && swanctl --load-all >/dev/null")

    suspend fun l2tpSecret(): String = fileSecret(L2TP_IPSEC)

    private suspend fun fileSecret(path: String) =
        root.masked("cat ${quote(path)}").lines().map { it.trim() }.firstOrNull { it.startsWith("secret") && '=' in it }?.let(::swanSecret).orEmpty()

    // the client connections made here with the state of their IKE SA
    private suspend fun clients(): List<Item> {
        val sas = parseIkeSas(root.masked("swanctl --list-sas --raw 2>/dev/null; true")).associate { it.connection to it.values["state"].orEmpty() }
        return state().connections.filter { it.client }.map { it.clientRow(sas[it.name].orEmpty()) }
    }

    private suspend fun addClient(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val host = state()
        if (host.connections.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an IPsec connection $name already")
        writeClient(host, name, values, values["secret"].orEmpty())
        return name
    }

    // the settings given take the place of those there are; an empty secret leaves the one the file has
    private suspend fun setClient(id: String?, changes: Map<String, String?>) {
        val host = state()
        val client = client(host, id)
        val secret = changes["secret"]?.ifEmpty { null } ?: fileSecret(client.file)
        writeClient(host, client.name, client.clientValues() + changes.mapValues { it.value.orEmpty() }, secret)
    }

    private suspend fun removeClients(ids: List<String>) {
        val host = state()
        val gone = ids.map { client(host, it) }
        root.masked(gone.joinToString(" && ") { "${ikeTerminate(it.name)} && rm -f ${quote(it.file)} ${quote(clientCa(it.name))}" } + " && swanctl --load-all >/dev/null")
    }

    // a connection brought up goes under the guard, as the networks it takes may hold the way this session comes by; one that does not
    // come up is ended at once
    private suspend fun bringClients(command: String, arguments: Map<String, String>): List<Item> {
        val host = state()
        val up = parseIkeSas(root.masked("swanctl --list-sas --raw 2>/dev/null; true")).map { it.connection }.toSet()
        for (client in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }.map { client(host, it) }) {
            val name = quote(client.name)
            // what charon says goes to stdout, and the message of a failure is taken from stderr
            when (command) {
                "up" -> if (client.name !in up) guard.guarded(LinuxGuard.Backup("vpn", "true", ikeTerminate(client.name))) { root.masked("swanctl --initiate --ike $name --child $name >&2") }
                "down" -> root.masked(ikeTerminate(client.name))
            }
        }
        return emptyList()
    }

    private fun client(host: IpsecState, id: String?) =
        host.connections.firstOrNull { it.name == id && it.client } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no IPsec connection $id")

    // EAP-MSCHAPv2 with a server that shows a certificate, or a preshared key on both sides; the CA of the certificate is copied beside
    // the others where a file of it is given
    private suspend fun writeClient(host: IpsecState, name: String, values: Map<String, String>, secret: String) {
        val given = { key: String -> values[key].orEmpty() }
        val eap = given("auth").ifEmpty { "eap-mschapv2" } == "eap-mschapv2"
        val remoteTs = given("networks")
        if (remoteTs.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "networks: a value is needed")
        val bad = when {
            !HOST.matches(given("server")) -> "server" to given("server")
            given("server-id").isNotEmpty() && !CLIENT_ID.matches(given("server-id")) -> "server-id" to given("server-id")
            given("auth").isNotEmpty() && given("auth") !in listOf("eap-mschapv2", "psk") -> "auth" to given("auth")
            eap && !CLIENT_ID.matches(given("user")) -> "user" to given("user")
            !items(remoteTs).all { NETWORK.matches(it) } -> "networks" to remoteTs
            !items(given("local-networks")).all { NETWORK.matches(it) } -> "local-networks" to given("local-networks")
            given("ca-file").isNotEmpty() && !CA_FILE.matches(given("ca-file")) -> "ca-file" to given("ca-file")
            else -> null
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        if (secret.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "secret: a value is needed")
        if (secret.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        val ca = given("ca-file")
        if (ca.isNotEmpty() && ca != clientCa(name)) root.masked("cp ${quote(ca)} ${quote(clientCa(name))} && chmod 644 ${quote(clientCa(name))}")
        val remoteId = given("server-id").ifEmpty { given("server") }
        val text = listOf("# a client connection made by NetworkHelm", "connections {", "    $name {", "        version = 2", "        remote_addrs = ${given("server")}") +
            listOfNotNull("        vips = 0.0.0.0".takeIf { given("vip").ifEmpty { "yes" } in ValueType.Flag.on }) +
            listOf("        local {", "            auth = ${if (eap) "eap-mschapv2" else "psk"}") + listOfNotNull("            eap_id = ${given("user")}".takeIf { eap }) +
            listOf("        }", "        remote {", "            auth = ${if (eap) "pubkey" else "psk"}", "            id = $remoteId") +
            listOfNotNull("            cacerts = ${clientCa(name).substringAfterLast('/')}".takeIf { ca.isNotEmpty() }) +
            listOf("        }", "        children {", "            $name {", "                remote_ts = $remoteTs") +
            listOfNotNull(given("local-networks").ifEmpty { null }?.let { "                local_ts = $it" }) +
            listOf("            }", "        }", "    }", "}", "secrets {", "    ${if (eap) "eap" else "ike"}-client-$name {", "        id = ${if (eap) given("user") else remoteId}",
                "        secret = ${swanQuoted(secret)}", "    }", "}")
        running(host)
        if (eap) plugins(host)
        load(clientConf(name), text.joinToString("\n"))
    }

    // a user without a password gets one the host makes
    suspend fun addEap(name: String, password: String, disabled: Boolean) {
        val users = secrets()
        if (users.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $name already")
        writeSecrets(users + EapSecret(name, password.ifEmpty { newSecret() }, disabled))
    }

    // an empty password leaves the one there is; a user turned off loses its sessions
    suspend fun setEap(name: String, password: String?, disabled: Boolean?) {
        val users = secrets()
        val user = users.firstOrNull { it.name == name } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        writeSecrets(users.map { if (it === user) EapSecret(name, password?.ifEmpty { null } ?: user.secret, disabled ?: user.disabled) else it })
        if (disabled == true) {
            val ids = root.masked("swanctl --list-sas --raw 2>/dev/null; true").lines().filter { " remote-eap-id=$name " in it }.mapNotNull { UNIQUE.find(it)?.groupValues?.get(1) }
            for (id in ids) root.masked("swanctl --terminate --ike-id $id --force >/dev/null")
        }
    }

    suspend fun removeEap(names: List<String>) = writeSecrets(secrets().filter { it.name !in names })

    // the server, the login and the password of a user for each server of IKEv2 made here
    suspend fun credentials(name: String): List<Item> {
        val user = secrets().firstOrNull { it.name == name } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        return servers().map { Item("", mapOf("conf" to login(it, name, user.secret))) }
    }

    // a profile of each server for the device of a user: .mobileconfig for macOS and iOS with the CA to trust, .sswan for the app of
    // strongSwan on Android, a script of PowerShell for Windows; the password goes in none of them, the device asks for it
    suspend fun export(name: String, kind: String): List<Item> {
        if (secrets().none { it.name == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        return profiles(servers(), name, listOf(kind)).map { Item("", mapOf("conf" to it)) }
    }

    // the profiles of each server kind by kind, with the CA of each and fresh uuids read at once
    private suspend fun profiles(servers: List<IpsecConnection>, user: String, kinds: List<String>): List<String> {
        val read = root.masked(servers.joinToString("; ") { "echo $PART; cat /etc/swanctl/x509ca/$PREFIX${it.name}-ca.crt; for i in 1 2 3; do cat /proc/sys/kernel/random/uuid; done" })
        val parts = read.split("$PART\n").drop(1)
        return servers.zip(parts).flatMap { (server, part) ->
            val ca = part.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----").filterNot { it.isWhitespace() }
            val uuids = part.substringAfter("-----END CERTIFICATE-----").lines().map { it.trim() }.filter { it.isNotEmpty() }
            kinds.map { profile(it, server, user, ca, uuids) }
        }
    }

    // what a new server takes unless the user says otherwise: the next free name, the address of the interface of the default route
    // for clients, the first network 10.N.0.0/24 that nothing of the host and no pool of another server overlaps, DNS for clients, NAT
    // where the host can keep it, a start at boot and a first user
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan(root)
        val taken = scan.networks + host.connections.mapNotNull { Ipv4.parseSubnet(it.pool) }
        val users = host.users.map { it.first }
        return Item(
            "",
            linkedMapOf(
                "name" to freeName(host, "ikev2"), "client-endpoint" to scan.address, "autostart" to "true", "pool" to freeNetwork(taken)?.cidr.orEmpty(),
                "dns" to CLIENT_DNS, "nat" to "${host.unit != null || nat.manager() != null}", "user" to generateSequence(1) { it + 1 }.map { "client$it" }.first { it !in users },
                "unique" to "never", "mobike" to "true", "fragmentation" to "yes", "encap" to "false", "dpd-delay" to "30s", "proposals" to IKE_PROPOSALS,
                "esp-proposals" to ESP_PROPOSALS, "send-cert" to "always",
            ),
        )
    }

    // a server in one step: the server as a new one is made, then its first user, whose login, password and profiles come back
    private suspend fun create(values: Map<String, String>): List<Item> {
        val name = values["name"].orEmpty()
        val endpoint = values["client-endpoint"].orEmpty()
        val user = values["user"].orEmpty()
        val password = values["password"].orEmpty()
        if (endpoint.isNotEmpty() && !HOST.matches(endpoint)) throw DeviceException(ErrorCategory.ArgumentValue, "client-endpoint: $endpoint is not a value it takes")
        if (user.isNotEmpty()) {
            if (!VPN_USER.matches(user)) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
            if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
            if (secrets().any { it.name == user }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $user already")
        }
        add(values + ("server-id" to endpoint))
        if (user.isEmpty()) return listOf(Item(name, mapOf("name" to name)))
        val secret = try {
            password.ifEmpty { newSecret() }.also { addEap(user, it, false) }
        } catch (e: DeviceException) {
            undoing(e) { remove(listOf(name)) }
        }
        val server = servers().firstOrNull { it.name == name } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no IPsec connection $name")
        val texts = profiles(listOf(server), user, EXTENSIONS.keys.toList())
        return listOf(Item(name, mapOf("name" to name, "conf" to login(server, user, secret)))) +
            EXTENSIONS.values.zip(texts) { extension, text -> Item(name, mapOf("name" to name, "file" to "$name.$extension", "conf" to text)) }
    }

    // a client of a server of IKEv2 from the profile that server gave, .sswan or .mobileconfig, which names the server, the login and
    // the CA, or from a certificate of the CA in PEM; the fields given go before those of the profile, and the password, which no
    // profile holds, is typed. The CA goes beside the others, the client is made as on its tab and comes up under the guard
    private suspend fun importClient(values: Map<String, String>): List<Item> {
        val profile = parseIkeProfile(values["text"].orEmpty())
            ?: throw DeviceException(ErrorCategory.ArgumentValue, "text: a profile of IKEv2 or the certificate of its CA in PEM is needed")
        val name = values["name"].orEmpty()
        if (!NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (state().connections.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an IPsec connection $name already")
        val server = values["server"].orEmpty().ifEmpty { profile.server }
        val user = values["user"].orEmpty().ifEmpty { profile.user }
        val password = values["password"].orEmpty()
        listOf("server" to server, "user" to user, "password" to password).firstOrNull { it.second.isEmpty() }
            ?.let { throw DeviceException(ErrorCategory.ArgumentValue, "${it.first}: a value is needed") }
        val ca = clientCa(name)
        root.write(ca, profile.pem(), "true", "644")
        try {
            addClient(mapOf("name" to name, "server" to server, "user" to user, "secret" to password, "networks" to values["networks"].orEmpty().ifEmpty { "0.0.0.0/0" }, "ca-file" to ca))
        } catch (e: DeviceException) {
            root.masked("rm -f ${quote(ca)}")
            throw e
        }
        bringClients("up", mapOf("numbers" to name))
        return listOf(Item(name, mapOf("name" to name)))
    }

    private suspend fun secrets(): List<EapSecret> = parseSecrets(root.exec("cat $USERS").takeIf { it.exitStatus == 0 }?.output.orEmpty())

    private suspend fun writeSecrets(users: List<EapSecret>) {
        users.firstOrNull { it.secret.any { char -> char == '\n' || char == '\r' } }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break") }
        val blocks = users.map { user ->
            listOf("    eap-${user.name} {", "        id = ${user.name}", "        secret = ${swanQuoted(user.secret)}", "    }")
                .map { if (user.disabled) "$OFF$it" else it }
        }
        load(USERS, (listOf("secrets {") + blocks.flatten() + "}").joinToString("\n"), "swanctl --load-creds")
    }
}

// a server of IKEv2 as this window writes it; its options are the fields of SERVER_OPTIONS as swanctl writes them, the empty ones
// left to the defaults of the template, which are those of charon where the template has none
internal class IkeServer(
    val name: String, val id: String, val pool: String, val pool6: String, val dns: String, val localTs: String, val options: Map<String, String> = emptyMap(),
) {
    fun pools() = listOf(pool, pool6).filter { it.isNotEmpty() }

    fun text(): String {
        val option = { key: String -> options[key].orEmpty() }
        val ts = localTs.ifEmpty { if (pool6.isEmpty()) "0.0.0.0/0" else "0.0.0.0/0,::/0" }
        val pools = listOf("$name-v4" to pool) + listOfNotNull(pool6.ifEmpty { null }?.let { "$name-v6" to it })
        return (
            listOf(
                "# a server of IKEv2 made by NetworkHelm: EAP-MSCHAPv2 for the clients built into Windows, macOS, iOS and Android",
                "connections {", "    $name {", "        version = 2", "        pools = ${pools.joinToString(",") { it.first }}", "        send_cert = ${option("send-cert").ifEmpty { "always" }}",
                "        proposals = ${option("proposals").ifEmpty { IKE_PROPOSALS }}", "        dpd_delay = ${option("dpd-delay").ifEmpty { "30s" }}",
                "        unique = ${option("unique").ifEmpty { "never" }}",
            ) + listOfNotNull(
                option("rekey-time").ifEmpty { null }?.let { "        rekey_time = $it" }, "        mobike = no".takeIf { option("mobike") == "no" },
                option("fragmentation").takeIf { it.isNotEmpty() && it != "yes" }?.let { "        fragmentation = $it" }, "        encap = yes".takeIf { option("encap") == "yes" },
            ) + listOf(
                "        local {", "            auth = pubkey", "            certs = $PREFIX$name.crt", "            id = $id", "        }",
                "        remote {", "            auth = eap-mschapv2", "            eap_id = %any", "        }",
                "        children {", "            $name {", "                local_ts = $ts", "                esp_proposals = ${option("esp-proposals").ifEmpty { ESP_PROPOSALS }}",
            ) + listOfNotNull(option("child-rekey-time").ifEmpty { null }?.let { "                rekey_time = $it" }) + listOf(
                "                dpd_action = clear", "            }", "        }", "    }", "}", "pools {",
            ) + pools.flatMap { (section, addrs) -> listOf("    $section {", "        addrs = $addrs") + listOfNotNull(dns.ifEmpty { null }?.let { "        dns = ${items(it).joinToString(",")}" }) + "    }" } + "}"
            ).joinToString("\n")
    }
}

// the options of a server from the values of a form over those it has, yes and no and the lists of proposals as swanctl writes them
private fun optionsOf(values: Map<String, String?>, current: Map<String, String> = emptyMap()) = SERVER_OPTIONS.associateWith { key ->
    val value = (values[key] ?: current[key]).orEmpty().trim()
    when {
        value.isEmpty() -> ""
        key == "mobike" || key == "encap" -> if (value in ValueType.Flag.on) "yes" else "no"
        key == "proposals" || key == "esp-proposals" -> items(value).joinToString(",")
        else -> value
    }
}

// an IKE SA as swanctl --list-sas --raw prints it on a line of its own: list-sa event {<connection> {uniqueid=1 ... remote-vips=[10.10.10.1]
// child-sas {<child> {... bytes-in=0 ...}}}}. A value with blanks in it, as an id of a certificate has, comes up to its first blank
internal class IkeSa(val connection: String, val values: Map<String, String>, val children: List<Map<String, String>>) {
    val id get() = values["uniqueid"].orEmpty()
    val kind get() = "IKEv${values["version"] ?: "2"}"
    val user get() = values["remote-eap-id"] ?: values["remote-xauth-id"] ?: values["remote-id"].orEmpty()
    val endpoint get() = values["remote-host"].orEmpty().let { if (':' in it) "[$it]" else it } + values["remote-port"]?.let { ":$it" }.orEmpty()
    val address get() = values["remote-vips"].orEmpty().removeSurrounding("[", "]").split(' ').filter { it.isNotEmpty() }.joinToString(", ")
    val cipher get() = listOfNotNull(values["encr-alg"]?.plus(values["encr-keysize"]?.let { "-$it" }.orEmpty()), values["integ-alg"]).joinToString("/")

    // the bytes of its child SAs, which start again from 0 at their rekeying
    fun bytes(way: String) = "${children.sumOf { it["bytes-$way"]?.toLongOrNull() ?: 0L }}"
}

internal fun parseIkeSas(text: String): List<IkeSa> = text.lines().filter { it.startsWith(SA_EVENT) }.mapNotNull { line ->
    val body = line.removePrefix(SA_EVENT)
    val connection = body.substringBefore(" {").takeIf { it.isNotEmpty() && ' ' !in it } ?: return@mapNotNull null
    val children = CHILD.findAll(body.substringAfter("child-sas {", "")).map { rawPairs(it.groupValues[2]) }.toList()
    IkeSa(connection, rawPairs(body.substringAfter(" {").substringBefore("child-sas {")), children)
}

private fun rawPairs(text: String) = PAIR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

// the lines of the journal of charon that tell of an IKE SA coming and going and of the EAP identity before it, over the last month
internal const val CHARON_HISTORY = "journalctl -q -o json --no-pager --since '31 days ago' _COMM=charon-systemd _COMM=charon 2>/dev/null | " +
    "grep -E 'established between|deleting IKE_SA|received DELETE for IKE_SA|received EAP identity' | tail -n 20000; true"

// the sessions of IKE the journal of charon tells of, newest first: an IKE SA established and deleted by its name and number, under the EAP
// identity charon received for it, by the field IKE_SA_UNIQUE_ID where its journal has one and else the last one before it, or the id of
// its peer. The journal tells no address in the tunnel
internal fun parseCharonJournal(text: String, offset: String): List<Item> {
    val open = linkedMapOf<String, Map<String, String>>()
    val closed = mutableListOf<Map<String, String>>()
    val identities = mutableMapOf<String, String>()
    var identity = ""
    for (line in text.lines()) {
        val entry = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: continue
        val seconds = (entry["__REALTIME_TIMESTAMP"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.div(1_000_000) ?: continue
        val message = (entry["MESSAGE"] as? JsonPrimitive)?.contentOrNull ?: continue
        IKE_IDENTITY.find(message)?.let { match ->
            identity = match.groupValues[1]
            (entry["IKE_SA_UNIQUE_ID"] as? JsonPrimitive)?.contentOrNull?.let { identities[it] = identity }
        }
        IKE_UP.find(message)?.let { match ->
            val (name, number, host, peer) = match.destructured
            open["$name[$number]"] = mapOf("service" to name, "user" to (identities[number] ?: identity.ifEmpty { peer }), "endpoint" to host, "start" to "$seconds")
            identity = ""
        }
        IKE_DOWN.find(message)?.let { match -> open.remove("${match.groupValues[1]}[${match.groupValues[2]}]")?.let { closed += it + ("end" to "$seconds") } }
    }
    return vpnJournalRows("IKEv2", closed + open.values, offset)
}

private const val CLIENT_PREFIX = "networkhelm-client-"

// the connections charon has and its IKE SAs, read with the live tables of the panel
internal const val IKE_STATE = "if command -v swanctl >/dev/null 2>&1; then echo --nh-ike-clients--; for f in /etc/swanctl/conf.d/$CLIENT_PREFIX*.conf; do " +
    "[ -f \"\$f\" ] && { f=\${f##*/$CLIENT_PREFIX}; echo \"\${f%.conf}\"; }; done; " +
    "echo --nh-ike-conns--; swanctl --list-conns 2>/dev/null | sed -n 's/^\\([^ :][^:]*\\): .*/\\1/p'; echo --nh-ike-sas--; swanctl --list-sas --raw 2>/dev/null; fi; true"

// swanctl exits with 0 even where it loaded nothing, so a line that says failed or error fails the load as well; the lines about the
// optional plugins strongSwan lacks say failed to load and mean nothing for it
internal fun swanFailed(output: String) = output.lines().filterNot { PLUGIN_LINE.matches(it.trim()) }.any { "failed" in it || "rror" in it }

// the rules of NAT for the pools of a server that the unit of charon puts in and takes out where no firewall keeps them: masquerade of
// what goes out past IPsec, and forwarding of what IPsec brings and takes, which a policy DROP of FORWARD would stop
internal fun ipsecNat(name: String, pools: List<String>, iptables: Boolean): Pair<String, String> {
    if (!iptables) {
        val table = "inet $NAT_MARK-ipsec-$name"
        val rules = pools.map { "nft add rule $table nat ${if (':' in it) "ip6" else "ip"} saddr $it rt ipsec missing masquerade" }
        return (listOf("nft add table $table", "nft add chain $table nat '{ type nat hook postrouting priority 100; }'") + rules).joinToString("; ") to "nft delete table $table"
    }
    val rules = pools.flatMap { pool ->
        val tool = if (':' in pool) "ip6tables" else "iptables"
        listOf(
            "$tool -w -t nat -A POSTROUTING -s $pool -m policy --dir out --pol none -j MASQUERADE", "$tool -w -I FORWARD -s $pool -m policy --dir in --pol ipsec -j ACCEPT",
            "$tool -w -I FORWARD -d $pool -m policy --dir out --pol ipsec -j ACCEPT",
        )
    }
    return rules.joinToString("; ") to rules.joinToString("; ") { it.replace(" -A ", " -D ").replace(" -I ", " -D ") }
}

private fun hookFile(unit: String, name: String) = "/etc/systemd/system/$unit.d/$NAT_MARK-ipsec-$name.conf"

// the rules of NAT for the networks of the clients of a server whose devices come and go, past those devices, which the addition to its
// unit puts in where no firewall keeps them; a port of UDP sealed takes only what came through IPsec
internal fun deviceNat(name: String, networks: List<String>, device: String, iptables: Boolean, sealed: String? = null): Pair<String, String> {
    if (iptables) {
        val (up, down) = natHooks(networks, iptables = true).toList().map { it.substringBefore(" # ").replace("%i", "$device+") }
        val seal = sealed?.let { "INPUT -p udp --dport $it -m policy --dir in --pol none -j DROP" }
        return up + seal?.let { "; iptables -w -I $it" }.orEmpty() to down + seal?.let { "; iptables -w -D $it" }.orEmpty()
    }
    val table = "inet $NAT_MARK-$name"
    val rules = listOf("nft add table $table", "nft add chain $table nat '{ type nat hook postrouting priority 100; }'") +
        networks.map { "nft add rule $table nat ${if (':' in it) "ip6" else "ip"} saddr $it oifname != '$device*' masquerade" } +
        listOfNotNull(sealed?.let { "nft add chain $table input '{ type filter hook input priority 0; }'; nft add rule $table input udp dport $it meta ipsec missing drop" })
    return rules.joinToString("; ") to "nft delete table $table"
}

// NAT where no firewall keeps the rules: an addition to a unit puts them in as the unit starts and takes them out as it stops, and a
// running unit swaps the rules of the addition before for the new ones at once; no rules take the addition away. A rule that fails
// does not stop the unit, which serves its clients all the same
internal suspend fun Root.natUnit(unit: String, path: String, rules: Pair<String, String>?) {
    val file = quote(path)
    val running = "systemctl is-active -q ${quote(unit)}"
    masked("if [ -e $file ] && $running; then sed -n 's|^ExecStopPost=-/bin/sh -c \"\\(.*\\)\"\$|\\1|p' $file | sh; fi; true")
    if (rules == null) {
        masked("[ ! -e $file ] || { rm -f $file && systemctl daemon-reload; }")
        return
    }
    val (up, down) = rules
    write(path, "[Service]\nExecStartPost=-/bin/sh -c \"$up\"\nExecStopPost=-/bin/sh -c \"$down\"", "systemctl daemon-reload 2>&1", "644")
    masked("if $running; then $up; fi")
}

// a password the host makes from /dev/urandom
internal suspend fun Root.newSecret() = masked("head -c 15 /dev/urandom | base64 | tr -d '+/='").trim()

// what a step of a new server made goes again where a later one fails, so that the next try finds its name free; a failure on the way
// back stays with the first one
private suspend fun undoing(e: DeviceException, undo: suspend () -> Unit): Nothing {
    try {
        undo()
    } catch (undone: DeviceException) {
        e.addSuppressed(undone)
    }
    throw e
}

private fun validate(server: IkeServer) {
    val bad = when {
        !HOST.matches(server.id) -> "server-id" to server.id
        Ipv4.parseSubnet(server.pool)?.takeIf { '/' in server.pool }?.prefix !in 8..30 -> "pool" to server.pool
        server.pool6.isNotEmpty() && !NETWORK6.matches(server.pool6) -> "pool6" to server.pool6
        !items(server.dns).all(HOST::matches) -> "dns" to server.dns
        !items(server.localTs).all { NETWORK.matches(it) } -> "local-ts" to server.localTs
        else -> OPTION_VALUES.entries.firstOrNull { (key, rule) -> server.options[key].orEmpty().let { it.isNotEmpty() && !rule.matches(it) } }?.let { it.key to server.options[it.key] }
    }
    bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
}

// a connection as the files show it: its settings under connections.<name>, those of its first pool, and whether charon has it
internal class IpsecConnection(val name: String, val file: String, val settings: Map<String, String>, val loaded: Boolean) {
    val client get() = file.substringAfterLast('/').startsWith(CLIENT_PREFIX)
    val own get() = file.substringAfterLast('/').startsWith(PREFIX) && !client

    // the server of L2TP and the connections of the host to such servers, which the window of L2TP keeps
    val l2tp get() = file.substringAfterLast('/').startsWith(L2TP_PREFIX)
    val serverId get() = settings["local.id"] ?: settings.entries.firstOrNull { it.key.startsWith("local") && it.key.endsWith(".id") }?.value.orEmpty()
    val pool get() = settings["pool.addrs"].orEmpty()
    val pool6 get() = settings["pool6.addrs"].orEmpty()
    val dns get() = settings["pool.dns"].orEmpty()
    val localTs get() = settings.entries.firstOrNull { it.key.startsWith("children.") && it.key.endsWith(".local_ts") }?.value.orEmpty()

    // the options of a server made here, by the fields of SERVER_OPTIONS, as its file writes them
    fun serverOptions() = mapOf(
        "proposals" to settings["proposals"].orEmpty(), "esp-proposals" to settings["children.$name.esp_proposals"].orEmpty(),
        "dpd-delay" to settings["dpd_delay"].orEmpty(), "rekey-time" to settings["rekey_time"].orEmpty(), "child-rekey-time" to settings["children.$name.rekey_time"].orEmpty(),
        "mobike" to settings["mobike"].orEmpty(), "fragmentation" to settings["fragmentation"].orEmpty(), "unique" to settings["unique"].orEmpty(),
        "encap" to settings["encap"].orEmpty(), "send-cert" to settings["send_cert"].orEmpty(),
    )

    // a client connection made here by the fields of its form; the secret stays in its file
    fun clientValues() = mapOf(
        "server" to settings["remote_addrs"].orEmpty(), "server-id" to settings["remote.id"].orEmpty(),
        "auth" to if (settings["local.auth"] == "psk") "psk" else "eap-mschapv2", "user" to settings["local.eap_id"].orEmpty(),
        "networks" to settings["children.$name.remote_ts"].orEmpty(), "local-networks" to settings["children.$name.local_ts"].orEmpty(),
        "vip" to if (settings["vips"] != null) "yes" else "no", "ca-file" to if (settings["remote.cacerts"] != null) clientCa(name) else "",
    )

    fun clientRow(sa: String) = Item(name, linkedMapOf("name" to name, "loaded" to "$loaded", "state" to sa) + clientValues() + mapOf("secret" to "", "file" to file))
}

// what the host has of strongSwan at one read: the connections of the files, the users of EAP, the service of charon, whether it starts at
// boot, and the servers whose NAT ufw or firewalld keeps, as ipsec-<name>
internal class IpsecState(
    val connections: List<IpsecConnection>, val users: List<Pair<String, Boolean>>, val unit: String?, val active: Boolean, val enabled: Boolean, val nat: Set<String>,
) {
    // mobike and fragmentation of charon are on and encap off where the file says nothing
    fun rows(): List<Item> = connections.map { connection ->
        val auth = { side: String -> connection.settings.entries.firstOrNull { it.key.startsWith(side) && it.key.endsWith(".auth") }?.value.orEmpty() }
        val options = connection.serverOptions()
        Item(
            connection.name,
            linkedMapOf(
                "name" to connection.name, "loaded" to "${connection.loaded}", "version" to connection.settings["version"].orEmpty(),
                "local-auth" to auth("local"), "remote-auth" to auth("remote"), "server-id" to connection.serverId, "pool" to connection.pool, "pool6" to connection.pool6,
                "dns" to connection.dns, "local-ts" to connection.localTs, "remote-addrs" to connection.settings["remote_addrs"].orEmpty(), "autostart" to "$enabled",
                "nat" to "${"ipsec-${connection.name}" in nat}",
            ) + options + linkedMapOf(
                "mobike" to "${options["mobike"] != "no"}", "fragmentation" to options["fragmentation"].orEmpty().ifEmpty { "yes" }, "encap" to "${options["encap"] == "yes"}",
                "kept" to if (connection.own || connection.client) "NetworkHelm" else "", "file" to connection.file,
            ),
        )
    }
}

// the files of swanctl with their secrets masked on the host, the connections charon has loaded and the service that starts it
internal fun parseIpsec(text: String): IpsecState {
    val files = mutableListOf<Pair<String, MutableList<String>>>()
    val names = mutableListOf<String>()
    val nat = mutableSetOf<String>()
    val units = mutableListOf<List<String>>()
    var enabled = false
    var body: MutableList<String>? = null
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        if (!mark.startsWith("--nh-")) {
            body?.add(line)
            continue
        }
        body = null
        val rest = line.substringAfter(' ', "").trim()
        when (mark) {
            "--nh-file--" -> body = mutableListOf<String>().also { files += rest to it }
            "--nh-loaded--" -> body = names
            "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            "--nh-unit--" -> units += rest.split(' ')
            "--nh-boot--" -> enabled = rest == "enabled"
        }
    }
    // a unit as <name> <active> <file> <enabled>, - for the service of OpenRC; strongswan-swanctl.service is an alias of strongswan.service
    // on Debian 12 and Ubuntu 24.04, which systemctl enable refuses
    val service = units.firstOrNull { (it[0] == "-" || UNIT.matches(it[0])) && it.getOrNull(2) != "alias" }
    val loaded = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val connections = files.flatMap { (file, lines) ->
        val settings = swanctlSettings(lines)
        val pools = settings.keys.filter { it.startsWith("pools.") }.map { it.split('.')[1] }.distinct()
        settings.keys.filter { it.startsWith("connections.") }.map { it.split('.')[1] }.distinct().map { name ->
            val own = settings.filterKeys { it.startsWith("connections.$name.") }.mapKeys { it.key.removePrefix("connections.$name.") }
            val named = own["pools"].orEmpty().split(',').map { it.trim() }.filter { it in pools }
            val first = named.firstOrNull { pool -> settings["pools.$pool.addrs"]?.let { ':' !in it } == true }
            val six = named.firstOrNull { pool -> settings["pools.$pool.addrs"]?.contains(':') == true }
            val extra = listOfNotNull(
                first?.let { "pool.addrs" to settings["pools.$it.addrs"].orEmpty() }, first?.let { "pool.dns" to settings["pools.$it.dns"].orEmpty() },
                six?.let { "pool6.addrs" to settings["pools.$it.addrs"].orEmpty() },
            )
            IpsecConnection(name, file, own + extra, name in loaded)
        }
    }
    val users = files.firstOrNull { it.first == USERS }?.second.orEmpty().let { parseSecrets(it.joinToString("\n")) }.map { it.name to it.disabled }
    return IpsecState(
        connections, users, service?.get(0)?.takeIf { it != "-" }, service?.getOrNull(1) == "active", service?.getOrNull(3)?.let { it == "enabled" } ?: enabled, nat,
    )
}

// the settings of a file of swanctl by their paths, local.id under connections.<name>; comments, and with them what is off, are none
private fun swanctlSettings(lines: List<String>): Map<String, String> {
    val path = ArrayDeque<String>()
    val found = linkedMapOf<String, String>()
    for (line in lines) {
        val text = line.trim()
        if (text.startsWith("#")) continue
        when {
            text.endsWith("{") -> path.addLast(text.removeSuffix("{").trim())
            text == "}" -> path.removeLastOrNull()
            '=' in text -> found[(path + text.substringBefore('=').trim()).joinToString(".")] = text.substringAfter('=').trim()
        }
    }
    return found
}

// a user of EAP in the file of NetworkHelm: eap-<name> with its id and secret, all lines behind #[disabled] for one turned off
internal class EapSecret(val name: String, val secret: String, val disabled: Boolean)

internal fun parseSecrets(text: String): List<EapSecret> {
    val found = mutableListOf<EapSecret>()
    var name: String? = null
    var off = false
    var secret = ""
    for (line in text.lines()) {
        val disabled = line.startsWith(OFF)
        val text = line.removePrefix(OFF).trim()
        when {
            text.startsWith("eap-") && text.endsWith("{") -> {
                name = text.removePrefix("eap-").removeSuffix("{").trim()
                off = disabled
                secret = ""
            }
            text.startsWith("secret") && '=' in text -> secret = swanSecret(text)
            text == "}" && name != null -> {
                found += EapSecret(name, secret, off)
                name = null
            }
        }
    }
    return found
}

private fun profile(kind: String, server: IpsecConnection, user: String, ca: String, uuids: List<String>): String = when (kind) {
    "sswan" -> buildJsonObject {
        put("uuid", uuids.getOrElse(0) { "" })
        put("name", server.name)
        put("type", "ikev2-eap")
        put("remote", buildJsonObject {
            put("addr", server.serverId)
            put("cert", ca)
        })
        put("local", buildJsonObject { put("eap_id", user) })
    }.toString()
    "windows" -> (
        listOfNotNull("# $PROFILE_GAP".takeUnless { fitsProfiles(server) }) + listOf(
            "# ${server.name}: IKEv2 with EAP-MSCHAPv2, in PowerShell as an administrator",
            "\$ca = Join-Path \$env:TEMP '${server.name}-ca.cer'",
            "[IO.File]::WriteAllBytes(\$ca, [Convert]::FromBase64String('$ca'))",
            "Import-Certificate -FilePath \$ca -CertStoreLocation Cert:\\LocalMachine\\Root | Out-Null",
            "Add-VpnConnection -Name '${server.name}' -ServerAddress '${server.serverId}' -TunnelType IKEv2 -AuthenticationMethod EAP -EncryptionLevel Required -RememberCredential -Force",
            "Set-VpnConnectionIPsecConfiguration -ConnectionName '${server.name}' -AuthenticationTransformConstants SHA256128 -CipherTransformConstants AES256 " +
                "-EncryptionMethod AES256 -IntegrityCheckMethod SHA256 -DHGroup Group14 -PfsGroup PFS2048 -Force",
        )
        ).joinToString("\n")
    else -> MOBILECONFIG.replace("{name}", server.name).replace("{server}", server.serverId).replace("{user}", user).replace("{ca}", ca)
        .replace("{uuid1}", uuids.getOrElse(0) { "" }).replace("{uuid2}", uuids.getOrElse(1) { "" }).replace("{uuid3}", uuids.getOrElse(2) { "" })
        .let { if (fitsProfiles(server)) it else it.replaceFirst("\n", "\n<!-- $PROFILE_GAP -->\n") }
}

// whether the proposals of a server take those the profiles for Windows, macOS and iOS ask for: AES256 with SHA256 and group 14, for
// ESP with PFS; default of IKE holds them. Proposals that leave them out stand, as other clients may need just those, and the profile
// says it
private fun fitsProfiles(server: IpsecConnection): Boolean {
    fun takes(list: String, ike: Boolean) = list.isEmpty() || items(list).any { proposal ->
        ike && proposal == "default" || proposal.split('-').let { "aes256" in it && ("sha256" in it || "sha2_256" in it) && "modp2048" in it }
    }
    return takes(server.settings["proposals"].orEmpty(), true) && takes(server.settings["children.${server.name}.esp_proposals"].orEmpty(), false)
}

private const val PROFILE_GAP = "the proposals of this server leave out AES256 with SHA256 and group 14, which this profile asks for: the device does not connect until they are back"

private fun login(server: IpsecConnection, user: String, secret: String) = "${server.name}: IKEv2, EAP-MSCHAPv2\nserver: ${server.serverId}\nlogin: $user\npassword: $secret"

// what a profile of a server of IKEv2 tells its client: the server, the login and the CA in base64 out of a .sswan or a .mobileconfig
// as this window writes them, or the CA alone out of a certificate in PEM
internal class IkeProfile(val server: String, val user: String, val ca: String) {
    fun pem() = "-----BEGIN CERTIFICATE-----\n${ca.chunked(64).joinToString("\n")}\n-----END CERTIFICATE-----"
}

internal fun parseIkeProfile(text: String): IkeProfile? {
    val found = when {
        text.trimStart().startsWith("{") -> {
            val json = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return null
            val remote = json["remote"] as? JsonObject ?: return null
            IkeProfile(remote.text("addr"), (json["local"] as? JsonObject)?.text("eap_id").orEmpty(), remote.text("cert"))
        }
        "<plist" in text -> IkeProfile(
            PLIST_SERVER.find(text)?.groupValues?.get(1).orEmpty(), PLIST_USER.find(text)?.groupValues?.get(1).orEmpty(), PLIST_CA.find(text)?.groupValues?.get(1).orEmpty(),
        )
        else -> IkeProfile("", "", PEM_CERT.find(text)?.groupValues?.get(1).orEmpty())
    }
    val ca = found.ca.filterNot { it.isWhitespace() }
    return if (BASE64.matches(ca)) IkeProfile(found.server, found.user, ca) else null
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun items(text: String) = text.split(LIST).filter { it.isNotEmpty() }

// the first name of the base given, then with -2, -3 and on, that no connection of the host has
private fun freeName(host: IpsecState, base: String) =
    (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first { free -> host.connections.none { it.name == free } }

internal fun noNat(name: String) = DeviceException(ErrorCategory.Unsupported, "NAT for the clients of $name needs ufw, firewalld or systemd")

// a secret of swanctl in quotes, and the value of its line secret = "..." again
private fun swanQuoted(secret: String) = "\"${secret.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun swanSecret(line: String) = line.substringAfter('=').trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")

private fun conf(name: String) = "/etc/swanctl/conf.d/$PREFIX$name.conf"

private fun clientConf(name: String) = "/etc/swanctl/conf.d/$CLIENT_PREFIX$name.conf"

private fun clientCa(name: String) = "/etc/swanctl/x509ca/$CLIENT_PREFIX$name-ca.crt"

// the IKE SAs of a connection ended; swanctl fails with no matching SAs where none is left, which is what was asked for. A listed SA
// may be gone by the time of --terminate, as one after a failed initiate was in the lab on 18.11
internal fun ikeTerminate(name: String) = "if command -v swanctl >/dev/null; then o=\$(swanctl --terminate --ike ${quote(name)} --force 2>&1) || " +
    "case \"\$o\" in *'no matching SAs'*) ;; *) printf '%s\\n' \"\$o\" >&2; false;; esac; fi"

// the file of the IPsec of L2TP, which the window of L2TP keeps
internal const val L2TP_IPSEC = "/etc/swanctl/conf.d/networkhelm-l2tp.conf"

// the name of the server of L2TP, and with -<name> after it that of a connection of the host to one: its IPsec, unit, files and lines
// of chap-secrets
internal const val L2TP_PREFIX = "networkhelm-l2tp"

private const val L2TP_PROPOSALS = "aes256-sha256-modp2048,aes256-sha1-modp2048,aes256-sha1-modp1024,aes128-sha1-modp1024,3des-sha1-modp1024,default"
private const val L2TP_ESP = "aes256-sha256,aes256-sha1,aes128-sha1,3des-sha1,default"

private fun l2tpClientConf(name: String) = "/etc/swanctl/conf.d/$L2TP_PREFIX-$name.conf"

private const val PREFIX = "networkhelm-"
private const val USERS = "/etc/swanctl/conf.d/networkhelm-users.conf"
private const val OFF = "#[disabled] "
private const val PART = "--nh-part--"

private val NAME = Regex("[A-Za-z0-9_-]{1,32}")

// the names whose files are those of the users of EAP, of the clients of the host and of L2TP
private val RESERVED = Regex("users|client-.*|l2tp.*")
private val HOST = Regex("[A-Za-z0-9.:-]+")
private val CLIENT_ID = Regex("[A-Za-z0-9.:@_=-]{1,128}")
private val CA_FILE = Regex("/[A-Za-z0-9._/+-]+")
private val NETWORK = Regex("[0-9A-Fa-f.:]+/\\d{1,3}")
private val NETWORK6 = Regex("[0-9A-Fa-f:]+/\\d{1,3}")
private val LIST = Regex("[,;\\s]+")
private val UNIQUE = Regex("uniqueid=(\\d+)")
private val EAP_PLUGINS = setOf("eap-identity", "eap-mschapv2")
private val UNIT = Regex("strongswan(-swanctl)?\\.service")
private val IKE_PORTS = listOf("500/udp", "4500/udp")
private const val SA_EVENT = "list-sa event {"

// a name of a user that every server of the VPN takes, as the window of users checks it
internal val VPN_USER = Regex("[A-Za-z0-9_.@-]{1,64}")

// the profiles a new server gives its first user, by the kinds of export and the extensions of their files
private val EXTENSIONS = mapOf("mobileconfig" to "mobileconfig", "sswan" to "sswan", "windows" to "ps1")

private val PEM_CERT = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
private val PLIST_SERVER = Regex("<key>RemoteAddress</key>\\s*<string>([^<]*)</string>")
private val PLIST_USER = Regex("<key>AuthName</key>\\s*<string>([^<]*)</string>")
private val PLIST_CA = Regex("<data>([^<]*)</data>")
private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

// IKE_SA rw[1] established between 192.168.10.250[192.168.10.250]...192.168.10.249[192.168.10.249]
private val IKE_UP = Regex("^IKE_SA (\\S+)\\[(\\d+)\\] established between .*?\\.\\.\\.([^\\[]+)\\[([^\\]]*)\\]")

// deleting IKE_SA rw[1] between ..., and received DELETE for IKE_SA rw[1] where the client leaves
private val IKE_DOWN = Regex("^(?:deleting IKE_SA|received DELETE for IKE_SA) (\\S+)\\[(\\d+)\\]")
private val IKE_IDENTITY = Regex("^received EAP identity '([^']*)'")
private val PAIR = Regex("([A-Za-z0-9_-]+)=(\\[[^\\]]*\\]|[^ {}\\]]+)")
private val CHILD = Regex("([^ {}]+) \\{([^{}]*)\\}")

private const val IKE_PROPOSALS = "aes256gcm16-prfsha256-ecp256,aes256-sha256-modp2048,aes256-sha256-ecp256,default"
private const val ESP_PROPOSALS = "aes256gcm16,aes256-sha256-modp2048,aes256-sha256,default"

// the options of a server beside its pools, as the fields of its form
private val SERVER_OPTIONS = listOf("proposals", "esp-proposals", "dpd-delay", "rekey-time", "child-rekey-time", "mobike", "fragmentation", "unique", "encap", "send-cert")

// what swanctl takes for an option, checked before the file; mobike and encap come as yes or no
private val OPTION_VALUES = mapOf(
    "proposals" to Regex("[a-z0-9_]+(-[a-z0-9_]+)*(,[a-z0-9_]+(-[a-z0-9_]+)*)*"), "esp-proposals" to Regex("[a-z0-9_]+(-[a-z0-9_]+)*(,[a-z0-9_]+(-[a-z0-9_]+)*)*"),
    "dpd-delay" to Regex("\\d+[smhd]?"), "rekey-time" to Regex("\\d+[smhd]?"), "child-rekey-time" to Regex("\\d+[smhd]?"),
    "fragmentation" to Regex("yes|accept|force|no"), "unique" to Regex("never|no|replace|keep"), "send-cert" to Regex("always|ifasked"),
)

private val CONNECTION = listOf(
    "name", "loaded", "version", "local-auth", "remote-auth", "server-id", "pool", "pool6", "dns", "local-ts", "remote-addrs", "autostart", "nat",
) + SERVER_OPTIONS + listOf("kept", "file")
private val CLIENT = listOf("name", "loaded", "state", "server", "server-id", "auth", "user", "networks", "local-networks", "vip", "ca-file", "secret", "file")
private val CLIENT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("server", ValueType.Text(), placeholder = "vpn.example.net"),
    Argument("auth", ValueType.Enumeration(closed = true), listOf("eap-mschapv2", "psk"), optional = true, placeholder = "eap-mschapv2"),
    Argument("user", ValueType.Text(), optional = true),
    Argument("secret", ValueType.Secret, optional = true),
    Argument("server-id", ValueType.Text(), optional = true),
    Argument("ca-file", ValueType.Text(), optional = true, placeholder = "/etc/ssl/certs/ISRG_Root_X1.pem"),
    Argument("networks", ValueType.Text(), placeholder = "10.10.0.0/16"),
    Argument("local-networks", ValueType.Text(), optional = true),
    Argument("vip", ValueType.YesNo, optional = true),
)
// the options of a server in its form and in the window of a new one, where tab puts them
private val OPTION_ARGUMENTS = listOf(
    Argument("unique", ValueType.Enumeration(closed = true), listOf("never", "no", "replace", "keep"), optional = true, label = "Unique IDs", tab = "general", placeholder = "never"),
    Argument("mobike", ValueType.YesNo, optional = true, label = "MOBIKE", tab = "network"),
    Argument("fragmentation", ValueType.Enumeration(closed = true), listOf("yes", "accept", "force", "no"), optional = true, label = "IKE Fragmentation", tab = "network", placeholder = "yes"),
    Argument("encap", ValueType.YesNo, optional = true, label = "Force UDP Encapsulation", tab = "network"),
    Argument("dpd-delay", ValueType.Text(), optional = true, label = "DPD Delay", tab = "network", placeholder = "30s"),
    Argument("proposals", ValueType.Text(), optional = true, label = "IKE Proposals", tab = "keys", placeholder = IKE_PROPOSALS),
    Argument("esp-proposals", ValueType.Text(), optional = true, label = "ESP Proposals", tab = "keys", placeholder = ESP_PROPOSALS),
    Argument("rekey-time", ValueType.Text(), optional = true, label = "IKE Rekey Time", tab = "keys", placeholder = "4h"),
    Argument("child-rekey-time", ValueType.Text(), optional = true, label = "CHILD Rekey Time", tab = "keys", placeholder = "1h"),
    Argument("send-cert", ValueType.Enumeration(closed = true), listOf("always", "ifasked"), optional = true, label = "Send Certificate", tab = "keys", placeholder = "always"),
)

private val ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("server-id", ValueType.Text(), fixed = true, placeholder = "vpn.example.net"),
    Argument("pool", ValueType.Ip4Prefix, optional = true, placeholder = "10.10.10.0/24"),
    Argument("pool6", ValueType.Ip6Prefix, optional = true),
    Argument("dns", ValueType.Text(), optional = true, placeholder = "1.1.1.1"),
    Argument("local-ts", ValueType.Text(), optional = true, placeholder = "0.0.0.0/0"),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
) + OPTION_ARGUMENTS.map { it.copy(label = null, tab = null) }

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes
private val SERVER_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name", tab = "general"),
    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("pool", ValueType.Ip4Prefix, label = "IPv4 Pool", tab = "network", placeholder = "10.10.10.0/24"),
    Argument("pool6", ValueType.Ip6Prefix, optional = true, label = "IPv6 Pool", tab = "network"),
    Argument("dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = CLIENT_DNS),
    Argument("local-ts", ValueType.Text(), optional = true, label = "Networks for Clients", tab = "dns", placeholder = "0.0.0.0/0"),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("user", ValueType.Text(), optional = true, label = "First User", tab = "clients"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", tab = "clients"),
) + OPTION_ARGUMENTS

private val IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("server", ValueType.Text(), optional = true, label = "Server", placeholder = "from the profile"),
    Argument("user", ValueType.Text(), optional = true, label = "Login", placeholder = "from the profile"),
    Argument("password", ValueType.Secret, label = "Password"),
    Argument("networks", ValueType.Text(), label = "Networks Through VPN", placeholder = "0.0.0.0/0"),
    Argument("text", ValueType.Text(), label = "Profile"),
)

// the configurations of strongSwan by their files, with their secrets masked, the names of the connections charon has, and its service
private val IPSEC_STATE = $$"""echo --nh-loaded--
swanctl --list-conns 2>/dev/null | sed -n 's/^\([^ :][^:]*\): .*/\1/p'
for f in /etc/swanctl/swanctl.conf /etc/swanctl/conf.d/*.conf; do [ -f "$f" ] && { echo "--nh-file-- $f"; awk '{ l = $0; sub(/^(#\[disabled\] )?[ \t]+/, "", l) } l ~ /^secret[ \t]*=/ { sub(/=.*/, "= (hidden)") } { print }' "$f"; }; done
systemctl list-unit-files --no-legend 'strongswan*.service' 2>/dev/null | while read -r u f p; do echo "--nh-unit-- $u $(systemctl is-active "$u" 2>/dev/null) $f $(systemctl is-enabled "$u" 2>/dev/null)"; done
[ ! -e /etc/init.d/strongswan ] || echo "--nh-unit-- - $(rc-service strongswan status >/dev/null 2>&1 && echo active) service $([ -e /etc/runlevels/default/strongswan ] && echo enabled)"
for c in /etc/systemd/system/strongswan*.service.d/networkhelm-nat-ipsec-*.conf; do [ -f "$c" ] && { c=${c##*/}; echo "--nh-nat-- ${c%.conf}"; }; done
$$NAT_STATE
true"""

private const val MOBILECONFIG = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadType</key><string>com.apple.security.root</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadIdentifier</key><string>networkhelm.{name}.ca</string>
      <key>PayloadUUID</key><string>{uuid1}</string>
      <key>PayloadDisplayName</key><string>{name} CA</string>
      <key>PayloadContent</key><data>{ca}</data>
    </dict>
    <dict>
      <key>PayloadType</key><string>com.apple.vpn.managed</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadIdentifier</key><string>networkhelm.{name}.vpn</string>
      <key>PayloadUUID</key><string>{uuid2}</string>
      <key>PayloadDisplayName</key><string>{name}</string>
      <key>UserDefinedName</key><string>{name}</string>
      <key>VPNType</key><string>IKEv2</string>
      <key>IKEv2</key>
      <dict>
        <key>RemoteAddress</key><string>{server}</string>
        <key>RemoteIdentifier</key><string>{server}</string>
        <key>LocalIdentifier</key><string>{user}</string>
        <key>AuthenticationMethod</key><string>None</string>
        <key>ExtendedAuthEnabled</key><integer>1</integer>
        <key>AuthName</key><string>{user}</string>
        <key>ServerCertificateIssuerCommonName</key><string>{name} CA</string>
        <key>ServerCertificateCommonName</key><string>{server}</string>
        <key>IKESecurityAssociationParameters</key>
        <dict>
          <key>EncryptionAlgorithm</key><string>AES-256</string>
          <key>IntegrityAlgorithm</key><string>SHA2-256</string>
          <key>DiffieHellmanGroup</key><integer>14</integer>
        </dict>
        <key>ChildSecurityAssociationParameters</key>
        <dict>
          <key>EncryptionAlgorithm</key><string>AES-256</string>
          <key>IntegrityAlgorithm</key><string>SHA2-256</string>
          <key>DiffieHellmanGroup</key><integer>14</integer>
        </dict>
        <key>EnablePFS</key><integer>1</integer>
        <key>DeadPeerDetectionRate</key><string>Medium</string>
      </dict>
    </dict>
  </array>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadVersion</key><integer>1</integer>
  <key>PayloadIdentifier</key><string>networkhelm.{name}</string>
  <key>PayloadUUID</key><string>{uuid3}</string>
  <key>PayloadDisplayName</key><string>{name}</string>
</dict>
</plist>"""
