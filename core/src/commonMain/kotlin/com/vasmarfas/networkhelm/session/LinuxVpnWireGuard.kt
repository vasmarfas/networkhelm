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

// WireGuard of a Linux host: the interfaces wg-quick, NetworkManager and systemd-networkd keep in their files, their peers and
// what wg shows of them running; of the kind AmneziaWG the same through awg and awg-quick, with the keys of the disguise in
// [Interface]. The host reads its files back with the private and preshared keys masked and makes keys itself; a file with keys
// goes to it as input, so no command line and no log of sudo holds one. Bringing an interface up and a change of what a running
// one routes go under the guard: either can take the way this session comes by
internal class LinuxVpnWireGuard(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val guard: LinuxGuard,
    private val kind: WgKind = WIREGUARD,
) {
    private val nat = LinuxVpnNat(root, tools, guard)
    private val disguise = kind.disguise.map { it.lowercase() }
    private val settings = SETTINGS + disguise

    val tables: Map<String, HostTable> = mapOf(
        kind.path to HostTable(
            INTERFACE + disguise, { state().interfaces(localAddress(ssh)) }, slow = true, arguments = { INTERFACE_ARGUMENTS + disguiseArguments() }, add = ::addInterface,
            set = ::setInterface, remove = ::removeInterfaces, commands = listOf("up", "down"), run = ::bring, present = { kind.tool in tools() },
        ),
        "${kind.path}/peer" to HostTable(
            PEER, { state().peers() }, slow = true, arguments = ::peerArguments, add = ::addPeer, set = ::setPeer, remove = ::removePeers,
            commands = listOf("client-config"), run = { _, arguments -> clientConfig(arguments) }, present = { kind.tool in tools() },
        ),
        "${kind.path}/server" to HostTable(
            SERVER_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { SERVER_ARGUMENTS + disguiseArguments() },
            commands = listOf("create"), run = { _, values -> create(values) }, present = { kind.quick in tools() },
        ),
        "${kind.path}/import" to HostTable(
            IMPORT_ARGUMENTS.map { it.name },
            { listOf(Item("", mapOf("name" to freeName(state(), ssh.scan(root), kind.tool), "autostart" to "true", "keep-session" to "true"))) },
            settings = true, arguments = { IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importConfig(values) }, present = { kind.quick in tools() },
        ),
    )

    suspend fun state() = parseWireGuard(root.masked(wireGuardState(kind)), kind)

    // the keys of the disguise the awg of the host takes; WireGuard has none
    private suspend fun knows(): Set<String> = if (disguise.isEmpty()) emptySet() else awgKnows(root.masked(AWG_USAGE))

    private suspend fun disguiseArguments(): List<Argument> = if (disguise.isEmpty()) emptyList() else knows().let { known -> DISGUISE_ARGUMENTS.filter { it.name in known } }

    // a new interface is a file of wg-quick, with a private key the host makes and the first free port from 51820 unless they are given,
    // as a server whose port changes at each start loses its clients; it comes up at once, its port open where ufw or firewalld keeps
    // the rules, as an interface left down is a server nobody reaches, and starts at boot only once the guard let it come up
    private suspend fun addInterface(values: Map<String, String>): String {
        if (kind.quick !in tools()) throw DeviceException(ErrorCategory.Unsupported, "a new interface goes to ${kind.quick}, and the host has none")
        val name = values["name"].orEmpty()
        if (!NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is no name of an interface")
        val host = state()
        if (host.faces.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has the ${kind.title} interface $name already")
        val given = values.filterKeys { it in settings }.filterValues { it.isNotEmpty() }
        given.forEach { (field, value) -> checkSetting(field, value) }
        if (given.keys.any { it in disguise }) checkDisguise(given.filterKeys { it in disguise }, knows())
        if ("dns" in given) needResolvconf()
        if (values["nat"] in ValueType.Flag.on && networks(given["address"].orEmpty()).isEmpty()) {
            throw DeviceException(ErrorCategory.ArgumentValue, "NAT for the clients of $name needs an address of the interface")
        }
        val face = WgFace(name, name, WgSource.Quick, "${kind.dir}/$name.conf", WgIni("[Interface]\n"), "", null, kind)
        val port = given["listen-port"] ?: freePort(host, ssh.scan(root))
        face.put(face.ini, null, "private-key", given["private-key"]?.takeIf { it != AUTO }?.let { wgKey("private-key", it) } ?: newKey())
        face.put(face.ini, null, "listen-port", port)
        for ((field, value) in given - "private-key" - "listen-port") face.put(face.ini, null, field, value)
        root.write(face.file, face.ini.text(), face.checkLine())
        if (values["nat"] in ValueType.Flag.on) setNat(face, true)
        nat.open(name, listOf("$port/udp"))
        guard.guarded(face.backup()) { root.masked(face.upLine()) }
        if (values["autostart"] in ValueType.Flag.on) autostart(host, face, true)
        return name
    }

    // an empty private key leaves the one the interface has, auto has the host make a new one; the open port follows the port of the
    // interface and each change of its NAT
    private suspend fun setInterface(id: String?, changes: Map<String, String?>) {
        val host = state()
        val face = host.face(id)
        val fields = changes.filterKeys { it in settings }.mapValues { it.value.orEmpty() }.filterNot { it.key == "private-key" && it.value.isEmpty() }
        fields.forEach { (field, value) -> if (value.isNotEmpty()) checkSetting(field, value) }
        if (fields.keys.any { it in disguise }) checkDisguise(disguise.associateWith { fields[it] ?: face.setting(it) }, knows())
        if (fields["dns"].orEmpty().isNotEmpty() && face.source == WgSource.Quick) needResolvconf()
        if (fields.isNotEmpty()) {
            val key = fields["private-key"]?.let { if (it == AUTO) newKey() else wgKey("private-key", it) }
            change(host, face) { file, network ->
                for ((field, value) in fields) face.put(file, network, field, if (field == "private-key") key.orEmpty() else value)
                face.settingsApply(fields, key)
            }
        }
        changes["autostart"]?.let { autostart(host, face, it in ValueType.Flag.on) }
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on } ?: true.takeIf { "address" in fields && host.natted(face) }
        natOn?.let { setNat(face, it) }
        if (natOn != null || "listen-port" in fields) nat.open(face.name, listOfNotNull((fields["listen-port"] ?: face.setting("listen-port")).ifEmpty { null }?.let { "$it/udp" }))
    }

    // an interface goes down first and with it whatever its source starts it by; a .netdev of the distribution stays
    private suspend fun removeInterfaces(ids: List<String>) {
        val host = state()
        val faces = ids.map(host::face)
        for (face in faces) {
            if (face.name in host.live) keepSession(ssh, face.name)
            if (face.source == WgSource.Networkd && !face.file.startsWith("/etc/")) throw outside(face.file)
        }
        for (face in faces) {
            if (host.managed(face)) nat.manager()?.let { nat.set(it, face.name, emptyList(), emptyList(), false) }
            nat.open(face.name, emptyList())
            val n = quote(face.name)
            val q = kind.quick
            val gone = when (face.source) {
                WgSource.Quick -> "{ systemctl disable $q@$n 2>/dev/null; rc-update del $q.$n default 2>/dev/null; rm -f /etc/init.d/$q.$n; true; } && rm -f ${quote(face.file)}"
                WgSource.Manager -> "nmcli connection delete ${face.ref()}"
                WgSource.Networkd -> "rm -f ${quote(face.file)}" +
                    face.network?.takeIf { it.file.startsWith("/etc/") && networkdNames(it.ini.text()) == listOf(face.name) }?.let { " ${quote(it.file)}" }.orEmpty() +
                    " && networkctl reload && { ip link del $n 2>/dev/null; true; }"
                WgSource.Running -> "true"
            }
            root.masked(listOfNotNull(face.downLine().takeIf { face.name in host.live }, gone, "rm -rf ${quote("${kind.kept}/${face.name}")}").joinToString(" && "))
        }
    }

    // up goes under the guard, down keeps the interface this session came in by
    private suspend fun bring(command: String, arguments: Map<String, String>): List<Item> {
        val host = state()
        for (face in ids(arguments["numbers"]).map(host::face)) {
            val running = face.name in host.live
            when (command) {
                "up" -> if (!running) guard.guarded(face.backup()) { root.masked(face.upLine()) }
                "down" -> if (running) {
                    keepSession(ssh, face.name)
                    root.masked(face.downLine())
                }
            }
        }
        return emptyList()
    }

    // wg-quick starts by its unit of systemd or the service of OpenRC Alpine makes of its link, NetworkManager by autoconnect, and
    // systemd-networkd brings an interface up unless its .network holds it back. A quick tool no package gives a service of OpenRC, as
    // awg-quick, gets the one of its kind on a host of OpenRC
    private suspend fun autostart(host: WireGuardState, face: WgFace, on: Boolean) {
        val n = quote(face.name)
        val q = kind.quick
        when (face.source) {
            WgSource.Quick -> root.masked(
                when {
                    "systemctl" in tools() -> "systemctl ${if (on) "enable" else "disable"} $q@$n"
                    !host.openRc && (kind.service == null || !on) -> throw DeviceException(ErrorCategory.Unsupported, "autostart: the host has neither systemd nor the OpenRC service of $q")
                    on -> {
                        if (!host.openRc) root.write("/etc/init.d/$q", kind.service.orEmpty(), "[ -d /etc/runlevels ] || { echo '$NO_OPENRC'; exit 1; }", "755")
                        "ln -sf $q /etc/init.d/$q.$n && rc-update add $q.$n default"
                    }
                    else -> "rc-update del $q.$n default; rm -f /etc/init.d/$q.$n"
                },
            )
            WgSource.Manager -> change(host, face) { file, _ ->
                (file.part("connection") ?: file.add("connection")).set("autoconnect", if (on) "true" else "false", "=")
                WgApply()
            }
            WgSource.Networkd -> change(host, face) { _, network ->
                network?.let { (it.part("Link") ?: it.add("Link")).set("ActivationPolicy", if (on) "" else "manual", "=") }
                WgApply()
            }
            WgSource.Running -> throw notKept(face)
        }
    }

    // NAT through ufw or firewalld where one keeps the rules, else hooks of wg-quick; forwarding comes on with it
    private suspend fun setNat(face: WgFace, on: Boolean) {
        val host = state()
        val current = host.face(face.id)
        val manager = nat.manager()
        if (!on) {
            if (manager != null && host.managed(current)) nat.set(manager, current.name, emptyList(), emptyList(), false)
            if (current.hookedNat()) quickNat(host, current, emptyList(), false)
            return
        }
        val networks = networks(current.setting("address"))
        if (networks.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "NAT for the clients of ${current.name} needs an address of the interface")
        when {
            manager != null -> {
                nat.forward(networks.any { ':' in it })
                nat.set(manager, current.name, networks, emptyList(), true)
            }
            current.source == WgSource.Quick -> {
                nat.forward(networks.any { ':' in it })
                quickNat(host, current, networks, true)
            }
            else -> throw DeviceException(ErrorCategory.Unsupported, "NAT for the clients of ${current.name} needs ufw or firewalld, or an interface of wg-quick")
        }
    }

    // a running interface drops the rules of its old hooks and takes the new ones at once
    private suspend fun quickNat(host: WireGuardState, face: WgFace, networks: List<String>, on: Boolean) {
        val (up, down) = natHooks(networks, "iptables-save" in tools())
        change(host, face) { file, _ ->
            val part = file.part("Interface") ?: file.add("Interface")
            val stale = part.values("PostDown").filter { NAT_MARK in it }
            for ((key, hook) in listOf("PostUp" to up, "PostDown" to down)) part.set(key, part.values(key).filter { NAT_MARK !in it } + listOfNotNull(hook.takeIf { on }), " = ")
            WgApply(stale.map { "{ ${hookLine(it, face.name)}; true; }" } + listOfNotNull(hookLine(up, face.name).takeIf { on }))
        }
    }

    private suspend fun peerArguments(): List<Argument> {
        val faces = state().faces.filter { it.source != WgSource.Running }.map { it.id }
        return listOf(Argument("interface", ValueType.Enumeration(closed = true), faces, default = faces.firstOrNull(), fixed = true)) + PEER_ARGUMENTS
    }

    // a peer without a public key gets a pair the host makes, the private key kept there for its configuration, or until the
    // configuration is shown once, and a preshared key unless the field is given empty; without allowed-ips it gets the next free
    // address of the interface. A device with keys of its own gets no preshared key it was not given, as it would not know it
    suspend fun addPeer(values: Map<String, String>): String {
        val host = state()
        val face = host.face(values["interface"])
        val given = values.filterKeys { it in PEER_SETTINGS }.filterValues { it.isNotEmpty() }
        given.forEach { (field, value) -> checkPeer(field, value) }
        val name = given["name"].orEmpty()
        if (given["public-key"] == null && name.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "name: a value is needed")
        if (name.isNotEmpty() && face.peers().any { it.value("#Name") == name }) throw DeviceException(ErrorCategory.ArgumentValue, "${face.name} has the peer $name already")
        val allowed = given["allowed-ips"] ?: face.nextAddress()
        val psk = (given["preshared-key"] ?: AUTO.takeIf { "preshared-key" !in values && given["public-key"] == null })?.let { presharedKey(it) }
        val kept = if (given["public-key"] == null) "${kind.kept}/${face.name}/$name.${if (values["keep-key"].orEmpty().ifEmpty { "yes" } in ValueType.Flag.on) "key" else "once"}" else null
        val key = given["public-key"]?.let { wgKey("public-key", it) } ?: madeKey(kept.orEmpty())
        try {
            change(host, face) { file, _ ->
                if (face.peer(file, key) != null) throw DeviceException(ErrorCategory.ArgumentValue, "${face.name} has the peer $key already")
                val part = file.add(if (face.source == WgSource.Manager) "$NM_PEER$key" else face.dialect().peer)
                if (name.isNotEmpty()) part.set("#Name", name, "")
                if (face.source != WgSource.Manager) part.set("PublicKey", key, face.dialect().equals)
                psk?.let { face.putPeer(part, "preshared-key", it) }
                face.putPeer(part, "allowed-ips", allowed)
                for (field in listOf("endpoint", "persistent-keepalive") + CLIENT) given[field]?.let { face.putPeer(part, field, it) }
                if (given["disabled"] in ValueType.Flag.on) part.turn(false)
                face.peerApply(file, null, part)
            }
        } catch (e: DeviceException) {
            if (kept != null) root.masked("rm -f ${quote(kept)}")
            throw e
        }
        return "${face.id}/$key"
    }

    // another public key is another peer to the running interface; a renamed peer takes its kept key along
    suspend fun setPeer(id: String?, changes: Map<String, String?>) {
        val host = state()
        val (face, key) = host.peer(id)
        if (changes["interface"]?.let { it != face.id } == true) throw DeviceException(ErrorCategory.Unsupported, "a peer stays with its connection; add it to the other one and remove this one")
        val values = changes.filterKeys { it in PEER_SETTINGS }.mapValues { it.value.orEmpty() }.filterNot { it.key == "preshared-key" && it.value.isEmpty() }
        values.forEach { (field, value) -> if (value.isNotEmpty()) checkPeer(field, value) }
        val newKey = values["public-key"]?.let { wgKey("public-key", it) }?.takeIf { it != key }
        val psk = values["preshared-key"]?.let { presharedKey(it) }
        var renamed: Pair<String, String>? = null
        change(host, face) { file, _ ->
            val old = face.peer(file, key) ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no ${kind.title} peer $id")
            val was = face.was(old)
            val name = old.value("#Name").orEmpty()
            values["name"]?.takeIf { it != name }?.let { new ->
                if (new.isNotEmpty() && face.peers(file).any { it !== old && it.value("#Name") == new }) throw DeviceException(ErrorCategory.ArgumentValue, "${face.name} has the peer $new already")
                if (name.isNotEmpty() && new.isNotEmpty()) renamed = name to new
            }
            val part = if (newKey != null) face.rekey(file, old, newKey) else old
            for ((field, value) in values - "public-key" - "preshared-key") face.putPeer(part, field, value)
            psk?.let { face.putPeer(part, "preshared-key", it) }
            face.peerApply(file, was, part)
        }
        renamed?.let { (from, to) ->
            val old = quote("${kind.kept}/${face.name}/$from")
            val new = quote("${kind.kept}/${face.name}/$to")
            root.masked("for e in key once; do [ ! -e $old.\$e ] || mv -f $old.\$e $new.\$e; done")
        }
    }

    suspend fun removePeers(ids: List<String>) {
        val host = state()
        for ((face, keys) in ids.map(host::peer).groupBy({ it.first }, { it.second })) {
            val names = mutableListOf<String>()
            change(host, face) { file, _ ->
                keys.fold(WgApply()) { apply, key ->
                    val part = face.peer(file, key) ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no ${kind.title} peer ${face.id}/$key")
                    part.value("#Name")?.let { names += it }
                    val was = face.was(part)
                    file.parts.remove(part)
                    apply + face.peerApply(file, was, null)
                }
            }
            if (names.isNotEmpty()) root.masked("rm -f " + names.joinToString(" ") { "${quote("${kind.kept}/${face.name}/$it.key")} ${quote("${kind.kept}/${face.name}/$it.once")}" })
        }
    }

    // the configuration of the device of a peer: the key the host keeps for it, or the one it kept until this showing; what the peer
    // does not set for its device comes from the interface, and the server is as this session reaches it unless either names another.
    // A client of AmneziaWG takes the disguise of the interface whole, as both ends need the same paddings and types of messages
    suspend fun clientConfig(arguments: Map<String, String>): List<Item> {
        val host = state()
        val (face, key) = host.peer(arguments["number"])
        val peer = face.peers().first { face.peerKey(it) == key }
        val name = peer.value("#Name").orEmpty()
        val kept = "${kind.kept}/${face.name}/$name"
        val pskFile = peer.value("PresharedKeyFile")?.takeIf { face.source == WgSource.Networkd }
        val output = root.masked(
            "cat ${quote(face.file)}; echo; echo $KEY_MARK" +
                (if (name.isEmpty()) "" else "; cat ${quote("$kept.key")} 2>/dev/null || { cat ${quote("$kept.once")} 2>/dev/null && rm -f ${quote("$kept.once")}; }") +
                pskFile?.let { "; echo $PSK_MARK; cat ${quote(it)}" }.orEmpty() + "; true",
        )
        val private = output.substringAfter("\n$KEY_MARK\n", "").substringBefore(PSK_MARK).trim()
        val psk = pskFile?.let { output.substringAfter("$PSK_MARK\n", "").trim() }
            ?: face.peer(WgIni(output.substringBefore("\n$KEY_MARK\n")), key)?.let { face.presharedOf(it) }.orEmpty()
        val server = host.live[face.name]?.publicKey ?: face.publicKey.ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "${face.name} has no private key") }
        val port = face.setting("listen-port").ifEmpty { host.live[face.name]?.port.orEmpty() }
        val endpoint = peer.value("#ClientEndpoint") ?: face.endpoint(localAddress(ssh), port)
        val address = peer.value("#ClientAddress") ?: items(face.peerValue(peer, "allowed-ips")).filter { it.endsWith("/32") || it.endsWith("/128") }.joinToString(", ")
        fun client(field: String) = peer.value(COMMENTS.getValue(field)) ?: face.setting(field).ifEmpty { null }
        val conf = buildList {
            add("[Interface]")
            add("PrivateKey = $private")
            if (address.isNotEmpty()) add("Address = $address")
            client("client-dns")?.let { add("DNS = $it") }
            peer.value("#ClientMTU")?.let { add("MTU = $it") }
            for (key in kind.disguise) face.setting(key.lowercase()).ifEmpty { null }?.let { add("$key = $it") }
            add("")
            add("[Peer]")
            add("PublicKey = $server")
            if (psk.isNotEmpty()) add("PresharedKey = $psk")
            add("AllowedIPs = ${client("client-allowed-ips") ?: "0.0.0.0/0, ::/0"}")
            if (endpoint.isNotEmpty()) add("Endpoint = $endpoint")
            client("client-keepalive")?.let { add("PersistentKeepalive = $it") }
        }
        return listOf(Item("", mapOf("conf" to conf.joinToString("\n"))))
    }

    // a change of the files of an interface: read whole as root, edited, written back through the check of its source and given to
    // it running by wg set or a restart; one that moves what it routes waits for the guard. With SaveConfig wg-quick writes the
    // running interface over its file as it goes down, which would undo the change and drop the names and the peers turned off
    private suspend fun change(host: WireGuardState, face: WgFace, edit: (WgIni, WgIni?) -> WgApply) {
        if (face.source == WgSource.Running) throw notKept(face)
        if (face.source == WgSource.Networkd && !face.file.startsWith("/etc/")) throw outside(face.file)
        val text = root.masked("cat ${quote(face.file)}")
        val file = WgIni(text)
        if (face.source == WgSource.Quick && file.part("Interface")?.value("SaveConfig")?.let { face.clean(it) } == "true") {
            throw DeviceException(ErrorCategory.Unsupported, "${face.name} has SaveConfig = true, and ${kind.quick} writes the running interface over ${face.file} as it goes down; take SaveConfig out of the file first")
        }
        val written = face.network?.ini?.text() ?: "[Match]\nName=${face.name}\n"
        val network = if (face.source == WgSource.Networkd) WgIni(written) else null
        val apply = edit(file, network)
        val networkFile = face.network?.file ?: "$NETWORKD/${face.name}.network"
        if (network != null && network.text() != written && !networkFile.startsWith("/etc/")) throw outside(networkFile)
        val running = face.name in host.live
        if (running && apply.restart) keepSession(ssh, face.name)
        val work: suspend () -> Unit = {
            if (file.text() != text) root.write(face.file, file.text(), face.checkLine())
            if (network != null && network.text() != written) root.write(networkFile, network.text(), "networkctl reload 2>&1", "644")
            if (running && apply.restart) {
                root.masked(face.restartLine())
            } else if (running && apply.commands.isNotEmpty()) {
                try {
                    root.masked(apply.commands.joinToString(" && "), apply.input)
                } catch (e: DeviceException) {
                    // the running interface did not take it, and the file goes back to what it runs
                    if (file.text() != text) root.write(face.file, text, face.checkLine())
                    throw e
                }
            }
        }
        if (running && (apply.restart || apply.routes)) guard.guarded(face.backup()) { work() } else work()
    }

    private suspend fun newKey() = made(root.masked("${kind.tool} genkey"), kind.tool)

    private suspend fun presharedKey(value: String) = if (value == AUTO) made(root.masked("${kind.tool} genpsk"), kind.tool) else wgKey("preshared-key", value)

    // a pair made on the host: the private key goes to its file there, only the public one comes back
    private suspend fun madeKey(path: String): String {
        val new = quote("$path.new")
        val tool = kind.tool
        return made(root.masked("umask 077; mkdir -p ${quote(path.substringBeforeLast('/'))} && $tool genkey > $new && $tool pubkey < $new && mv -f $new ${quote(path)}"), tool)
    }

    // what a new server takes unless the user says otherwise: the next free name wgN or awgN and port from 51820, the first network
    // 10.N.0.0/24 nothing on the host overlaps, the address of the interface of the default route for its clients, NAT, a start at boot,
    // a first client with a preshared key and its private key kept on the host and, for AmneziaWG, a disguise of version 1.0
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan(root)
        val taken = scan.networks + host.faces.flatMap { face -> items(face.setting("address")).mapNotNull(Ipv4::parseSubnet) }
        val address = freeNetwork(taken)?.let { "${Ipv4.format(it.address + 1)}/24" }.orEmpty()
        return Item(
            "",
            linkedMapOf(
                "name" to freeName(host, scan, kind.tool), "listen-port" to freePort(host, scan), "client-endpoint" to scan.address, "autostart" to "true",
                "address" to address, "client-dns" to CLIENT_DNS, "client-allowed-ips" to "0.0.0.0/0, ::/0", "client-keepalive" to "25",
                "preshared-key" to "true", "nat" to "true", "client" to "client1", "keep-key" to "true",
            ) + if (disguise.isEmpty()) emptyMap() else proposedDisguise(),
        )
    }

    // a server in one step: the interface as a new one is made, then its first client, whose configuration comes back
    private suspend fun create(values: Map<String, String>): List<Item> {
        val name = values["name"].orEmpty()
        val client = values["client"].orEmpty()
        if (client.isNotEmpty()) checkPeer("name", client)
        addInterface(values.filterKeys { it in settings || it in listOf("name", "autostart", "nat") })
        if (client.isEmpty()) return listOf(Item(name, mapOf("name" to name)))
        val psk = if (values["preshared-key"] in ValueType.Flag.on) AUTO else ""
        val peer = addPeer(mapOf("interface" to name, "name" to client, "keep-key" to values["keep-key"].orEmpty(), "preshared-key" to psk))
        return listOf(Item(name, mapOf("name" to name) + clientConfig(mapOf("number" to peer)).single().values))
    }

    // the configuration of a client, pasted or read from a file, as an interface of wg-quick of the name given. It is written and comes
    // up under the guard, as a peer of 0.0.0.0/0 takes the routes of the host, and starts at boot only once the guard let it be; the
    // way to the end of this session keeps outside the tunnel by a route of its own, which the rule of wg-quick that passes over the
    // default route of main leaves in place
    private suspend fun importConfig(values: Map<String, String>): List<Item> {
        if (kind.quick !in tools()) throw DeviceException(ErrorCategory.Unsupported, "a new interface goes to ${kind.quick}, and the host has none")
        val name = values["name"].orEmpty()
        if (!NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is no name of an interface")
        val host = state()
        if (host.faces.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has the ${kind.title} interface $name already")
        val ini = WgIni(values["text"].orEmpty())
        val face = WgFace(name, name, WgSource.Quick, "${kind.dir}/$name.conf", ini, "", null, kind)
        val key = face.clean(ini.part("Interface")?.value("PrivateKey").orEmpty())
        if ('•' in key || key == "(hidden)") throw DeviceException(ErrorCategory.ArgumentValue, "text: the keys are masked; show them in the window of the configuration or take it by Copy")
        if (!WG_KEY.matches(key) || face.peers().none { WG_KEY.matches(face.peerKey(it)) }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "text: a configuration of ${kind.title} needs a private key and a peer")
        }
        importedDisguise(ini)
        if (face.setting("dns").isNotEmpty() && "resolvconf" !in tools()) {
            throw DeviceException(ErrorCategory.Unsupported, "the configuration sets DNS, which ${kind.quick} gives the host through resolvconf, and the host lacks it; take the line DNS out of it")
        }
        if (values["keep-session"] in ValueType.Flag.on) sessionWay(ssh)?.takeIf { way -> face.peers().any { face.carries(it, way.address) } }?.let { way ->
            val part = ini.part("Interface") ?: ini.add("Interface")
            val ip = "ip ${family(way.address)} route"
            part.set("PreUp", part.values("PreUp") + "$ip replace ${way.target} # $SESSION_MARK", " = ")
            part.set("PostDown", part.values("PostDown") + "$ip del ${way.target} || true # $SESSION_MARK", " = ")
        }
        guard.guarded(face.backup()) {
            root.write(face.file, ini.text(), face.checkLine())
            root.masked(face.upLine())
        }
        if (values["autostart"] in ValueType.Flag.on) autostart(host, face, true)
        return listOf(Item(name, mapOf("name" to name)))
    }

    // wg-quick takes none of the keys of the disguise, and an awg older than the configuration only some of them: either fails as the
    // interface comes up, so the import says it first
    private suspend fun importedDisguise(ini: WgIni) {
        val keys = ini.part("Interface")?.lines.orEmpty().mapNotNull(::wgEntry).map { it.first }.filter { key -> DISGUISE.any { it.equals(key, ignoreCase = true) } }
        if (keys.isEmpty()) return
        if (disguise.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "text: the configuration sets ${keys.first()}, which belongs to AmneziaWG; import it in the window of AmneziaWG")
        val known = knows()
        keys.firstOrNull { it.lowercase() !in known }?.let { throw unknownDisguise(it) }
    }

    // wg-quick gives the servers of DNS to the host itself through resolvconf, and without it the interface does not come up
    private suspend fun needResolvconf() {
        if ("resolvconf" !in tools()) {
            throw DeviceException(ErrorCategory.Unsupported, "dns: ${kind.quick} sets these servers on the host itself through resolvconf, which the host lacks; the servers for clients go to Client DNS of their peers")
        }
    }
}

