package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.random.Random

// sing-box and Xray of a Linux host: a table of the inbounds of their JSON config and one of their users, the file whole in the editor of its
// window, which the program itself checks (sing-box check, xray run -test) before it takes the place of the old one, and the link of a user for
// its client with the QR. A new user gets a UUID or a password the host makes; the read masks them, and a link comes only at its button. The
// tables write the file anew from its JSON; a file with the comments Xray allows is no JSON for them and is changed in the editor alone. A new
// inbound is the wizard of a server: a common scheme with all it needs made on the host
internal class LinuxProxyJson(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, guard: LinuxGuard) {
    private val nat = LinuxVpnNat(root, tools, guard)

    val tables: Map<String, HostTable> = JSON_KINDS.flatMap { kind ->
        val present: suspend () -> Boolean = { kind.id in tools() }
        listOf(
            "proxy/${kind.id}" to HostTable(
                JSON_FIELDS, { listOfNotNull(state(kind).row()) }, settings = true, slow = true, arguments = { listOf(Argument("autostart", ValueType.YesNo, optional = true)) },
                set = { _, changes -> changes["autostart"]?.let { autostart(kind, it in ValueType.Flag.on) } },
                commands = listOf("up", "down", "config", "save-config"), run = { what, arguments -> command(kind, what, arguments) }, present = present,
            ),
            "proxy/${kind.id}/inbound" to HostTable(
                INBOUND_FIELDS, { state(kind).inbounds() }, slow = true, arguments = { serverArguments(kind) }, add = { values -> addServer(kind, values) },
                set = { id, changes -> setInbound(kind, id, changes) }, remove = { ids -> removeInbounds(kind, ids) }, commands = listOf("client-link"),
                run = { _, arguments -> inboundLinks(kind, arguments["number"].orEmpty()) }, present = present,
            ),
            "proxy/${kind.id}/user" to HostTable(
                USER_FIELDS, { state(kind).users() }, slow = true, arguments = { userArguments(kind) }, add = { values -> addUser(kind, values) },
                set = { id, changes -> setUser(kind, id, changes) }, remove = { ids -> removeUsers(kind, ids) }, commands = listOf("client-link"),
                run = { _, arguments -> listOf(userLink(kind, arguments["number"].orEmpty())) }, present = present,
            ),
        )
    }.toMap()

    suspend fun state(kind: JsonKind) = parseJsonProxy(root.output(jsonState(kind, full = false)), kind)

    suspend fun full(kind: JsonKind) = parseJsonProxy(root.output(jsonState(kind, full = true)), kind)

    private suspend fun command(kind: JsonKind, command: String, arguments: Map<String, String>): List<Item> {
        when (command) {
            "config" -> {
                val host = full(kind)
                return listOf(Item("", mapOf("file" to host.file(), "text" to host.text)))
            }
            "save-config" -> {
                val text = arguments["text"].orEmpty()
                configJson(text, "config.json")
                save(kind, text)
            }
            "up" -> {
                if (kind.sing) refuseExample(kind, full(kind).inboundList())
                ProxyService(root, tools, kind.id).bring(command)
            }
            else -> ProxyService(root, tools, kind.id).bring(command)
        }
        return emptyList()
    }

    private suspend fun autostart(kind: JsonKind, on: Boolean) {
        if (on && kind.sing) refuseExample(kind, full(kind).inboundList())
        ProxyService(root, tools, kind.id).autostart(on)
    }

    suspend fun save(kind: JsonKind, text: String) {
        refuseExample(kind, configJson(text, "config.json").listed("inbounds"))
        val host = full(kind)
        val file = host.file()
        ProxyService(root, tools, kind.id).rewrite(file, host.text, text, running(host.state), "600", checkLine(kind, file))
    }

    // the example inbound the package of sing-box ships has a password anyone can read, so the service neither starts, nor starts at boot,
    // nor takes a new file while it is there
    private fun refuseExample(kind: JsonKind, inbounds: List<JsonObject>) {
        val example = inbounds.firstOrNull(kind::example) ?: return
        throw DeviceException(ErrorCategory.ArgumentValue, "the example inbound of sing-box on port ${kind.port(example)} has a published password: remove it or give it a password of its own")
    }

    // after Install Now of sing-box: the example inbound of its package goes before anything can start it
    suspend fun dropExample() {
        val kind = JSON_KINDS.first { it.sing }
        val host = full(kind)
        val inbounds = host.inboundList()
        if (inbounds.any(kind::example)) save(kind, host.with(inbounds.filterNot(kind::example)))
    }

    // the fields of the wizard, with a port nothing on the host has taken, and those of the settings of an inbound there is, which stand where
    // its type, network and security have them: a new inbound has none of these, an inbound there is has no scheme. An empty tag is made of
    // the scheme
    private suspend fun serverArguments(kind: JsonKind): List<Argument> {
        val inbounds = state(kind).inboundList()
        val schemes = schemes(kind)
        val certificates = certificates(kind)
        val signed = schemes.filter { it in SIGNED }.map { mapOf("scheme" to it) }
        val files = signed.map { it + ("certificate" to "files") }
        return listOfNotNull(
            Argument("scheme", ValueType.Enumeration(closed = true), schemes, default = schemes.first(), labels = SCHEME_LABELS.filterKeys { it in schemes }, fixed = true),
            Argument("tag", ValueType.Text(), placeholder = AUTO),
            Argument("port", ValueType.Integer(1, 65535), default = "${freePort(kind, inbounds, listeningPorts(ssh), listOf("tcp", "udp"))}"),
            Argument("listen", ValueType.Text(), placeholder = if (kind.sing) "::" else "0.0.0.0"),
            Argument("server-name", ValueType.Text(), default = REALITY_SITE, fixed = true, depends = (schemes - "shadowsocks-2022").map { mapOf("scheme" to it) }),
            Argument("user", ValueType.Text(), default = FIRST_USER, fixed = true),
            Argument("path", ValueType.Text(), default = randomPath(), fixed = true, depends = listOf(mapOf("scheme" to "vless-ws-tls"))),
            Argument(
                "certificate", ValueType.Enumeration(closed = true), certificates, default = SELF_SIGNED, labels = CERTIFICATE_LABELS.filterKeys { it in certificates }, fixed = true,
                depends = signed,
            ),
            Argument("certificate-file", ValueType.Text(), placeholder = "/etc/letsencrypt/live/example.com/fullchain.pem", fixed = true, depends = files),
            Argument("key-file", ValueType.Text(), placeholder = "/etc/letsencrypt/live/example.com/privkey.pem", fixed = true, depends = files),
            Argument("email", ValueType.Text(), optional = true, fixed = true, depends = signed.map { it + ("certificate" to "acme") }).takeIf { kind.sing },
            Argument("obfs", ValueType.YesNo, default = "yes", depends = listOf(mapOf("scheme" to "hysteria2"), mapOf("type" to "hysteria2"))).takeIf { kind.sing },
            Argument("obfs-password", ValueType.Secret, optional = true, placeholder = AUTO, depends = listOf(mapOf("type" to "hysteria2", "obfs" to "true"))).takeIf { kind.sing },
            Argument("up-mbps", ValueType.Integer(0, 100_000, unit = "Mbit/s"), optional = true, depends = HYSTERIA).takeIf { kind.sing },
            Argument("down-mbps", ValueType.Integer(0, 100_000, unit = "Mbit/s"), optional = true, depends = HYSTERIA).takeIf { kind.sing },
            Argument("network", ValueType.Enumeration(closed = true), if (kind.sing) SING_BOX_NETWORKS else XRAY_NETWORKS, depends = TRANSPORTED),
            Argument("transport-path", ValueType.Text(), optional = true, placeholder = "/", depends = PATHED),
            Argument("transport-host", ValueType.Text(), optional = true, depends = PATHED),
            Argument("service-name", ValueType.Text(), optional = true, depends = listOf(mapOf("network" to "grpc"))),
            Argument("security", ValueType.Enumeration(closed = true), listOf("none", "tls", "reality"), depends = TRANSPORTED),
            Argument("server-names", ValueType.Text(), optional = true, depends = listOf(mapOf("security" to "tls"), mapOf("security" to "reality"))),
            Argument("alpn", ValueType.Text(), optional = true, placeholder = "h2,http/1.1", depends = TLS),
            Argument("certificate-path", ValueType.Text(), optional = true, depends = TLS),
            Argument("key-path", ValueType.Text(), optional = true, depends = TLS),
            Argument("dest", ValueType.Text(), optional = true, placeholder = "$REALITY_SITE:443", depends = REALITY),
            Argument("short-ids", ValueType.Text(), optional = true, depends = REALITY),
            Argument("private-key", ValueType.Secret, optional = true, placeholder = AUTO, depends = REALITY),
            Argument("sniffing", ValueType.YesNo, depends = SNIFFED).takeIf { !kind.sing },
            Argument("method", ValueType.Enumeration(closed = true), SS_CIPHERS, depends = SHADOWSOCKS),
            Argument("password", ValueType.Secret, optional = true, placeholder = AUTO, depends = SHADOWSOCKS),
        )
    }