// what tells AmneziaWG from WireGuard: the menu, the tool and its quick tool, the folder of the files of the quick tool, beside which
// the host keeps the keys of the clients, the keys of the disguise in [Interface] and the service of OpenRC for the quick tool where no
// package gives one
internal class WgKind(
    val title: String, val path: String, val tool: String, val quick: String, val dir: String, val disguise: List<String> = emptyList(), val service: String? = null,
) {
    val kept get() = "$dir/networkhelm"
}

internal val WIREGUARD = WgKind("WireGuard", "vpn/wireguard", "wg", "wg-quick", "/etc/wireguard")

// where an interface is kept: a file of wg-quick, a profile of NetworkManager, a .netdev of systemd-networkd, or only the running system
internal enum class WgSource(val title: String) { Quick("wg-quick"), Manager("NetworkManager"), Networkd("systemd-networkd"), Running("") }

internal class WgFace(
    val id: String, val name: String, val source: WgSource, val file: String, val ini: WgIni, val publicKey: String, val network: WgNetwork?, val kind: WgKind = WIREGUARD,
)

// the .network file systemd-networkd configures an interface of a .netdev with
internal class WgNetwork(val file: String, val ini: WgIni)

// a running interface as wg show all dump has it; a peer keeps the fields of its line
internal class WgLive(val publicKey: String, val port: String, val fwmark: String) {
    val peers = linkedMapOf<String, List<String>>()
}

// what the host has of WireGuard at one read
internal class WireGuardState(
    val faces: List<WgFace>,
    val live: Map<String, WgLive>,
    private val enabled: Map<String, String>,
    private val kept: Set<String>,
    private val nat: Set<String>,
    val now: Long?,
    val openRc: Boolean,
    private val kind: WgKind = WIREGUARD,
) {
    fun face(id: String?): WgFace = faces.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no ${kind.title} interface $id")

    // a peer by its id, the id of its interface and its public key
    fun peer(id: String?): Pair<WgFace, String> {
        val face = faces.firstOrNull { it.id == id?.substringBefore('/') }
        val key = id?.substringAfter('/', "").orEmpty()
        if (face != null && face.source == WgSource.Running) throw notKept(face)
        if (face == null || face.peers().none { face.peerKey(it) == key }) throw DeviceException(ErrorCategory.MissingItem, "the host has no ${kind.title} peer $id")
        return face to key
    }

    fun managed(face: WgFace) = face.name in nat

    fun natted(face: WgFace) = managed(face) || face.hookedNat()

    // the endpoint of clients is the one their configurations get, the address of this session where the interface names none; of the
    // disguise only the keys the file sets, so a form leaves out those the awg of the host may not know
    fun interfaces(local: String = ""): List<Item> = faces.map { face ->
        val running = live[face.name]
        fun total(at: Int) = running?.peers?.values?.sumOf { it.getOrNull(at)?.toLongOrNull() ?: 0L }?.toString().orEmpty()
        val port = face.setting("listen-port").ifEmpty { running?.port.orEmpty() }
        Item(
            face.id,
            linkedMapOf(
                "name" to face.name,
                "source" to if (face.source == WgSource.Quick) kind.quick else face.source.title,
                "running" to "${running != null}",
                "autostart" to "${autostart(face)}",
                "public-key" to (running?.publicKey ?: face.publicKey),
                "private-key" to "",
                "listen-port" to port,
                "endpoint" to face.endpoint(local, port),
                "client-endpoint" to face.setting("client-endpoint"),
                "client-dns" to face.setting("client-dns"),
                "client-allowed-ips" to face.setting("client-allowed-ips"),
                "client-keepalive" to face.setting("client-keepalive"),
                "address" to face.setting("address"),
                "dns" to face.setting("dns"),
                "mtu" to face.setting("mtu"),
                "table" to face.setting("table"),
                "fwmark" to face.setting("fwmark").ifEmpty { running?.fwmark?.takeIf { it != "off" }.orEmpty() },
                "pre-up" to face.setting("pre-up"),
                "post-up" to face.setting("post-up"),
                "pre-down" to face.setting("pre-down"),
                "post-down" to face.setting("post-down"),
                "nat" to "${natted(face)}",
                "peers" to "${if (face.source == WgSource.Running) running?.peers?.size ?: 0 else face.peers().size}",
                "rx" to total(6),
                "tx" to total(7),
                "file" to face.file,
            ) + kind.disguise.map { it.lowercase() }.associateWith { face.setting(it) }.filterValues { it.isNotEmpty() },
        )
    }

    // a peer turned off is not in the running interface, whatever wg shows under its key
    fun peers(): List<Item> = faces.flatMap { face ->
        val running = live[face.name]?.peers.orEmpty()
        if (face.source == WgSource.Running) {
            running.map { (key, fields) -> peer(face, key, null, fields) }
        } else {
            face.peers().mapNotNull { part -> face.peerKey(part).ifEmpty { null }?.let { key -> peer(face, key, part, running[key]?.takeIf { !part.off }) } }
        }
    }

    private fun peer(face: WgFace, key: String, part: WgPart?, fields: List<String>?): Item {
        fun column(at: Int) = fields?.getOrNull(at)?.takeIf { it != "(none)" && it != "off" }.orEmpty()
        val name = part?.value("#Name").orEmpty()
        val handshake = fields?.getOrNull(5)?.toLongOrNull()?.takeIf { it > 0 }
        val path = "${kind.kept}/${face.name}/$name"
        return Item(
            "${face.id}/$key",
            linkedMapOf(
                "interface" to face.id,
                "name" to name,
                "public-key" to key,
                "preshared-key" to "",
                "allowed-ips" to if (part == null) items(column(4)).joinToString(", ") else face.peerValue(part, "allowed-ips"),
                "endpoint" to part?.let { face.peerValue(it, "endpoint") }.orEmpty(),
                "persistent-keepalive" to if (part == null) column(8) else face.peerValue(part, "persistent-keepalive"),
                "disabled" to "${part?.off == true}",
                "current-endpoint" to column(3),
                "last-handshake" to if (handshake != null && now != null) duration((now - handshake).coerceAtLeast(0)) else "",
                "rx" to column(6),
                "tx" to column(7),
                "client-key" to when {
                    name.isEmpty() -> ""
                    "$path.key" in kept -> "host"
                    "$path.once" in kept -> "once"
                    else -> ""
                },
            ) + COMMENTS.filterKeys { it in CLIENT }.mapValues { (_, comment) -> part?.value(comment).orEmpty() },
        )
    }

    private fun autostart(face: WgFace): Boolean = when (face.source) {
        WgSource.Quick -> enabled[face.name] == "enabled"
        WgSource.Manager -> face.ini.part("connection")?.value("autoconnect")?.lowercase() !in setOf("false", "no", "0")
        WgSource.Networkd -> face.network?.ini?.part("Link")?.value("ActivationPolicy")?.lowercase() !in setOf("manual", "down", "always-down")
        WgSource.Running -> false
    }
}