    // a server of one of the schemes and its first user, all it needs made on the host: the UUID by the kernel and the key of Reality by the
    // program for VLESS with Reality over TCP and XTLS Vision, two keys of /dev/urandom for Shadowsocks 2022 (of the server and of the user),
    // a password or a UUID and a certificate for Trojan, VLESS over WebSocket and Hysteria 2 of sing-box, which gets the password of
    // salamander too. A file without outbounds gets a direct one. The service starts with the new file and at boot; when it does not come up,
    // the old file comes back. Then ufw or firewalld, where one keeps the rules, opens the port. The example inbound of the package of sing-box
    // goes, as its password is in the repository of sing-box for anyone to read
    private suspend fun addServer(kind: JsonKind, values: Map<String, String>): String {
        val scheme = values["scheme"]?.ifEmpty { null } ?: SCHEMES.first()
        if (scheme !in schemes(kind)) throw DeviceException(ErrorCategory.ArgumentValue, "scheme: $scheme is not a value it takes")
        val host = full(kind)
        val inbounds = host.inboundList().filterNot(kind::example)
        val listening = listeningPorts(ssh)
        val protocols = PROTOCOLS.getValue(scheme)
        val tag = values["tag"]?.ifEmpty { null } ?: freeTag(kind, inbounds, scheme)
        if (!TAG.matches(tag)) throw DeviceException(ErrorCategory.ArgumentValue, "tag: $tag is not a value it takes")
        if (inbounds.any { kind.tag(it) == tag }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has an inbound $tag already")
        val port = values["port"]?.ifEmpty { null }?.let { given ->
            given.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw DeviceException(ErrorCategory.ArgumentValue, "port: $given is not a value it takes")
        } ?: freePort(kind, inbounds, listening, protocols)
        if (inbounds.any { kind.port(it) == "$port" }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has an inbound on port $port already")
        if (protocols.any { "$port/$it" in listening }) throw DeviceException(ErrorCategory.ArgumentValue, "port $port is taken on the host")
        val site = values["server-name"]?.ifEmpty { null } ?: REALITY_SITE
        if (!HOST_NAME.matches(site)) throw DeviceException(ErrorCategory.ArgumentValue, "server-name: $site is not a value it takes")
        val user = values["user"]?.ifEmpty { null } ?: FIRST_USER
        if (!JSON_NAME.matches(user)) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
        val listen = values["listen"]?.trim().orEmpty()
        checkedSetting("listen", listen)
        val provider = SING_BOX_14.find(host.version)?.destructured?.let { (major, minor) -> major.toInt() > 1 || major.toInt() == 1 && minor.toInt() >= 14 } ?: true
        val made = when (scheme) {
            "shadowsocks-2022" -> {
                val keys = generated("head -c 16 /dev/urandom | base64; head -c 16 /dev/urandom | base64", PSK_16, 2)
                kind.shadowsocks(tag, port, user, keys[0], keys[1])
            }
            "hysteria2" -> {
                val passwords = generated("head -c 18 /dev/urandom | base64; head -c 18 /dev/urandom | base64", PASSWORD_18, 2)
                val tls = kind.serverTls(site, listOf("h3"), certificate(kind, values, site), provider)
                kind.hysteria2(tag, port, user, passwords[0], passwords[1].takeIf { values["obfs"] !in OFF }, tls)
            }
            "trojan-tls" -> {
                val tls = kind.serverTls(site, listOf("h2", "http/1.1"), certificate(kind, values, site), provider)
                kind.trojan(tag, port, user, generated("head -c 18 /dev/urandom | base64", PASSWORD_18, 1).first(), tls)
            }
            "vless-ws-tls" -> {
                val path = values["path"]?.ifEmpty { null } ?: randomPath()
                if (!WS_PATH.matches(path)) throw DeviceException(ErrorCategory.ArgumentValue, "path: $path is not a value it takes")
                val tls = kind.serverTls(site, listOf("http/1.1"), certificate(kind, values, site), provider)
                kind.vlessWs(tag, port, user, generated("cat /proc/sys/kernel/random/uuid", UUID, 1).first(), path, tls)
            }
            else -> {
                val command = "cat /proc/sys/kernel/random/uuid; head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \\n'; echo; ${kind.keys}"
                val lines = ssh.exec(command).output.lines().map { it.trim() }
                val uuid = lines.firstOrNull { UUID.matches(it) }
                val shortId = lines.firstOrNull { SHORT_ID.matches(it) }
                val key = lines.firstNotNullOfOrNull { PRIVATE_KEY.matchEntire(it)?.groupValues?.get(1) }
                if (uuid == null || shortId == null || key == null) throw DeviceException(ErrorCategory.Api, "the host made no key: ${kind.keys}")
                kind.reality(tag, port, site, user, uuid, key, shortId)
            }
        }
        val inbound = if (listen.isEmpty()) made else kind.edited(made, "listen", listen)
        val text = host.with(inbounds + inbound, host.outboundList().ifEmpty { listOf(kind.direct) })
        val service = ProxyService(root, tools, kind.id)
        service.rewrite(host.path, host.text.ifBlank { "{}" }, text, running(host.state), "600", checkLine(kind, host.path), start = true)
        service.autostart(true)
        nat.open("${kind.id}-$tag", protocols.map { "$port/$it" })
        return "${inbounds.size + 1}"
    }

    // lines the host makes, as many as needed of those the pattern takes
    private suspend fun generated(command: String, pattern: Regex, count: Int): List<String> {
        val lines = ssh.exec(command).output.lines().map { it.trim() }.filter { pattern.matches(it) }
        if (lines.size < count) throw DeviceException(ErrorCategory.Api, "the host made no key: $command")
        return lines
    }

    // the certificate of a server of TLS as the wizard was told: one the program signs itself for the name for ten years, files the host has,
    // or ACME of sing-box, which needs a name of this host
    private suspend fun certificate(kind: JsonKind, values: Map<String, String>, site: String): ServerCertificate {
        val choice = values["certificate"]?.ifEmpty { null } ?: SELF_SIGNED
        if (choice !in certificates(kind)) throw DeviceException(ErrorCategory.ArgumentValue, "certificate: $choice is not a value it takes")
        return when (choice) {
            "files" -> {
                val files = listOf("certificate-file", "key-file").map { field ->
                    values[field].orEmpty().also { if (!FILE_PATH.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $it is not a value it takes") }
                }
                files.firstOrNull { root.exec("test -r ${quote(it)}").exitStatus != 0 }?.let { throw DeviceException(ErrorCategory.MissingItem, "the host has no file $it") }
                ServerCertificate.Files(files[0], files[1])
            }
            "acme" -> {
                if (site == REALITY_SITE) throw DeviceException(ErrorCategory.ArgumentValue, "ACME needs a domain of this host in server-name, not $site")
                val email = values["email"]?.ifEmpty { null }
                if (email != null && !EMAIL.matches(email)) throw DeviceException(ErrorCategory.ArgumentValue, "email: $email is not a value it takes")
                ServerCertificate.Acme(email)
            }
            else -> {
                val command = if (kind.sing) "sing-box generate tls-keypair ${quote(site)} -m 120" else "xray tls cert --domain=${quote(site)} --expire=87600h"
                val output = ssh.exec(command).output
                val pair = if (kind.sing) {
                    val blocks = PEM_BLOCK.findAll(output).toList()
                    val certificate = blocks.firstOrNull { it.groupValues[1] == "CERTIFICATE" }?.value
                    val key = blocks.firstOrNull { it.groupValues[1].endsWith("PRIVATE KEY") }?.value
                    if (certificate == null || key == null) null else certificate.lines() to key.lines()
                } else {
                    val json = try {
                        Json.parseToJsonElement(output) as? JsonObject
                    } catch (e: SerializationException) {
                        null
                    }
                    val certificate = json.strings("certificate")
                    val key = json.strings("key")
                    if (certificate.isEmpty() || key.isEmpty()) null else certificate to key
                }
                val (certificate, key) = pair ?: throw DeviceException(ErrorCategory.Api, "the host made no key: $command")
                ServerCertificate.Lines(certificate, key)
            }
        }
    }

    // the port the wizard opened for an inbound closes with it; the tags of others never had one
    private suspend fun removeInbounds(kind: JsonKind, ids: List<String>) {
        val host = full(kind)
        val inbounds = host.inboundList()
        val gone = ids.map { id -> id.toIntOrNull()?.takeIf { it in 1..inbounds.size } ?: throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no inbound $id") }
        save(kind, host.with(inbounds.filterIndexed { index, _ -> index + 1 !in gone }))
        for (index in gone) kind.tag(inbounds[index - 1]).takeIf { TAG.matches(it) }?.let { nat.open("${kind.id}-$it", emptyList()) }
    }

    // a change of an inbound in its form: what was touched goes to the config where its program keeps it, the settings the others hang on
    // first. A secret left empty or auto the host makes anew, an inbound turned to TLS or Reality gets what it lacks, a new cipher of
    // Shadowsocks 2022 new keys where the old ones do not fit it. The port the app opened for the inbound goes with its tag and its port
    private suspend fun setInbound(kind: JsonKind, id: String?, changes: Map<String, String?>) {
        val host = full(kind)
        val inbounds = host.inboundList()
        val index = id?.toIntOrNull()?.takeIf { it in 1..inbounds.size } ?: throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no inbound $id")
        val before = inbounds[index - 1]
        val others = inbounds.filterIndexed { at, _ -> at != index - 1 }
        val wanted = changes.mapValues { (_, value) -> value.orEmpty().trim() }.toMutableMap()
        when {
            wanted["obfs"] in OFF -> wanted.remove("obfs-password")
            wanted["obfs"] != null && before["obfs"] == null -> wanted.getOrPut("obfs-password") { "" }
        }
        wanted["tag"]?.let { tag ->
            if (!TAG.matches(tag)) throw DeviceException(ErrorCategory.ArgumentValue, "tag: $tag is not a value it takes")
            if (others.any { kind.tag(it) == tag }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has an inbound $tag already")
        }
        wanted["port"]?.let { port ->
            if (port.toIntOrNull() !in 1..65535) throw DeviceException(ErrorCategory.ArgumentValue, "port: $port is not a value it takes")
            if (others.any { kind.port(it) == port }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has an inbound on port $port already")
            if (port != kind.port(before) && protocolsOf(kind, before).any { "$port/$it" in listeningPorts(ssh) }) throw DeviceException(ErrorCategory.ArgumentValue, "port $port is taken on the host")
        }
        var inbound = before
        for ((field, value) in wanted.entries.sortedBy { it.key !in FIRST }) {
            val given = when {
                field !in SECRETS -> value.also {
                    checkedSetting(field, it)
                    if (field == "server-names" && ',' in it && (kind.sing || kind.tls(inbound).security != "reality")) throw DeviceException(ErrorCategory.ArgumentValue, "server-names: $it is not a value it takes")
                }
                value.isEmpty() || value == AUTO -> secret(kind, inbound, field)
                field == "private-key" -> value.also { realityPublicKey(it) }
                value.any { it == '\n' || it == '\r' } -> throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
                else -> value
            }
            inbound = kind.edited(inbound, field, given)
        }
        when (wanted["security"]) {
            "reality" -> inbound = toReality(kind, inbound, "server-names" in wanted)
            "tls" -> inbound = toTls(kind, inbound)
        }
        if ("method" in wanted) inbound = rekeyed(kind, inbound, "password" in wanted)
        if (inbound == before) return
        val config = JsonObject(host.json + ("inbounds" to JsonArray(inbounds.mapIndexed { at, it -> if (at == index - 1) inbound else it })))
        save(kind, host.encoded(kind.retagged(config, kind.tag(before), kind.tag(inbound))))
        val tag = kind.tag(inbound)
        if (tag != kind.tag(before) || kind.port(inbound) != kind.port(before)) {
            kind.tag(before).takeIf { it != tag && TAG.matches(it) }?.let { nat.open("${kind.id}-$it", emptyList()) }
            if (TAG.matches(tag)) nat.open("${kind.id}-$tag", protocolsOf(kind, inbound).map { "${kind.port(inbound)}/$it" })
        }
    }

    // a secret of an inbound the host makes: the private key of Reality by the program, the key of Shadowsocks as long as its cipher wants it,
    // the password of salamander
    private suspend fun secret(kind: JsonKind, inbound: JsonObject, field: String): String = when (field) {
        "private-key" -> ssh.exec(kind.keys).output.lines().firstNotNullOfOrNull { PRIVATE_KEY.matchEntire(it.trim())?.groupValues?.get(1) }
            ?: throw DeviceException(ErrorCategory.Api, "the host made no key: ${kind.keys}")
        "password" -> made("password", kind.method(inbound))
        else -> generated("head -c 18 /dev/urandom | base64", PASSWORD_18, 1).first()
    }

    // an inbound turned to Reality gets what it lacks: a private key of the program, a short ID of the host and the handshake of the site the
    // wizard borrows, under the name of that site unless the form gave one
    private suspend fun toReality(kind: JsonKind, inbound: JsonObject, named: Boolean): JsonObject {
        val settings = kind.inboundSettings(inbound)
        var reality = inbound
        if (kind.tls(reality).privateKey.isEmpty()) reality = kind.edited(reality, "private-key", secret(kind, reality, "private-key"))
        if ("short-ids" !in settings) reality = kind.edited(reality, "short-ids", generated("head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \\n'; echo", SHORT_ID, 1).first())
        if ("dest" !in settings) {
            reality = kind.edited(reality, "dest", "$REALITY_SITE:443")
            if (!named) reality = kind.edited(reality, "server-names", REALITY_SITE)
        }
        return reality
    }

    // an inbound turned to TLS with no certificate gets one the program signs itself for its name, as the wizard makes it
    private suspend fun toTls(kind: JsonKind, inbound: JsonObject): JsonObject {
        val settings = kind.inboundSettings(inbound)
        val acme = (inbound["tls"] as? JsonObject)?.let { it["acme"] != null || it["certificate_provider"] != null } == true
        if (kind.inlineCertificate(inbound) != null || "certificate-path" in settings || acme) return inbound
        val site = settings["server-names"] ?: REALITY_SITE
        val tls = kind.serverTls(site, settings["alpn"]?.split(',') ?: listOf("h2", "http/1.1"), certificate(kind, emptyMap(), site), provider = true)
        return if (kind.sing) JsonObject(inbound + ("tls" to tls)) else inbound.placed(listOf("streamSettings", "tlsSettings"), tls)
    }

    // a key of Shadowsocks 2022 is as long as its cipher wants it: one of the server or of a user that does not fit the new cipher is made anew
    private suspend fun rekeyed(kind: JsonKind, inbound: JsonObject, given: Boolean): JsonObject {
        val method = kind.method(inbound)
        if (!method.startsWith("2022-")) return inbound
        val size = if ("128" in method) 16 else 32
        val server = (if (kind.sing) inbound else inbound["settings"] as? JsonObject).str("password")
        val keyed = if (given || keySize(server) == size) inbound else kind.edited(inbound, "password", made("password", method))
        val users = kind.users(keyed)
        val fitted = users.map { user -> if (keySize(user.str("password")) == size) user else JsonObject(user + ("password" to JsonPrimitive(made("password", method)))) }
        return if (fitted == users) keyed else kind.withUsers(keyed, fitted)
    }

    // what an inbound listens on: Hysteria 2 and TUIC on UDP, Shadowsocks on both, the rest on TCP
    private fun protocolsOf(kind: JsonKind, inbound: JsonObject) = when (kind.type(inbound)) {
        "hysteria2", "tuic" -> listOf("udp")
        "shadowsocks" -> listOf("tcp", "udp")
        else -> listOf("tcp")
    }

    private fun schemes(kind: JsonKind) = if (kind.sing) SCHEMES else SCHEMES - "hysteria2"

    private fun certificates(kind: JsonKind) = if (kind.sing) CERTIFICATES else CERTIFICATES - "acme"

    private fun randomPath() = "/" + List(8) { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    private fun freeTag(kind: JsonKind, inbounds: List<JsonObject>, scheme: String) =
        generateSequence(1) { it + 1 }.map { if (it == 1) scheme else "$scheme-$it" }.first { tag -> inbounds.none { kind.tag(it) == tag } }

    // 443, where Reality looks most like the site it borrows and QUIC like HTTP/3, then 8443, then a port above 20000 nothing has taken for
    // the protocols of the scheme
    private fun freePort(kind: JsonKind, inbounds: List<JsonObject>, listening: Set<String>, protocols: List<String>) =
        (listOf(443, 8443) + List(32) { Random.nextInt(20000, 60000) })
            .first { port -> protocols.none { "$port/$it" in listening } && inbounds.none { kind.port(it) == "$port" } }

    private suspend fun userArguments(kind: JsonKind): List<Argument> {
        val inbounds = state(kind).inboundList()
        val choices = inbounds.indices.map { "${it + 1}" }
        return listOf(
            Argument("inbound", ValueType.Enumeration(closed = true), choices, default = choices.firstOrNull(), fixed = true,
                labels = inbounds.mapIndexed { index, inbound -> "${index + 1}" to "${kind.tag(inbound).ifEmpty { "#${index + 1}" }} (${kind.type(inbound)})" }.toMap()),
            Argument("name", ValueType.Text()),
            Argument("secret", ValueType.Secret, optional = true),
            Argument("flow", ValueType.Text(), optional = true, placeholder = VISION),
        )
    }

    private suspend fun addUser(kind: JsonKind, values: Map<String, String>): String {
        val host = full(kind)
        val inbounds = host.inboundList()
        val index = values["inbound"]?.toIntOrNull()?.takeIf { it in 1..inbounds.size } ?: throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no inbound ${values["inbound"]}")
        val inbound = inbounds[index - 1]
        val type = kind.type(inbound)
        val key = kind.secretKey(type) ?: throw DeviceException(ErrorCategory.Unsupported, "the app adds no users to an inbound of $type")
        val name = values["name"].orEmpty()
        checkName(kind, kind.users(inbound), name)
        val secret = secretOf(values["secret"], key, kind.method(inbound))
        val user = buildJsonObject {
            put(kind.nameKey, name)
            put(key, secret)
            values["flow"]?.ifEmpty { null }?.let { put("flow", it) }
        }
        val users = kind.users(inbound) + user
        save(kind, host.with(inbounds.mapIndexed { at, it -> if (at == index - 1) kind.withUsers(it, users) else it }))
        return "$index/${users.size}"
    }

    // a user renamed, given a flow or a new secret, which the host makes when the field is left empty
    private suspend fun setUser(kind: JsonKind, id: String?, changes: Map<String, String?>) {
        val host = full(kind)
        val (index, at) = userAt(host, kind, id.orEmpty())
        val inbounds = host.inboundList()
        val users = kind.users(inbounds[index])
        var user = users[at]
        changes["name"]?.let { name ->
            checkName(kind, users.filterIndexed { other, _ -> other != at }, name)
            user = JsonObject(user + (kind.nameKey to JsonPrimitive(name)))
        }
        if ("secret" in changes) {
            val type = kind.type(inbounds[index])
            val key = kind.secretKey(type) ?: throw DeviceException(ErrorCategory.Unsupported, "the app adds no users to an inbound of $type")
            user = JsonObject(user + (key to JsonPrimitive(secretOf(changes["secret"], key, kind.method(inbounds[index])))))
        }
        if ("flow" in changes) user = JsonObject(changes["flow"]?.trim()?.ifEmpty { null }?.let { user + ("flow" to JsonPrimitive(it)) } ?: (user - "flow"))
        val changed = users.mapIndexed { other, old -> if (other == at) user else old }
        save(kind, host.with(inbounds.mapIndexed { place, it -> if (place == index) kind.withUsers(it, changed) else it }))
    }

    private fun checkName(kind: JsonKind, others: List<JsonObject>, name: String) {
        if (!JSON_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (others.any { kind.name(it) == name }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has the user $name already")
    }

    // the secret of a user as it was given, or one the host makes
    private suspend fun secretOf(given: String?, key: String, method: String): String {
        val secret = given?.ifEmpty { null } ?: made(key, method)
        if (secret.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        return secret
    }

    private suspend fun removeUsers(kind: JsonKind, ids: List<String>) {
        val host = full(kind)
        val gone = ids.map { userAt(host, kind, it) }
        val inbounds = host.inboundList().mapIndexed { index, inbound ->
            val users = kind.users(inbound)
            if (gone.none { it.first == index }) inbound else kind.withUsers(inbound, users.filterIndexed { at, _ -> (index to at) !in gone })
        }
        save(kind, host.with(inbounds))
    }

    private fun userAt(host: JsonProxyState, kind: JsonKind, id: String): Pair<Int, Int> {
        val (inbound, user) = id.split('/').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
        val users = host.inboundList().getOrNull(inbound - 1)?.let(kind::users).orEmpty()
        if (user !in 1..users.size) throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no user $id")
        return inbound - 1 to user - 1
    }

    // a key of 2022 is as long as its cipher wants it, other passwords and the UUIDs the kernel makes are random as well
    private suspend fun made(key: String, method: String): String {
        val command = when {
            key != "password" -> "cat /proc/sys/kernel/random/uuid"
            method.startsWith("2022-") -> "head -c ${if ("128" in method) 16 else 32} /dev/urandom | base64"
            else -> "head -c 18 /dev/urandom | base64"
        }
        return ssh.exec(command).output.trim().ifEmpty { throw DeviceException(ErrorCategory.Api, "the host made no key: $command") }
    }

    private suspend fun userLink(kind: JsonKind, id: String): Item {
        val host = full(kind)
        val (index, at) = userAt(host, kind, id)
        val inbound = host.inboundList()[index]
        return link(kind, inbound, kind.users(inbound)[at])
    }

    // the links of all users of an inbound; one of shadowsocks without users has the link of its own password
    private suspend fun inboundLinks(kind: JsonKind, id: String): List<Item> {
        val inbound = full(kind).inboundList().getOrNull((id.toIntOrNull() ?: 0) - 1) ?: throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no inbound $id")
        val users = kind.users(inbound)
        if (users.isEmpty() && kind.type(inbound) == "shadowsocks") return listOf(link(kind, inbound, JsonObject(emptyMap())))
        return users.map { link(kind, inbound, it) }
    }

    private suspend fun link(kind: JsonKind, inbound: JsonObject, user: JsonObject): Item {
        val type = kind.type(inbound)
        val listen = inbound.str("listen")
        val address = (if (listen in ANYWHERE) localAddress(ssh) else listen).let { if (':' in it) "[$it]" else it }
        val port = kind.port(inbound)
        val name = urlEncoded(kind.name(user).ifEmpty { kind.tag(inbound) })
        val secret = user.str(kind.secretKey(type).orEmpty())
        val tls = kind.tls(inbound)
        val embedded = kind.inlineCertificate(inbound)
        val query = buildList {
            add("security" to tls.security)
            tls.serverName.ifEmpty { null }?.let { add("sni" to it) }
            if (tls.security == "reality") {
                add("fp" to "chrome")
                add("pbk" to realityPublicKey(tls.privateKey))
                tls.shortId.ifEmpty { null }?.let { add("sid" to it) }
                // the spider path of Xray clients; the clients of this app also take it for a server of Xray
                if (!kind.sing) add("spx" to "/")
            }
            // a certificate in the config is one the program signed itself: clients of sing-box skip its check, Xray 26 pins it
            if (tls.security == "tls" && embedded != null) {
                add("allowInsecure" to "1")
                certificatePin(embedded)?.let { add("pcs" to it) }
            }
            add("type" to tls.network)
            tls.path.ifEmpty { null }?.let { add((if (tls.network == "grpc") "serviceName" else "path") to it) }
            tls.host.ifEmpty { null }?.let { add("host" to it) }
            user.str("flow").ifEmpty { null }?.let { add("flow" to it) }
        }.joinToString("&") { (key, value) -> "$key=${urlEncoded(value)}" }
        val conf = when (type) {
            "vless" -> "vless://$secret@$address:$port?encryption=none&$query#$name"
            "trojan" -> "trojan://${urlEncoded(secret)}@$address:$port?$query#$name"
            "hysteria2" -> "hy2://${urlEncoded(secret)}@$address:$port?${hysteriaQuery(inbound, tls, embedded != null)}#$name"
            "vmess" -> "vmess://" + Base64.encode(
                buildJsonObject {
                    put("v", "2"); put("ps", kind.name(user)); put("add", address.removeSurrounding("[", "]")); put("port", port); put("id", secret); put("aid", "0")
                    put("net", tls.network); put("type", "none"); put("host", tls.host); put("path", tls.path); put("tls", if (tls.security == "tls") "tls" else ""); put("sni", tls.serverName)
                }.toString().encodeToByteArray(),
            )
            "shadowsocks" -> {
                val method = kind.method(inbound).ifEmpty { user.str("method") }
                val password = listOf((if (kind.sing) inbound else inbound["settings"] as? JsonObject).str("password"), secret).filter { it.isNotEmpty() }.distinct().joinToString(":")
                "ss://" + Base64.UrlSafe.encode("$method:$password".encodeToByteArray()).trimEnd('=') + "@$address:$port#$name"
            }
            else -> throw DeviceException(ErrorCategory.Unsupported, "the app makes no link for an inbound of $type")
        }
        return Item("", mapOf("conf" to conf))
    }

    // a certificate in the config itself is one sing-box signed itself, which the client cannot check; salamander goes with its password
    private fun hysteriaQuery(inbound: JsonObject, tls: JsonTls, selfSigned: Boolean): String {
        val obfs = inbound["obfs"] as? JsonObject
        return listOfNotNull(
            tls.serverName.ifEmpty { null }?.let { "sni" to it },
            ("insecure" to "1").takeIf { selfSigned },
            obfs?.let { "obfs" to it.str("type") },
            obfs?.let { "obfs-password" to it.str("password") },
        ).joinToString("&") { (key, value) -> "$key=${urlEncoded(value)}" }
    }
}

// the two programs differ in names: sing-box keeps type, listen_port, users with name and tls, Xray protocol, port, settings.clients with
// email and streamSettings
internal class JsonKind(val id: String, val confs: List<String>, val check: String, val sing: Boolean) {
    val nameKey = if (sing) "name" else "email"

    // the program makes the key pair of Reality and prints the private key on a line of its own, PrivateKey or Private key
    val keys = if (sing) "sing-box generate reality-keypair" else "xray x25519"

    val direct: JsonObject = buildJsonObject {
        put(if (sing) "type" else "protocol", if (sing) "direct" else "freedom")
        put("tag", "direct")
    }

    fun tag(inbound: JsonObject) = inbound.str("tag")

    fun type(inbound: JsonObject) = inbound.str(if (sing) "type" else "protocol")

    fun port(inbound: JsonObject) = inbound.str(if (sing) "listen_port" else "port")

    // the cipher of Shadowsocks, in the inbound of sing-box and in its settings in Xray
    fun method(inbound: JsonObject) = (if (sing) inbound else inbound["settings"] as? JsonObject).str("method")

    fun name(user: JsonObject) = user.str(nameKey)

    fun users(inbound: JsonObject): List<JsonObject> =
        ((if (sing) inbound["users"] else (inbound["settings"] as? JsonObject)?.get("clients")) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun withUsers(inbound: JsonObject, users: List<JsonObject>): JsonObject = if (sing) {
        JsonObject(inbound + ("users" to JsonArray(users)))
    } else {
        JsonObject(inbound + ("settings" to JsonObject(((inbound["settings"] as? JsonObject) ?: JsonObject(emptyMap())) + ("clients" to JsonArray(users)))))
    }

    // the inbound of shadowsocks in the example config the package of sing-box ships
    fun example(inbound: JsonObject) = sing && type(inbound) == "shadowsocks" && inbound.str("password") in SING_BOX_EXAMPLE_PASSWORDS

    // an inbound of VLESS with Reality over TCP and XTLS Vision, the handshake borrowed from port 443 of the site
    fun reality(tag: String, port: Int, site: String, user: String, uuid: String, key: String, shortId: String): JsonObject = if (sing) {
        buildJsonObject {
            put("type", "vless")
            put("tag", tag)
            put("listen", "::")
            put("listen_port", port)
            putJsonArray("users") { addJsonObject { put("name", user); put("uuid", uuid); put("flow", VISION) } }
            putJsonObject("tls") {
                put("enabled", true)
                put("server_name", site)
                putJsonObject("reality") {
                    put("enabled", true)
                    putJsonObject("handshake") { put("server", site); put("server_port", 443) }
                    put("private_key", key)
                    putJsonArray("short_id") { add(shortId) }
                }
            }
        }
    } else {
        buildJsonObject {
            put("tag", tag)
            put("listen", "0.0.0.0")
            put("port", port)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("clients") { addJsonObject { put("id", uuid); put("email", user); put("flow", VISION) } }
                put("decryption", "none")
            }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("dest", "$site:443")
                    putJsonArray("serverNames") { add(site) }
                    put("privateKey", key)
                    putJsonArray("shortIds") { add(shortId) }
                }
            }
        }
    }

    // an inbound of Shadowsocks 2022 with the key of the server and its first user, whose link holds both keys
    fun shadowsocks(tag: String, port: Int, user: String, server: String, password: String): JsonObject = if (sing) {
        buildJsonObject {
            put("type", "shadowsocks")
            put("tag", tag)
            put("listen", "::")
            put("listen_port", port)
            put("method", SS_2022)
            put("password", server)
            putJsonArray("users") { addJsonObject { put("name", user); put("password", password) } }
        }
    } else {
        buildJsonObject {
            put("tag", tag)
            put("listen", "0.0.0.0")
            put("port", port)
            put("protocol", "shadowsocks")
            putJsonObject("settings") {
                put("method", SS_2022)
                put("password", server)
                put("network", "tcp,udp")
                putJsonArray("clients") { addJsonObject { put("password", password); put("email", user) } }
            }
        }
    }

    // an inbound of Hysteria 2 of sing-box, with salamander where it has a password
    fun hysteria2(tag: String, port: Int, user: String, password: String, obfs: String?, tls: JsonObject) = buildJsonObject {
        put("type", "hysteria2")
        put("tag", tag)
        put("listen", "::")
        put("listen_port", port)
        putJsonArray("users") { addJsonObject { put("name", user); put("password", password) } }
        obfs?.let { putJsonObject("obfs") { put("type", "salamander"); put("password", it) } }
        put("tls", tls)
    }

    // an inbound of Trojan over TCP behind TLS
    fun trojan(tag: String, port: Int, user: String, password: String, tls: JsonObject): JsonObject = if (sing) {
        buildJsonObject {
            put("type", "trojan")
            put("tag", tag)
            put("listen", "::")
            put("listen_port", port)
            putJsonArray("users") { addJsonObject { put("name", user); put("password", password) } }
            put("tls", tls)
        }
    } else {
        buildJsonObject {
            put("tag", tag)
            put("listen", "0.0.0.0")
            put("port", port)
            put("protocol", "trojan")
            putJsonObject("settings") { putJsonArray("clients") { addJsonObject { put("password", password); put("email", user) } } }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                put("security", "tls")
                put("tlsSettings", tls)
            }
        }
    }

    // an inbound of VLESS over WebSocket behind TLS, on a path of its own
    fun vlessWs(tag: String, port: Int, user: String, uuid: String, path: String, tls: JsonObject): JsonObject = if (sing) {
        buildJsonObject {
            put("type", "vless")
            put("tag", tag)
            put("listen", "::")
            put("listen_port", port)
            putJsonArray("users") { addJsonObject { put("name", user); put("uuid", uuid) } }
            put("tls", tls)
            putJsonObject("transport") { put("type", "ws"); put("path", path) }
        }
    } else {
        buildJsonObject {
            put("tag", tag)
            put("listen", "0.0.0.0")
            put("port", port)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("clients") { addJsonObject { put("id", uuid); put("email", user) } }
                put("decryption", "none")
            }
            putJsonObject("streamSettings") {
                put("network", "ws")
                put("security", "tls")
                putJsonObject("wsSettings") { put("path", path) }
                put("tlsSettings", tls)
            }
        }
    }

    // the TLS of a server, tls of sing-box or tlsSettings of Xray: the lines of a certificate in the config, its files on the host, or ACME of
    // sing-box for the name, a certificate provider since sing-box 1.14 and acme of tls before it
    fun serverTls(site: String, alpn: List<String>, certificate: ServerCertificate, provider: Boolean): JsonObject = if (sing) {
        buildJsonObject {
            put("enabled", true)
            put("server_name", site)
            putJsonArray("alpn") { alpn.forEach { add(it) } }
            when (certificate) {
                is ServerCertificate.Lines -> {
                    putJsonArray("certificate") { certificate.certificate.forEach { add(it) } }
                    putJsonArray("key") { certificate.key.forEach { add(it) } }
                }
                is ServerCertificate.Files -> {
                    put("certificate_path", certificate.certificate)
                    put("key_path", certificate.key)
                }
                is ServerCertificate.Acme -> putJsonObject(if (provider) "certificate_provider" else "acme") {
                    if (provider) put("type", "acme")
                    putJsonArray("domain") { add(site) }
                    put("data_directory", "/var/lib/sing-box/acme")
                    certificate.email?.let { put("email", it) }
                }
            }
        }
    } else {
        buildJsonObject {
            put("serverName", site)
            putJsonArray("alpn") { alpn.forEach { add(it) } }
            putJsonArray("certificates") {
                addJsonObject {
                    when (certificate) {
                        is ServerCertificate.Lines -> {
                            putJsonArray("certificate") { certificate.certificate.forEach { add(it) } }
                            putJsonArray("key") { certificate.key.forEach { add(it) } }
                        }
                        is ServerCertificate.Files -> {
                            put("certificateFile", certificate.certificate)
                            put("keyFile", certificate.key)
                        }
                        is ServerCertificate.Acme -> throw DeviceException(ErrorCategory.ArgumentValue, "certificate: acme is not a value it takes")
                    }
                }
            }
        }
    }

    // the certificate an inbound keeps in the config itself, which no ACME and no file of the host stands for
    fun inlineCertificate(inbound: JsonObject): String? {
        val tls = if (sing) {
            inbound["tls"] as? JsonObject
        } else {
            (((inbound["streamSettings"] as? JsonObject)?.get("tlsSettings") as? JsonObject)?.get("certificates") as? JsonArray)?.firstOrNull() as? JsonObject
        }
        if (tls == null || tls["acme"] != null || tls["certificate_provider"] != null) return null
        return when (val certificate = tls["certificate"]) {
            is JsonArray -> certificate.joinToString("\n") { it.str() }
            is JsonPrimitive -> certificate.contentOrNull
            else -> null
        }
    }

    // the key of the secret of a user by the kind of the inbound, or null where the window adds none
    fun secretKey(type: String): String? = when (type) {
        "vless", "vmess" -> if (sing) "uuid" else "id"
        "trojan", "shadowsocks", "hysteria2" -> "password"
        else -> null
    }

    // reality of sing-box stands inside its tls and counts while that is enabled; the transport of Xray keeps its path and host in the
    // settings named after its network
    fun tls(inbound: JsonObject): JsonTls = if (sing) {
        val tls = inbound["tls"] as? JsonObject
        val reality = tls?.get("reality") as? JsonObject
        val transport = inbound["transport"] as? JsonObject
        val security = when {
            tls.str("enabled") != "true" -> "none"
            reality.str("enabled") == "true" -> "reality"
            else -> "tls"
        }
        JsonTls(
            security, tls.str("server_name").ifEmpty { if (security == "reality") (reality?.get("handshake") as? JsonObject).str("server") else "" }, reality.str("private_key"),
            reality?.get("short_id").let { (it as? JsonArray)?.firstOrNull() ?: it }.str(), transport.str("type").ifEmpty { "tcp" },
            transport.str("path").ifEmpty { transport.str("service_name") }, (transport?.get("headers") as? JsonObject).str("Host").ifEmpty { transport.str("host") },
        )
    } else {
        val stream = inbound["streamSettings"] as? JsonObject
        val reality = stream?.get("realitySettings") as? JsonObject
        val security = stream.str("security").ifEmpty { "none" }
        val network = stream.str("network").ifEmpty { "tcp" }
        val settings = stream?.get("${network}Settings") as? JsonObject
        JsonTls(
            security,
            if (security == "reality") ((reality?.get("serverNames") as? JsonArray)?.firstOrNull()).str() else (stream?.get("tlsSettings") as? JsonObject).str("serverName"),
            reality.str("privateKey"), ((reality?.get("shortIds") as? JsonArray)?.firstOrNull()).str(), network, settings.str("path").ifEmpty { settings.str("serviceName") },
            settings.str("host"),
        )
    }
}

internal class JsonTls(val security: String, val serverName: String, val privateKey: String, val shortId: String, val network: String, val path: String, val host: String)

// what a server of TLS answers with: the lines of a certificate and its key in the config, their files on the host, or ACME of sing-box
internal sealed interface ServerCertificate {
    class Lines(val certificate: List<String>, val key: List<String>) : ServerCertificate

    class Files(val certificate: String, val key: String) : ServerCertificate

    class Acme(val email: String?) : ServerCertificate
}

// what the host has of sing-box or Xray at one read: the state of the service, the version, the file and its text, masked for the tables
internal class JsonProxyState(private val kind: JsonKind, val state: String, val autostart: Boolean, private val config: String?, val version: String, val text: String) {
    val json: JsonObject by lazy { if (config == null) JsonObject(emptyMap()) else configJson(text, config) }

    // the file the config is in, or the one a new config goes to
    val path: String get() = config ?: kind.confs.first()

    fun file() = config ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no config file ${kind.confs.first()}")

    fun inboundList() = json.listed("inbounds")

    fun outboundList() = json.listed("outbounds")

    fun with(inbounds: List<JsonObject>, outbounds: List<JsonObject>? = null): String =
        encoded(JsonObject(json + ("inbounds" to JsonArray(inbounds)) + listOfNotNull(outbounds?.let { "outbounds" to JsonArray(it) })))

    fun encoded(config: JsonObject): String = PRETTY.encodeToString(JsonObject.serializer(), config)

    fun row(): Item? = config?.let { file ->
        Item(kind.id, linkedMapOf("state" to state, "autostart" to "$autostart", "version" to version, "config" to file, "inbounds" to "${inboundList().size}"))
    }

    fun inbounds() = inboundList().mapIndexed { index, inbound ->
        val tls = kind.tls(inbound)
        Item(
            "${index + 1}",
            linkedMapOf(
                "tag" to kind.tag(inbound), "type" to kind.type(inbound), "listen" to inbound.str("listen"), "port" to kind.port(inbound), "security" to tls.security,
                "network" to tls.network, "users" to "${kind.users(inbound).size}",
            ) + kind.inboundSettings(inbound),
        )
    }

    fun users() = inboundList().flatMapIndexed { index, inbound ->
        kind.users(inbound).mapIndexed { at, user ->
            val row = linkedMapOf("inbound" to kind.tag(inbound).ifEmpty { "#${index + 1}" }, "type" to kind.type(inbound), "name" to kind.name(user))
            Item("${index + 1}/${at + 1}", row + listOfNotNull(user.str("flow").ifEmpty { null }?.let { "flow" to it }))
        }
    }
}

internal fun parseJsonProxy(text: String, kind: JsonKind): JsonProxyState {
    val head = text.lines().firstOrNull { it.startsWith("--nh-json-- ") }?.split(' ')?.drop(1) ?: return JsonProxyState(kind, "", false, null, "", "")
    val version = text.lines().firstOrNull { it.startsWith("--nh-json-version-- ") }?.substringAfter(' ')?.trim().orEmpty()
    return JsonProxyState(kind, head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", head.getOrNull(2), version, text.substringAfter("--nh-json-conf--\n", ""))
}

internal fun configJson(text: String, file: String): JsonObject = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
} ?: throw DeviceException(ErrorCategory.ArgumentValue, "$file is no JSON object")

// the objects of an array of a config, as its inbounds and outbounds
internal fun JsonObject.listed(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

// the check of the program reads the new file in its place; its colours go, and what it says comes back masked. The PATH of sudo may leave
// out /usr/local/bin, where a release puts the program
internal fun checkLine(kind: JsonKind, file: String) =
    $$"""PATH="$PATH:/usr/local/bin"; o=$($${kind.check} '$$file' 2>&1); r=$?; e=$(printf '\033'); printf '%s\n' "$o" | sed "s/$e\[[0-9;]*m//g" | $$PROXY_MASK; exit $r"""

// the ports something on the host listens on, as 443/tcp and 443/udp
internal suspend fun listeningPorts(ssh: SshSession): Set<String> =
    parseListening(ssh.exec("ss -H -tuln 2>/dev/null").output, "").map { "${it["port"]}/${it["protocol"].orEmpty().removeSuffix("6")}" }.toSet()

// a config with its passwords, UUIDs and private keys masked, as the tables read it
internal const val JSON_MASK = """sed -E 's/("(password|uuid|id|private_key|privateKey|psk)"[[:space:]]*:[[:space:]]*")[^"]*"/\1(hidden)"/g'"""

private fun JsonObject?.str(key: String) = this?.get(key).str()

private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject?.strings(key: String) = (this?.get(key) as? JsonArray)?.map { it.str() }.orEmpty()

// the pin of a certificate as Xray 26 takes it in pcs: SHA-256 of the DER of the first certificate, in hex
private fun certificatePin(pem: String): String? {
    val body = CERTIFICATE.find(pem)?.groupValues?.get(1)?.filterNot { it.isWhitespace() } ?: return null
    val der = try {
        Base64.decode(body)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return sha256(der).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

// a key of base64 by the bytes it holds, none for what is no base64
private fun keySize(key: String) = try {
    Base64.decode(key).size
} catch (e: IllegalArgumentException) {
    0
}

internal val JSON_KINDS = listOf(
    JsonKind("sing-box", listOf("/etc/sing-box/config.json"), "sing-box check -c", sing = true),
    JsonKind("xray", listOf("/usr/local/etc/xray/config.json", "/etc/xray/config.json"), "xray run -test -c", sing = false),
)

private val PRETTY = Json { prettyPrint = true }
private val JSON_NAME = Regex("[A-Za-z0-9._@+-]+")

// a tag the wizard takes, which also names the port it opens in ufw or firewalld
private val TAG = Regex("[A-Za-z0-9_-]{1,48}")
private val ANYWHERE = setOf("", "0.0.0.0", "::", "::0", "127.0.0.1", "::1", "localhost")

// the schemes of the wizard and the protocols each listens on; Hysteria 2 is offered with sing-box alone
private val SCHEMES = listOf("vless-reality", "vless-ws-tls", "trojan-tls", "shadowsocks-2022", "hysteria2")
private val SCHEME_LABELS = mapOf(
    "vless-reality" to "VLESS + REALITY", "vless-ws-tls" to "VLESS + WS + TLS", "trojan-tls" to "Trojan + TLS", "shadowsocks-2022" to "Shadowsocks 2022", "hysteria2" to "Hysteria2",
)
private val PROTOCOLS = mapOf(
    "vless-reality" to listOf("tcp"), "vless-ws-tls" to listOf("tcp"), "trojan-tls" to listOf("tcp"), "shadowsocks-2022" to listOf("tcp", "udp"), "hysteria2" to listOf("udp"),
)

// the certificates of the schemes with TLS; ACME is sing-box alone
private const val SELF_SIGNED = "self-signed"
private val CERTIFICATES = listOf(SELF_SIGNED, "files", "acme")
private val CERTIFICATE_LABELS = mapOf(SELF_SIGNED to "Self-Signed", "files" to "Files of the Host", "acme" to "ACME")

// a site to borrow the handshake from: TLS 1.3 and HTTP/2 on 443, the rest of its handshake in one flight after Server Hello, as the README of
// Reality wants it; Xray warns of sites of Apple and Microsoft. Hysteria 2 names its certificate after it
private const val REALITY_SITE = "dl.google.com"
private const val FIRST_USER = "user1"
private const val VISION = "xtls-rprx-vision"
private const val AUTO = "auto"
private const val SS_2022 = "2022-blake3-aes-128-gcm"
private val OFF = setOf("no", "false", "0")

// the passwords the example config of sing-box, release/config/config.json in its repository, has had
internal val SING_BOX_EXAMPLE_PASSWORDS = setOf("8JCsPssfgS8tiRwiMlhARg==", "Gn1JUS14bLUHgv1cWDDp4A==")

private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val SHORT_ID = Regex("[0-9a-f]{16}")
private val PRIVATE_KEY = Regex("Private ?[Kk]ey: *([A-Za-z0-9_-]{43})")
private val HOST_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+")

// 16 and 18 random bytes in base64: a key of Shadowsocks 2022 and a password
private val PSK_16 = Regex("[A-Za-z0-9+/]{22}==")
private val PASSWORD_18 = Regex("[A-Za-z0-9+/]{24}")
private val PEM_BLOCK = Regex("-----BEGIN ([A-Z ]+)-----[\\s\\S]*?-----END \\1-----")
private val CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
private val SING_BOX_14 = Regex("(\\d+)\\.(\\d+)")
private val WS_PATH = Regex("/[A-Za-z0-9._~/-]*")
private val FILE_PATH = Regex("/[A-Za-z0-9._/@+=,~-]+")
private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

// the schemes behind a certificate
private val SIGNED = setOf("vless-ws-tls", "trojan-tls", "hysteria2")

// where the settings of an inbound there is stand in its form, by its type, network and security, and the settings the others hang on
private val TRANSPORTED = listOf("vless", "vmess", "trojan").map { mapOf("type" to it) }
private val PATHED = listOf("ws", "httpupgrade", "xhttp").map { mapOf("network" to it) }
private val TLS = listOf(mapOf("security" to "tls"))
private val REALITY = listOf(mapOf("security" to "reality"))
private val SHADOWSOCKS = listOf(mapOf("type" to "shadowsocks"))
private val HYSTERIA = listOf(mapOf("type" to "hysteria2"))
private val SNIFFED = listOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "dokodemo-door", "tunnel").map { mapOf("type" to it) }
private val SING_BOX_NETWORKS = listOf("tcp", "ws", "grpc", "httpupgrade", "quic")
private val XRAY_NETWORKS = listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp")
private val SS_CIPHERS = listOf(
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
)
private val SECRETS = setOf("private-key", "password", "obfs-password")
private val FIRST = setOf("network", "security", "method", "obfs")

private val JSON_FIELDS = listOf("state", "autostart", "version", "config", "inbounds")
private val INBOUND_FIELDS = listOf("tag", "type", "listen", "port", "security", "network", "users")
private val USER_FIELDS = listOf("inbound", "type", "name")

// the config whole for a change, or with its keys, passwords and UUIDs masked for the tables; the PATH of sudo may leave out /usr/local/bin
private fun jsonState(kind: JsonKind, full: Boolean) = $$"""PATH="$PATH:/usr/local/bin"
c=
for f in $${kind.confs.joinToString(" ")}; do [ -f "$f" ] && { c=$f; break; }; done
[ -n "$c" ] || exit 0
$$SERVICE_STATE
echo "--nh-json-- $(s $${kind.id}) $c"
echo "--nh-json-version-- $($${kind.id} version 2>/dev/null | head -n 1)"
echo --nh-json-conf--
$${if (full) "cat" else JSON_MASK} "$c"
true"""