// the output of the read: the time of the host, wg show all dump with its secrets masked, each file with its keys masked and the
// public key of the interface, the .network files, what starts at boot, the client keys the host keeps and the tunnels whose
// NAT ufw or firewalld keeps
internal fun parseWireGuard(text: String, kind: WgKind = WIREGUARD): WireGuardState {
    var now: Long? = null
    var openRc = false
    val live = linkedMapOf<String, WgLive>()
    val files = mutableListOf<Triple<WgSource, String, MutableList<String>>>()
    val publics = mutableMapOf<String, String>()
    val networks = mutableListOf<Pair<String, MutableList<String>>>()
    val enabled = mutableMapOf<String, String>()
    val kept = mutableSetOf<String>()
    val nat = mutableSetOf<String>()
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
            "--nh-now--" -> now = rest.toLongOrNull()
            "--nh-dump--" -> {
                val fields = rest.split('\t')
                if (fields.size == 5) live[fields[0]] = WgLive(fields[2], fields[3], fields[4])
                if (fields.size == 9) live[fields[0]]?.peers?.put(fields[1], fields)
            }
            "--nh-file--" -> WgSource.entries.firstOrNull { it.title == rest.substringBefore(' ') }?.let { source ->
                body = mutableListOf<String>().also { files += Triple(source, rest.substringAfter(' '), it) }
            }
            "--nh-public--" -> files.lastOrNull()?.let { publics[it.second] = rest }
            "--nh-network--" -> body = mutableListOf<String>().also { networks += rest to it }
            "--nh-auto--" -> enabled[rest.substringBefore(' ')] = rest.substringAfter(' ', "")
            "--nh-kept--" -> kept += rest
            "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            "--nh-openrc--" -> openRc = true
        }
    }
    val seen = HashMap<String, Int>()
    val faces = files.mapNotNull { (source, path, lines) ->
        val ini = WgIni(lines.joinToString("\n"))
        val name = when (source) {
            WgSource.Quick -> path.substringAfterLast('/').removeSuffix(".conf")
            WgSource.Manager -> ini.part("connection")?.let { it.value("interface-name") ?: it.value("id") }
            else -> ini.part("NetDev")?.value("Name")
        }?.ifEmpty { null } ?: return@mapNotNull null
        val count = (seen[name] ?: 0) + 1
        seen[name] = count
        val network = networks.takeIf { source == WgSource.Networkd }?.firstOrNull { name in networkdNames(it.second.joinToString("\n")) }
        val own = network?.let { WgNetwork(it.first, WgIni(it.second.joinToString("\n"))) }
        WgFace(if (count == 1) name else "$name#$count", name, source, path, ini, publics[path].orEmpty(), own, kind)
    }
    val named = faces.map { it.name }.toSet()
    val running = live.filterKeys { it !in named }.map { (name, state) -> WgFace(name, name, WgSource.Running, "", WgIni(""), state.publicKey, null, kind) }
    return WireGuardState(faces + running, live, enabled, kept, nat, now, openRc, kind)
}

// a file of key = value lines by its sections, each line kept as it is written, so what no change touches goes back the same.
// The lines of a peer turned off stand behind #[disabled], as PiVPN writes them, which all three sources read as a comment
internal class WgIni(text: String) {
    val parts: MutableList<WgPart> = mutableListOf(WgPart("", mutableListOf()))

    init {
        for (line in text.lines()) {
            val head = line.removePrefix(OFF).trim()
            if (head.length > 2 && head.startsWith("[") && head.endsWith("]")) parts += WgPart(head.substring(1, head.length - 1).trim(), mutableListOf(line)) else parts.last().lines += line
        }
    }

    fun text() = parts.flatMap { it.lines }.joinToString("\n")

    // the one of the name that is on: a file has one [Interface], [wireguard], [NetDev] or [Network]
    fun part(name: String) = parts.firstOrNull { it.name.equals(name, ignoreCase = true) && !it.off }

    fun all(name: String) = parts.filter { it.name.equals(name, ignoreCase = true) }

    // a section at the end, a blank line before it
    fun add(name: String): WgPart {
        val last = parts.last().lines
        if (last.lastOrNull()?.isBlank() == false) last += ""
        return WgPart(name, mutableListOf("[$name]")).also { parts += it }
    }
}

internal class WgPart(val name: String, val lines: MutableList<String>) {
    val off: Boolean get() = lines.firstOrNull()?.startsWith(OFF) == true

    // the values of a key in their order; a comment # Name = phone is the key #Name
    fun values(key: String): List<String> = lines.mapNotNull(::wgEntry).filter { it.first.equals(key, ignoreCase = true) }.map { it.second }

    fun value(key: String): String? = values(key).firstOrNull()

    // the lines of a key replaced by one a value at the place of its first line, or after the last entry; no values take it out
    fun set(key: String, values: List<String>, equals: String) {
        val at = lines.indexOfFirst { same(it, key) }
        lines.removeAll { same(it, key) }
        if (values.isEmpty()) return
        val place = if (at >= 0) at else maxOf(lines.indexOfLast { wgEntry(it) != null } + 1, if (name.isEmpty()) 0 else 1)
        lines.addAll(place, values.map { value -> (if (off) OFF else "") + if (key.startsWith("#")) "# ${key.drop(1)} = $value" else "$key$equals$value" })
    }

    fun set(key: String, value: String, equals: String) = set(key, listOfNotNull(value.ifEmpty { null }), equals)

    fun turn(on: Boolean) {
        for (i in lines.indices) lines[i] = if (on) lines[i].removePrefix(OFF) else if (lines[i].isBlank() || lines[i].startsWith(OFF)) lines[i] else OFF + lines[i]
    }

    private fun same(line: String, key: String) = wgEntry(line)?.first?.equals(key, ignoreCase = true) == true
}

// the hooks of wg-quick that give its clients NAT where neither ufw nor firewalld keeps the rules: iptables where the host has it,
// else a table of nftables of its own. The mark after # is a comment to wg-quick
internal fun natHooks(networks: List<String>, iptables: Boolean): Pair<String, String> {
    val up = mutableListOf<String>()
    val down = mutableListOf<String>()
    if (iptables) {
        for ((tool, own) in networks.groupBy { if (':' in it) "ip6tables" else "iptables" }) {
            for (network in own) {
                up += "$tool -w -t nat -A POSTROUTING -s $network ! -o %i -j MASQUERADE"
                down += "$tool -w -t nat -D POSTROUTING -s $network ! -o %i -j MASQUERADE"
            }
            for (rule in listOf("-i %i -j ACCEPT", "-o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT")) {
                up += "$tool -w -I FORWARD $rule"
                down += "$tool -w -D FORWARD $rule"
            }
        }
    } else {
        up += "nft add table inet networkhelm-%i"
        up += "nft add chain inet networkhelm-%i nat '{ type nat hook postrouting priority 100; }'"
        for (network in networks) up += "nft add rule inet networkhelm-%i nat ${if (':' in network) "ip6" else "ip"} saddr $network oifname != %i masquerade"
        down += "nft delete table inet networkhelm-%i"
    }
    return up.joinToString("; ") + " # $NAT_MARK" to down.joinToString("; ") + " # $NAT_MARK"
}

private fun freeName(host: WireGuardState, scan: HostScan, prefix: String): String {
    val taken = scan.links + host.faces.map { it.name }
    return generateSequence(0) { it + 1 }.map { "$prefix$it" }.first { it !in taken }
}

// the first port from 51820 that no socket of UDP on the host, no running interface and no file of another interface holds
private fun freePort(host: WireGuardState, scan: HostScan): String {
    val taken = scan.ports + host.live.values.mapNotNull { it.port.toIntOrNull() } + host.faces.mapNotNull { it.setting("listen-port").toIntOrNull() }
    return "${(FIRST_PORT..65535).first { it !in taken }}"
}

// what a running interface needs after its files changed: commands, one of them reading the input, or a restart; routes says
// the change moves what the interface routes beyond its own networks
private class WgApply(val commands: List<String> = emptyList(), val input: String? = null, val restart: Boolean = false, val routes: Boolean = false) {
    operator fun plus(other: WgApply) = WgApply(commands + other.commands, input ?: other.input, restart || other.restart, routes || other.routes)
}

// a peer before a change, as the running interface has it
private class WgWas(val key: String, val on: Boolean, val allowed: List<String>, val endpoint: String, val keepalive: String, val psk: String)

// how a source writes WireGuard: the section of the interface, the one of a peer, the keys of the settings and how a key meets its value
private class WgDialect(val face: String, val peer: String, val keys: Map<String, String>, val equals: String)

private fun WgFace.dialect() = when (source) {
    WgSource.Quick -> QUICK_KEYS
    WgSource.Manager -> MANAGER_KEYS
    else -> NETWORKD_KEYS
}

// wg-quick takes the rest of a line after # for a comment
private fun WgFace.clean(value: String) = if (source == WgSource.Quick) value.substringBefore('#').trim() else value

internal fun WgFace.peers(file: WgIni = ini): List<WgPart> = if (source == WgSource.Manager) file.parts.filter { it.name.startsWith(NM_PEER) } else file.all(dialect().peer)

internal fun WgFace.peerKey(part: WgPart) = if (source == WgSource.Manager) part.name.removePrefix(NM_PEER) else clean(part.value("PublicKey").orEmpty())

private fun WgFace.peer(file: WgIni, key: String) = peers(file).firstOrNull { peerKey(it) == key }

internal fun WgFace.hookedNat() = source == WgSource.Quick && ini.part("Interface")?.values("PostUp")?.any { NAT_MARK in it } == true

// a setting of the interface as its source keeps it, in the words of wg-quick; the hooks of NAT stay out of sight
internal fun WgFace.setting(field: String): String = when {
    source == WgSource.Manager && (field == "address" || field == "dns") -> FAMILIES.flatMap { (family, _) ->
        val part = ini.part(family)
        if (field == "dns") items(part?.value("dns").orEmpty()) else part?.lines.orEmpty().mapNotNull(::wgEntry).filter { NM_ADDRESS.matches(it.first) }.map { it.second.substringBefore(',') }
    }.joinToString(", ")
    source == WgSource.Networkd && (field == "address" || field == "dns") -> network?.ini?.let { net ->
        val listed = net.part("Network")?.values(if (field == "address") "Address" else "DNS").orEmpty()
        (listed + (if (field == "address") net.all("Address").mapNotNull { it.value("Address") } else emptyList())).flatMap(::items).joinToString(", ")
    }.orEmpty()
    source == WgSource.Networkd && field == "mtu" -> ini.part("NetDev")?.value("MTUBytes").orEmpty()
    else -> {
        val values = dialect().keys[field]?.let { key -> ini.part(dialect().face)?.values(key) }.orEmpty().filter { NAT_MARK !in it }.map { clean(it) }
        when (field) {
            "address", "dns" -> values.flatMap(::items).joinToString(", ")
            in HOOKS -> values.joinToString("\n")
            else -> values.firstOrNull().orEmpty()
        }
    }
}

// where clients reach an interface: the address given for them, with the port of the interface unless it names one, else the address
// this session reaches the host by
internal fun WgFace.endpoint(local: String, port: String): String {
    val given = setting("client-endpoint")
    if (ENDPOINT.matches(given)) return given
    val host = given.ifEmpty { local }
    return if (host.isEmpty() || port.isEmpty()) "" else (if (':' in host) "[$host]" else host) + ":$port"
}

// a setting written where the source keeps it: NetworkManager keeps the addresses and the servers of names by family,
// systemd-networkd in the .network file of the interface; the hooks of NAT stay after the ones of the user
private fun WgFace.put(file: WgIni, network: WgIni?, field: String, value: String) {
    val values = items(value)
    when {
        source == WgSource.Manager && (field == "address" || field == "dns") -> for ((family, v6) in FAMILIES) {
            val own = values.filter { (':' in it) == v6 }
            val part = file.part(family) ?: if (own.isEmpty()) continue else file.add(family)
            if (field == "dns") {
                part.set("dns", if (own.isEmpty()) "" else own.joinToString(";", postfix = ";"), "=")
            } else {
                part.lines.removeAll { line -> wgEntry(line)?.first?.let(NM_ADDRESS::matches) == true }
                own.forEachIndexed { at, address -> part.set("address${at + 1}", address, "=") }
                part.set("method", if (own.isEmpty()) "disabled" else "manual", "=")
            }
        }
        source == WgSource.Networkd && (field == "address" || field == "dns") -> network?.let { net ->
            if (field == "address") net.parts.removeAll { it.name.equals("Address", ignoreCase = true) }
            (net.part("Network") ?: net.add("Network")).set(if (field == "address") "Address" else "DNS", values, "=")
        }
        source == WgSource.Networkd && field == "mtu" -> (file.part("NetDev") ?: file.add("NetDev")).set("MTUBytes", value, "=")
        else -> {
            val key = dialect().keys[field] ?: throw DeviceException(ErrorCategory.Unsupported, "$field: ${source.title} keeps no such setting of WireGuard")
            val part = file.part(dialect().face) ?: file.add(dialect().face)
            if (source == WgSource.Networkd && field == "private-key") part.set("PrivateKeyFile", "", "=")
            when (field) {
                in HOOKS -> part.set(key, value.lines().map { it.trim() }.filter { it.isNotEmpty() } + part.values(key).filter { NAT_MARK in it }, dialect().equals)
                "address", "dns" -> part.set(key, values.joinToString(", "), dialect().equals)
                else -> part.set(key, value, dialect().equals)
            }
        }
    }
}

// what a running interface takes of a change of its settings: wg set for the key, the port and the mark, ip for the MTU, a
// restart for what wg-quick or the manager sets up as it brings the interface up and for the disguise, which both ends change together
private fun WgFace.settingsApply(fields: Map<String, String>, key: String?): WgApply {
    val disguise = kind.disguise.map { it.lowercase() }
    if (fields.keys.any { it in RESTART || it in disguise } || fields["mtu"]?.isEmpty() == true) return WgApply(restart = true)
    val n = quote(name)
    val set = listOfNotNull(
        key?.let { "private-key /dev/stdin" },
        fields["listen-port"]?.let { "listen-port ${it.ifEmpty { "0" }}" },
        fields["fwmark"]?.let { "fwmark ${it.ifEmpty { "off" }}" },
    )
    return WgApply(listOfNotNull(set.takeIf { it.isNotEmpty() }?.let { "${kind.tool} set $n ${it.joinToString(" ")}" }, fields["mtu"]?.let { "ip link set dev $n mtu $it" }), key)
}

private fun WgFace.peerValue(part: WgPart, field: String): String {
    val values = dialect().keys[field]?.let { part.values(it) }.orEmpty().map { clean(it) }
    return if (field == "allowed-ips") values.flatMap(::items).joinToString(", ") else values.firstOrNull().orEmpty()
}

private fun WgFace.presharedOf(part: WgPart) = dialect().keys["preshared-key"]?.let { part.value(it) }?.let { clean(it) }.orEmpty()

// whether a peer takes the way to an address: one of its allowed networks holds it
private fun WgFace.carries(part: WgPart, address: String) = items(peerValue(part, "allowed-ips")).any { network ->
    if (':' in address) network == "::/0" else ipv4Net(network)?.let { (base, length) -> ipv4(address)?.let { (it xor base) shr (32 - length) == 0L } } == true
}

// the name and the settings for the device are comments of the section; the lists of NetworkManager end with ;
private fun WgFace.putPeer(part: WgPart, field: String, value: String) {
    COMMENTS[field]?.let { return part.set(it, value, "") }
    if (field == "disabled") return part.turn(value !in ValueType.Flag.on)
    val key = dialect().keys[field] ?: return
    if (source == WgSource.Networkd && field == "preshared-key") part.set("PresharedKeyFile", "", "=")
    val values = items(value)
    val written = when {
        field != "allowed-ips" -> value
        values.isEmpty() -> ""
        source == WgSource.Manager -> values.joinToString(";", postfix = ";")
        else -> values.joinToString(", ")
    }
    part.set(key, written, dialect().equals)
    // NetworkManager saves the preshared key of a peer back to its profile only with flags 0, and a peer without them has 4
    if (source == WgSource.Manager && field == "preshared-key") part.set("preshared-key-flags", "0", "=")
}

// NetworkManager names a peer by its key in the header of its section
private fun WgFace.rekey(file: WgIni, part: WgPart, key: String): WgPart {
    if (peer(file, key) != null) throw DeviceException(ErrorCategory.ArgumentValue, "$name has the peer $key already")
    if (source != WgSource.Manager) return part.also { it.set("PublicKey", key, dialect().equals) }
    val renamed = WgPart("$NM_PEER$key", (listOf((if (part.off) OFF else "") + "[$NM_PEER$key]") + part.lines.drop(1)).toMutableList())
    file.parts[file.parts.indexOf(part)] = renamed
    return renamed
}

private fun WgFace.was(part: WgPart) =
    WgWas(peerKey(part), !part.off, items(peerValue(part, "allowed-ips")), peerValue(part, "endpoint"), peerValue(part, "persistent-keepalive"), presharedOf(part))

// what the running interface needs after a peer changed: wg set for it, taken out first where its key changed or it lost its
// endpoint, which wg set cannot clear; the routes of its networks as wg-quick makes them, or a restart where a default route
// comes or goes, which wg-quick sets up as the interface comes up
private fun WgFace.peerApply(file: WgIni, previous: WgWas?, part: WgPart?): WgApply {
    val now = part?.takeIf { !it.off }?.let { was(it) }
    val before = previous?.takeIf { it.on }
    if (before?.allowed.orEmpty().filter(::isDefault).toSet() != now?.allowed.orEmpty().filter(::isDefault).toSet()) return WgApply(restart = true)
    val words = mutableListOf<String>()
    var input: String? = null
    val pskFile = part?.value("PresharedKeyFile")?.takeIf { source == WgSource.Networkd }
    if (before != null && (now == null || now.key != before.key || now.endpoint.isEmpty() && before.endpoint.isNotEmpty())) words += "peer ${quote(before.key)} remove"
    if (now != null && (words.isNotEmpty() || before == null || now.allowed != before.allowed || now.endpoint != before.endpoint || now.keepalive != before.keepalive || now.psk != before.psk)) {
        val psk = when {
            pskFile != null -> quote(pskFile)
            now.psk.isNotEmpty() -> "/dev/stdin".also { input = now.psk }
            else -> "/dev/null"
        }
        // 0 and not off: awg of amneziawg-tools 3.1 refuses off with exit 1 and no word
        words += "peer ${quote(now.key)} preshared-key $psk allowed-ips ${quote(now.allowed.joinToString(","))}" +
            now.endpoint.ifEmpty { null }?.let { " endpoint ${quote(it)}" }.orEmpty() + " persistent-keepalive ${now.keepalive.ifEmpty { "0" }}"
    }
    val others = peers(file).filter { !it.off && it !== part }.flatMap { items(peerValue(it, "allowed-ips")) }.toSet()
    val added = now?.allowed.orEmpty().filterNot(::isDefault) - before?.allowed.orEmpty().toSet()
    val gone = before?.allowed.orEmpty().filterNot(::isDefault) - now?.allowed.orEmpty().toSet() - others
    val own = networks(setting("address"))
    return WgApply(
        listOfNotNull(words.takeIf { it.isNotEmpty() }?.let { "${kind.tool} set ${quote(name)} ${it.joinToString(" ")}" }) + routes(file, added, gone),
        input,
        routes = (added + gone).any { !within(it, own) },
    )
}

// the routes wg-quick and NetworkManager make for the networks of the peers; systemd-networkd makes them only where it is told to
private fun WgFace.routes(file: WgIni, added: List<String>, gone: List<String>): List<String> {
    val table = when (source) {
        WgSource.Quick -> clean(file.part("Interface")?.value("Table").orEmpty()).lowercase()
        WgSource.Manager -> ""
        else -> return emptyList()
    }
    if (table == "off") return emptyList()
    val at = if (table.isEmpty() || table == "auto" || table == "main") "" else " table ${quote(table)}"
    val n = quote(name)
    return added.map { "{ ip ${family(it)} route show dev $n match $it$at | grep -q . || ip ${family(it)} route add $it dev $n$at; }" } +
        gone.map { "{ ip ${family(it)} route del $it dev $n$at 2>/dev/null || true; }" }
}

private fun WgFace.ref() = ini.part("connection")?.let { part -> part.value("uuid")?.let { "uuid ${quote(it)}" } ?: part.value("id")?.let { "id ${quote(it)}" } } ?: "id ${quote(name)}"

// what checks a written file and says what is wrong with it; wg-quick strip prints the file with its keys
private fun WgFace.checkLine() = when (source) {
    WgSource.Quick -> "${kind.quick} strip ${quote(file)} 2>&1 >/dev/null"
    WgSource.Manager -> "nmcli connection load ${quote(file)} 2>&1"
    else -> "networkctl reload 2>&1"
}

private fun WgFace.upLine(): String {
    val n = quote(name)
    val q = kind.quick
    return when (source) {
        // a unit left active by a wg-quick down typed by hand starts nothing, so only an inactive unit goes through systemd
        WgSource.Quick -> "if systemctl cat $q@$n >/dev/null 2>&1 && ! systemctl is-active -q $q@$n; then systemctl start $q@$n; else $q up $n; fi"
        WgSource.Manager -> "nmcli connection up ${ref()}"
        WgSource.Networkd -> "networkctl reload && networkctl up $n"
        WgSource.Running -> "ip link set dev $n up"
    }
}

private fun WgFace.downLine(): String {
    val n = quote(name)
    val q = kind.quick
    return when (source) {
        WgSource.Quick -> "if systemctl is-active -q $q@$n 2>/dev/null; then systemctl stop $q@$n; else $q down $n; fi"
        WgSource.Manager -> "nmcli connection down ${ref()}"
        WgSource.Networkd -> "networkctl down $n"
        WgSource.Running -> "ip link del dev $n"
    }
}

private fun WgFace.restartLine(): String {
    val n = quote(name)
    val q = kind.quick
    return when (source) {
        WgSource.Quick -> "if systemctl is-active -q $q@$n 2>/dev/null; then systemctl restart $q@$n; else $q down $n && $q up $n; fi"
        WgSource.Manager -> "nmcli connection up ${ref()}"
        else -> "networkctl reload && networkctl reconfigure $n"
    }
}

private fun WgFace.reloadLine() = when (source) {
    WgSource.Manager -> "nmcli connection load ${quote(file)}"
    WgSource.Networkd -> "networkctl reload"
    else -> null
}

// what the guard keeps of an interface: its files and whether it was up; taking a change back stops it, puts the files back and
// brings it up as it was
private fun WgFace.backup(): LinuxGuard.Backup {
    val files = listOfNotNull(file, (network?.file ?: "$NETWORKD/$name.network").takeIf { source == WgSource.Networkd })
    val save = files.mapIndexed { at, path -> "if [ -e ${quote(path)} ]; then cp -p ${quote(path)} \"\$B/$at\"; fi" } + "if [ -e /sys/class/net/${quote(name)} ]; then touch \"\$B/up\"; fi"
    val restore = listOf(downLine()) + files.mapIndexed { at, path -> "if [ -e \"\$1/$at\" ]; then cp -p \"\$1/$at\" ${quote(path)}; else rm -f ${quote(path)}; fi" } +
        listOfNotNull(reloadLine(), "if [ -e \"\$1/up\" ]; then ${upLine()}; fi")
    return LinuxGuard.Backup("vpn", save.joinToString("; "), restore.joinToString("; "))
}

// the lowest address of the first IPv4 network of the interface that neither it nor a peer has
private fun WgFace.nextAddress(): String {
    val own = items(setting("address"))
    val (base, length) = own.firstNotNullOfOrNull { if ('/' in it) ipv4Net(it) else null }?.takeIf { it.second in 8..30 }
        ?: throw DeviceException(ErrorCategory.ArgumentValue, "allowed-ips: a value is needed")
    val start = base shr (32 - length) shl (32 - length)
    val taken = (own + peers().flatMap { items(peerValue(it, "allowed-ips")) }).mapNotNull { ipv4(it.substringBefore('/')) }.toSet()
    val free = (1L until (1L shl (32 - length)) - 1).asSequence().map { start + it }.firstOrNull { it !in taken }
        ?: throw DeviceException(ErrorCategory.ArgumentValue, "the network of $name has no free address")
    return "${ipv4Text(free)}/32"
}

// systemd-networkd takes files from /usr/lib and /run as well, which the distribution or netplan write
private fun outside(path: String) = DeviceException(ErrorCategory.ArgumentValue, "$path lies outside /etc, and it stays as it is")

private fun notKept(face: WgFace) = DeviceException(
    ErrorCategory.Unsupported,
    if (face.kind == WIREGUARD) "${face.name} is in no file of wg-quick, NetworkManager or systemd-networkd" else "${face.name} is in no file of ${face.kind.quick}",
)

// the key and the value of a line, of one turned off as well; a comment # Name = phone holds the key #Name
private fun wgEntry(line: String): Pair<String, String>? {
    val text = line.removePrefix(OFF).trim()
    val comment = text.startsWith("#")
    val body = if (comment) text.drop(1).trim() else text
    if (body.startsWith("[") || body.startsWith(";") || '=' !in body) return null
    val key = body.substringBefore('=').trim()
    if (key.isEmpty() || key.any { it.isWhitespace() || it == '#' }) return null
    return (if (comment) "#$key" else key) to body.substringAfter('=').trim()
}

private fun items(text: String) = text.split(LIST).filter { it.isNotEmpty() }

private fun ids(text: String?) = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun isDefault(network: String) = network == "0.0.0.0/0" || network == "::/0"

private fun family(network: String) = if (':' in network) "-6" else "-4"

private fun hookLine(hook: String, name: String) = hook.substringBefore('#').trim().replace("%i", quote(name))

// the networks the addresses of an interface lie in: IPv4 ones by their prefix, IPv6 ones as written
private fun networks(addresses: String) = items(addresses).filter { '/' in it }.map { address ->
    ipv4Net(address)?.let { (base, length) -> "${ipv4Text(base shr (32 - length) shl (32 - length))}/$length" } ?: address
}

// whether an IPv4 network lies in one of the networks of the interface, which its addresses route already
private fun within(network: String, own: List<String>): Boolean {
    val (address, length) = ipv4Net(network) ?: return false
    return own.any { ipv4Net(it)?.let { (base, size) -> size <= length && (address xor base) shr (32 - size) == 0L } == true }
}

private fun ipv4Net(text: String): Pair<Long, Int>? {
    val address = ipv4(text.substringBefore('/')) ?: return null
    val length = text.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
    return address to length
}

private fun ipv4(text: String): Long? {
    val parts = text.split('.')
    val bytes = parts.mapNotNull { part -> part.toIntOrNull()?.takeIf { it in 0..255 } }
    if (parts.size != 4 || bytes.size != 4) return null
    return bytes.fold(0L) { value, byte -> value shl 8 or byte.toLong() }
}

private fun ipv4Text(value: Long) = (3 downTo 0).joinToString(".") { "${(value shr (it * 8)) and 255}" }

// a key as wg takes it; what is wrong with one is said without it, as it may be secret
private fun wgKey(field: String, value: String) = value.trim().takeIf(WG_KEY::matches) ?: throw DeviceException(ErrorCategory.ArgumentValue, "$field: no key of WireGuard, which is 44 characters of base64")

private fun made(output: String, tool: String) = output.trim().takeIf(WG_KEY::matches) ?: throw DeviceException(ErrorCategory.Api, "$tool made no key")

private fun checkSetting(field: String, value: String) {
    if (field == "private-key") {
        if (value != AUTO) wgKey(field, value)
        return
    }
    val valid = when (field) {
        "listen-port" -> value.toIntOrNull() in 1..65535
        "mtu" -> value.toIntOrNull() in 576..65535
        "address" -> items(value).all(PREFIXED::matches)
        "dns", "client-dns" -> items(value).all(HOST::matches)
        "table" -> TABLE.matches(value)
        "fwmark" -> FWMARK.matches(value)
        "client-endpoint" -> HOST.matches(value) || ENDPOINT.matches(value)
        "client-allowed-ips" -> items(value).all(NETWORK::matches)
        "client-keepalive" -> value.toIntOrNull() in 0..65535
        in DISGUISE_LIMITS -> value.toIntOrNull() in DISGUISE_LIMITS.getValue(field)
        in HEADERS -> headerRange(value) != null
        // wg-quick cuts a line at #
        else -> '#' !in value
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

private fun checkPeer(field: String, value: String) {
    if (field == "public-key" || field == "preshared-key") {
        if (value != AUTO || field == "public-key") wgKey(field, value)
        return
    }
    val valid = when (field) {
        "name" -> value.length <= 64 && !value.startsWith(".") && value.none { it == '/' || it.isISOControl() }
        "allowed-ips", "client-address", "client-allowed-ips" -> items(value).all(NETWORK::matches)
        "endpoint", "client-endpoint" -> ENDPOINT.matches(value)
        "persistent-keepalive", "client-keepalive" -> value.toIntOrNull() in 0..65535
        "client-mtu" -> value.toIntOrNull() in 576..65535
        "client-dns" -> items(value).all(HOST::matches)
        else -> true
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

private const val NETWORKD = "/etc/systemd/network"
private const val NM_PEER = "wireguard-peer."
private const val AUTO = "auto"
private const val OFF = "#[disabled] "
private const val KEY_MARK = "--nh-key--"
private const val PSK_MARK = "--nh-psk--"
private const val FIRST_PORT = 51820
private const val SESSION_MARK = "networkhelm-session"
private const val NO_OPENRC = "autostart: the host has neither systemd nor OpenRC"

private val LIST = Regex("[,;\\s]+")
private val BLANKS = Regex("\\s+")
private val NAME = Regex("[A-Za-z0-9_.-]{1,15}")
private val PREFIXED = Regex("[0-9A-Fa-f.:]+/\\d{1,3}")
private val NETWORK = Regex("[0-9A-Fa-f.:]+(/\\d{1,3})?")
private val HOST = Regex("[A-Za-z0-9.:-]+")
private val ENDPOINT = Regex("(\\[[0-9A-Fa-f.:]+\\]|[A-Za-z0-9.-]+):\\d{1,5}")
private val TABLE = Regex("[A-Za-z0-9_-]+")
private val FWMARK = Regex("off|\\d+|0x[0-9A-Fa-f]+")
private val NM_ADDRESS = Regex("address(es)?\\d+")
private val FAMILIES = listOf("ipv4" to false, "ipv6" to true)
private val HOOKS = setOf("pre-up", "post-up", "pre-down", "post-down")
private val RESTART = setOf("address", "dns", "table") + HOOKS

// what the configurations of the clients of an interface take where their peer sets nothing of its own
private val FACE_CLIENT = listOf("client-endpoint", "client-dns", "client-allowed-ips", "client-keepalive")
private val SETTINGS = listOf("private-key", "listen-port", "address", "dns", "mtu", "table", "fwmark") + HOOKS + FACE_CLIENT
private val CLIENT = listOf("client-address", "client-dns", "client-allowed-ips", "client-endpoint", "client-mtu", "client-keepalive")
private val PEER_SETTINGS = listOf("name", "public-key", "preshared-key", "allowed-ips", "endpoint", "persistent-keepalive", "disabled") + CLIENT
private val COMMENTS = mapOf(
    "name" to "#Name", "client-address" to "#ClientAddress", "client-dns" to "#ClientDNS", "client-allowed-ips" to "#ClientAllowedIPs",
    "client-endpoint" to "#ClientEndpoint", "client-mtu" to "#ClientMTU", "client-keepalive" to "#ClientKeepalive",
)

private val QUICK_KEYS = WgDialect(
    "Interface", "Peer",
    mapOf(
        "private-key" to "PrivateKey", "listen-port" to "ListenPort", "fwmark" to "FwMark", "address" to "Address", "dns" to "DNS", "mtu" to "MTU", "table" to "Table",
        "pre-up" to "PreUp", "post-up" to "PostUp", "pre-down" to "PreDown", "post-down" to "PostDown", "public-key" to "PublicKey", "preshared-key" to "PresharedKey",
        "allowed-ips" to "AllowedIPs", "endpoint" to "Endpoint", "persistent-keepalive" to "PersistentKeepalive",
    ) + COMMENTS.filterKeys { it in FACE_CLIENT } + DISGUISE.associateBy { it.lowercase() },
    " = ",
)
private val MANAGER_KEYS = WgDialect(
    "wireguard", NM_PEER,
    mapOf(
        "private-key" to "private-key", "listen-port" to "listen-port", "fwmark" to "fwmark", "mtu" to "mtu", "preshared-key" to "preshared-key",
        "allowed-ips" to "allowed-ips", "endpoint" to "endpoint", "persistent-keepalive" to "persistent-keepalive",
    ) + COMMENTS.filterKeys { it in FACE_CLIENT },
    "=",
)
private val NETWORKD_KEYS = WgDialect(
    "WireGuard", "WireGuardPeer",
    mapOf(
        "private-key" to "PrivateKey", "listen-port" to "ListenPort", "fwmark" to "FirewallMark", "table" to "RouteTable", "public-key" to "PublicKey",
        "preshared-key" to "PresharedKey", "allowed-ips" to "AllowedIPs", "endpoint" to "Endpoint", "persistent-keepalive" to "PersistentKeepalive",
    ) + COMMENTS.filterKeys { it in FACE_CLIENT },
    "=",
)

private val INTERFACE = listOf(
    "name", "source", "running", "autostart", "public-key", "private-key", "listen-port", "endpoint", "address", "dns", "mtu", "table", "fwmark", "pre-up", "post-up",
    "pre-down", "post-down", "nat", "peers", "rx", "tx", "file",
) + FACE_CLIENT
private val PEER = listOf(
    "interface", "name", "public-key", "preshared-key", "allowed-ips", "endpoint", "persistent-keepalive", "disabled", "current-endpoint", "last-handshake",
    "rx", "tx", "client-key",
) + CLIENT
private val INTERFACE_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("private-key", ValueType.Secret, optional = true, placeholder = AUTO),
    Argument("listen-port", ValueType.Integer(1, 65535), optional = true),
    Argument("address", ValueType.Text(), optional = true, placeholder = "10.8.0.1/24, fd42::1/64"),
    Argument("dns", ValueType.Text(), optional = true),
    Argument("mtu", ValueType.Integer(576, 65535), optional = true),
    Argument("table", ValueType.Text(), optional = true, placeholder = AUTO),
    Argument("fwmark", ValueType.Text(), optional = true),
    Argument("pre-up", ValueType.Text(), optional = true),
    Argument("post-up", ValueType.Text(), optional = true),
    Argument("pre-down", ValueType.Text(), optional = true),
    Argument("post-down", ValueType.Text(), optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
    Argument("client-endpoint", ValueType.Text(), optional = true, placeholder = "vpn.example.net"),
    Argument("client-dns", ValueType.Text(), optional = true, placeholder = "1.1.1.1, 9.9.9.9"),
    Argument("client-allowed-ips", ValueType.Text(), optional = true, placeholder = "0.0.0.0/0, ::/0"),
    Argument("client-keepalive", ValueType.Integer(0, 65535, unit = "s"), optional = true),
)

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes
private val SERVER_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name", tab = "general"),
    Argument("listen-port", ValueType.Integer(1, 65535), label = "Listen Port", tab = "general"),
    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("address", ValueType.Text(), label = "Address", tab = "network", placeholder = "10.8.0.1/24, fd42::1/64"),
    Argument("mtu", ValueType.Integer(576, 65535), optional = true, label = "MTU", tab = "network"),
    Argument("client-dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = "1.1.1.1, 9.9.9.9"),
    Argument("client-allowed-ips", ValueType.Text(), label = "Client Allowed IPs", tab = "dns", placeholder = "0.0.0.0/0, ::/0"),
    Argument("client-keepalive", ValueType.Integer(0, 65535, unit = "s"), optional = true, label = "Client Keepalive, s", tab = "dns"),
    Argument("private-key", ValueType.Secret, optional = true, label = "Private Key", tab = "keys", placeholder = AUTO),
    Argument("preshared-key", ValueType.YesNo, label = "Preshared Keys", tab = "keys"),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("client", ValueType.Text(), optional = true, label = "First Client", tab = "clients"),
    Argument("keep-key", ValueType.YesNo, label = "Keep Client Key on Host", tab = "clients"),
)

private val IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot"),
    Argument("keep-session", ValueType.YesNo, label = "Keep This Session Outside the Tunnel"),
    Argument("text", ValueType.Secret, label = "Configuration"),
)
private val PEER_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), optional = true),
    Argument("public-key", ValueType.Text(), optional = true),
    Argument("keep-key", ValueType.YesNo, optional = true, default = "yes", fixed = true),
    Argument("preshared-key", ValueType.Secret, optional = true, placeholder = AUTO),
    Argument("allowed-ips", ValueType.Text(), optional = true, placeholder = "10.8.0.2/32"),
    Argument("endpoint", ValueType.Text(), optional = true, placeholder = "vpn.example.net:51820"),
    Argument("persistent-keepalive", ValueType.Integer(0, 65535, unit = "s"), optional = true),
    Argument("disabled", ValueType.YesNo),
    Argument("client-address", ValueType.Text(), optional = true),
    Argument("client-dns", ValueType.Text(), optional = true),
    Argument("client-allowed-ips", ValueType.Text(), optional = true, placeholder = "0.0.0.0/0, ::/0"),
    Argument("client-endpoint", ValueType.Text(), optional = true),
    Argument("client-mtu", ValueType.Integer(576, 65535), optional = true),
    Argument("client-keepalive", ValueType.Integer(0, 65535, unit = "s"), optional = true),
)

// the files of NetworkManager and systemd-networkd, which only WireGuard has, while their manager runs
private val MANAGERS_STATE = $$"""if [ "$(nmcli -t -f RUNNING general 2>/dev/null)" = running ]; then
for c in /etc/NetworkManager/system-connections/* /run/NetworkManager/system-connections/*; do [ -f "$c" ] && grep -qx 'type=wireguard' "$c" && f NetworkManager "$c"; done
fi
if systemctl is-active -q systemd-networkd 2>/dev/null; then
w=
for c in /etc/systemd/network/*.netdev /run/systemd/network/*.netdev /usr/lib/systemd/network/*.netdev; do [ -f "$c" ] && grep -qix 'kind[[:space:]]*=[[:space:]]*wireguard' "$c" && f systemd-networkd "$c" && w="$w $(v name "$c")"; done
for c in /etc/systemd/network/*.network /run/systemd/network/*.network /usr/lib/systemd/network/*.network; do
[ -f "$c" ] || continue
for n in $w; do grep -qiE "^[[:space:]]*name[[:space:]]*=(.*[[:space:]])?$n([[:space:]]|\$)" "$c" && { echo "--nh-network-- $c"; cat "$c"; echo; break; }; done
done
fi"""

// one read as root: the private keys and the preshared keys are masked on the host, and the public key of an interface is worked
// out there from its private key. A line of awg show for an interface has the disguise between the port and the mark, the mark last;
// the read keeps of it what wg shows, so no parameter of the disguise, the key of header protection among them, leaves the host
internal fun wireGuardState(kind: WgKind) = $$"""echo "--nh-now-- $(date +%s)"
$${kind.tool} show all dump 2>/dev/null | awk -F '\t' -v OFS='\t' 'NF != 9 { $0 = $1 OFS "" OFS $3 OFS $4 OFS $NF } NF == 9 && $3 != "(none)" { $3 = "(set)" } { print "--nh-dump-- " $0 }'
m() { awk '{ l = tolower($0); sub(/^[ \t]+/, "", l); sub(/^#\[disabled\][ \t]*/, "", l) } l ~ /^(privatekey|presharedkey|private-key|preshared-key)[ \t]*=/ { sub(/=.*/, "=") } { print }' "$1"; }
v() { awk -v k="$1" '{ l = tolower($0); sub(/^[ \t]+/, "", l) } index(l, k) == 1 && substr(l, length(k) + 1) ~ /^[ \t]*=/ { sub(/^[^=]*=[ \t]*/, ""); sub(/[ \t\r]+$/, ""); print; exit }' "$2"; }
f() { echo "--nh-file-- $1 $2"; m "$2"; k=$(v privatekey "$2"); [ -n "$k" ] || k=$(v private-key "$2"); p=$(v privatekeyfile "$2"); [ -n "$k" ] || [ ! -f "$p" ] || k=$(cat "$p"); [ -z "$k" ] || echo "--nh-public-- $(printf '%s\n' "$k" | $${kind.tool} pubkey 2>/dev/null)"; }
for c in $${kind.dir}/*.conf; do [ -f "$c" ] && f wg-quick "$c"; done
$${if (kind == WIREGUARD) MANAGERS_STATE else ""}
for c in $${kind.dir}/*.conf; do [ -f "$c" ] || continue; n=${c##*/}; n=${n%.conf}; e=$(systemctl is-enabled "$${kind.quick}@$n" 2>/dev/null); [ ! -e "/etc/runlevels/default/$${kind.quick}.$n" ] || e=enabled; echo "--nh-auto-- $n $e"; done
for c in $${kind.kept}/*/*; do [ -f "$c" ] && echo "--nh-kept-- $c"; done
$$NAT_STATE
[ ! -e /etc/init.d/$${kind.quick} ] || echo --nh-openrc--
true"""

internal val WIREGUARD_STATE = wireGuardState(WIREGUARD)
