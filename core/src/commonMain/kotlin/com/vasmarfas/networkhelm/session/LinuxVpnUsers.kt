package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType

// the users of the VPN of a host over all its servers, as Secrets of PPP in RouterOS: a peer of WireGuard by the name in its comment, a
// client certificate of OpenVPN by its common name, a secret of EAP of strongSwan by its id, a user of ocpasswd of ocserv and a line of
// chap-secrets of the L2TP or the PPTP server are one user where the names meet. A new user gets what each server chosen needs, a peer with keys the
// host makes, a preshared key and the next free address, a certificate, a secret of EAP, which every server of IKEv2 here takes, a line
// of ocpasswd or of chap-secrets; one password goes to all services that take one, and the host makes it where none is given and EAP
// keeps it to be shown. Turning a user off, taking a server from it and removing it act on every server it is on
internal class LinuxVpnUsers(
    private val wireGuard: LinuxVpnWireGuard,
    private val openVpn: LinuxVpnOpenVpn,
    private val ipsec: LinuxVpnIpsec,
    private val ocserv: LinuxVpnOcserv,
    private val l2tp: LinuxVpnL2tp,
    private val pptp: LinuxVpnPptp,
    private val limits: LinuxVpnLimits,
    private val tools: suspend () -> Set<String>,
) {
    // what a user has on one server: wireguard/<interface>, openvpn/<server>, ipsec/eap, ocserv or l2tp, and the id of its peer, client or
    // secret there
    private class Access(val user: String, val server: String, val id: String, val disabled: Boolean, val address: String)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/user" to HostTable(
            USER, ::users, slow = true, arguments = ::arguments, add = ::add, set = ::set, remove = ::remove, commands = listOf("client-config") + EXPORTS,
            run = { command, arguments -> if (command in EXPORTS) export(arguments["number"], command) else configs(arguments["number"]) },
            present = { tools().let { "wg" in it || "openvpn" in it || "swanctl" in it || "ocserv" in it || "xl2tpd" in it || "pptpd" in it } },
        ),
    )

    private suspend fun accesses(): List<Access> {
        val present = tools()
        val peers = if ("wg" in present) wireGuard.state().peers().filter { !it["name"].isNullOrEmpty() } else emptyList()
        val clients = if ("openvpn" in present) openVpn.state().clientRows().filter { it["state"] == "valid" } else emptyList()
        val eap = if ("swanctl" in present) ipsec.eapUsers() else emptyList()
        val oc = if ("ocserv" in present) ocserv.users() else emptyList()
        val chap = if ("xl2tpd" in present) l2tp.users() else emptyList()
        val pptpd = if ("pptpd" in present) pptp.users() else emptyList()
        return peers.map { Access(it["name"].orEmpty(), "wireguard/${it["interface"]}", it.id, it["disabled"] == "true", it["allowed-ips"].orEmpty()) } +
            clients.map { Access(it["name"].orEmpty(), "openvpn/${it["server"]}", it.id, it["disabled"] == "true", it["address"].orEmpty()) } +
            eap.map { (name, disabled) -> Access(name, EAP, name, disabled, "") } + oc.map { (name, locked) -> Access(name, OCSERV, name, locked, "") } +
            chap.map { (name, off) -> Access(name, L2TP, name, off, "") } + pptpd.map { (name, off) -> Access(name, PPTP, name, off, "") }
    }

    // a user is off where it is off on every server; its limit of rates is the one of its lines in the limits
    private suspend fun users(): List<Item> {
        val limited = limits.read().associateBy { it.user }
        return accesses().groupBy { it.user }.map { (name, own) ->
            Item(
                name,
                linkedMapOf(
                    "name" to name, "disabled" to "${own.all { it.disabled }}", "servers" to own.joinToString(",") { it.server },
                    "addresses" to own.filter { it.address.isNotEmpty() }.joinToString(", ") { "${it.server.substringAfter('/')} ${it.address}" }, "password" to "",
                    "rate-limit" to limited[name]?.let { "${vpnRate(it.up)}/${vpnRate(it.down)}" }.orEmpty(),
                ),
            )
        }
    }

    private suspend fun arguments(): List<Argument> {
        val servers = servers()
        return listOf(
            Argument("name", ValueType.Text(), fixed = true),
            Argument("servers", ValueType.Enumeration(multiple = true, closed = true), servers.keys.toList(), labels = servers),
            Argument("password", ValueType.Secret, optional = true),
            Argument("disabled", ValueType.YesNo),
            Argument("rate-limit", ValueType.Text(), optional = true, placeholder = "5M/20M"),
        )
    }

    // the servers a user can be on: the interfaces of WireGuard a file keeps, the servers of OpenVPN with a PKI of easy-rsa, EAP for the
    // servers of IKEv2 made here, ocserv where its users are its own and the L2TP server made here
    private suspend fun servers(): Map<String, String> {
        val present = tools()
        val faces = if ("wg" in present) wireGuard.state().faces.filter { it.source != WgSource.Running }.map { "wireguard/${it.id}" to "${it.name} (WireGuard)" } else emptyList()
        val servers = if ("openvpn" in present) openVpn.state().servers.filter { it.pki != null }.map { "openvpn/${it.name}" to "${it.name} (OpenVPN)" } else emptyList()
        val eap = if ("swanctl" in present && ipsec.servers().isNotEmpty()) listOf(EAP to "IKEv2 (EAP)") else emptyList()
        val oc = if ("ocserv" in present && ocserv.plain()) listOf(OCSERV to "ocserv (OpenConnect)") else emptyList()
        val chap = if ("xl2tpd" in present && l2tp.configured()) listOf(L2TP to "L2TP/IPsec") else emptyList()
        val pptpd = if ("pptpd" in present && pptp.found()) listOf(PPTP to "PPTP") else emptyList()
        return (faces + servers + eap + oc + chap + pptpd).toMap()
    }

    private suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!USER_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (accesses().any { it.user == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $name already")
        val chosen = chosen(values["servers"])
        val password = values["password"].orEmpty().ifEmpty { if (EAP in chosen) ipsec.newSecret() else "" }
        for (server in chosen) give(name, server, values["disabled"].orEmpty(), password)
        values["rate-limit"]?.ifEmpty { null }?.let { limit(name, owned(name), it) }
        return name
    }

    // the servers given take what a user needs there and the servers taken lose it; a server a user is new on finds it on or off as
    // it is elsewhere. A new password goes to the services that take one
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val own = owned(id)
        val name = own.first().user
        changes["servers"]?.let { servers ->
            val chosen = chosen(servers)
            val disabled = changes["disabled"] ?: if (own.all { it.disabled }) "yes" else ""
            for (server in chosen - own.map { it.server }.toSet()) give(name, server, disabled, changes["password"].orEmpty())
            take(own.filter { it.server !in chosen })
        }
        val now = owned(id)
        changes["disabled"]?.let { disabled ->
            for (access in now) {
                when {
                    access.server == EAP -> ipsec.setEap(name, null, disabled in ValueType.Flag.on)
                    access.server == OCSERV -> ocserv.setUser(name, null, disabled in ValueType.Flag.on)
                    access.server == L2TP -> l2tp.setUser(name, null, disabled in ValueType.Flag.on)
                    access.server == PPTP -> pptp.setUser(name, null, disabled in ValueType.Flag.on)
                    access.server.startsWith("wireguard/") -> wireGuard.setPeer(access.id, mapOf("disabled" to disabled))
                    else -> openVpn.setClient(access.id, mapOf("disabled" to disabled))
                }
            }
        }
        changes["password"]?.takeIf { it.isNotEmpty() }?.let { password ->
            if (now.any { it.server == EAP }) ipsec.setEap(name, password, null)
            if (now.any { it.server == OCSERV }) ocserv.setUser(name, password, null)
            if (now.any { it.server == L2TP }) l2tp.setUser(name, password, null)
            if (now.any { it.server == PPTP }) pptp.setUser(name, password, null)
        }
        changes["rate-limit"]?.let { limit(name, now, it) }
    }

    // the limit of rates on every address a user has alone in a tunnel, which a peer of WireGuard has in its allowed IPs; the addresses
    // other servers hand out change with each session, so they take none
    private suspend fun limit(name: String, own: List<Access>, text: String) {
        val (up, down) = if (text.isEmpty()) 0L to 0L else vpnRates(text)
        val lines = if (up == 0L && down == 0L) emptyList() else own.filter { it.server.startsWith("wireguard/") }.flatMap { access ->
            access.address.split(',').map { it.trim() }.filter { it.endsWith("/32") || it.endsWith("/128") }.map { VpnLimit(name, access.server.substringAfter('/'), it, down, up) }
        }
        limits.set(name, lines)
    }

    private suspend fun remove(ids: List<String>) {
        val all = accesses()
        ids.firstOrNull { id -> all.none { it.user == id } }?.let { throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $it") }
        val limited = limits.read().map { it.user }.toSet()
        take(all.filter { it.user in ids })
        for (id in ids.filter { it in limited }) limits.set(id, emptyList())
    }

    // the configuration of each server a user is on, under the name of its server where there are several; for IKEv2 the server, login
    // and password, for ocserv the server, the pin of its certificate and the login, for L2TP the server, its preshared key and the login
    private suspend fun configs(id: String?): List<Item> {
        val own = owned(id)
        return own.flatMap { access ->
            val made = when {
                access.server == EAP -> ipsec.credentials(access.user)
                access.server == OCSERV -> ocserv.credentials(access.user)
                access.server == L2TP -> l2tp.credentials(access.user)
                access.server == PPTP -> pptp.credentials(access.user)
                access.server.startsWith("wireguard/") -> wireGuard.clientConfig(mapOf("number" to access.id))
                else -> openVpn.clientConfig(mapOf("number" to access.id))
            }
            if (own.size == 1) made else made.map { Item(it.id, mapOf("conf" to "# ${access.server}\n${it["conf"].orEmpty()}")) }
        }
    }

    private suspend fun export(id: String?, kind: String): List<Item> {
        val access = owned(id).firstOrNull { it.server == EAP } ?: throw DeviceException(ErrorCategory.Unsupported, "$id has no secret of EAP for IKEv2")
        return ipsec.export(access.user, kind)
    }

    private suspend fun owned(id: String?) = accesses().filter { it.user == id }.ifEmpty { throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $id") }

    private suspend fun chosen(text: String?): List<String> {
        val chosen = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (chosen.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "servers: a value is needed")
        val known = servers()
        chosen.firstOrNull { it !in known }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "servers: $it is not a value it takes") }
        return chosen
    }

    private suspend fun give(name: String, server: String, disabled: String, password: String) {
        val place = server.substringAfter('/')
        when {
            server == EAP -> ipsec.addEap(name, password, disabled in ValueType.Flag.on)
            server == OCSERV -> ocserv.addUser(name, password, disabled in ValueType.Flag.on)
            server == L2TP -> l2tp.addUser(name, password, disabled in ValueType.Flag.on)
            server == PPTP -> pptp.addUser(name, password, disabled in ValueType.Flag.on)
            server.startsWith("wireguard/") -> wireGuard.addPeer(mapOf("interface" to place, "name" to name, "preshared-key" to "auto", "disabled" to disabled))
            else -> openVpn.addClient(mapOf("server" to place, "name" to name, "disabled" to disabled))
        }
    }

    // a peer goes from its interface, a certificate is revoked, a secret of EAP and a line of ocpasswd go, which ends the sessions of any of
    // them; a line of chap-secrets goes as well, and the session of L2TP lasts until the client leaves
    private suspend fun take(accesses: List<Access>) {
        val peers = accesses.filter { it.server.startsWith("wireguard/") }
        val clients = accesses.filter { it.server.startsWith("openvpn/") }
        val eap = accesses.filter { it.server == EAP }
        if (peers.isNotEmpty()) wireGuard.removePeers(peers.map { it.id })
        if (clients.isNotEmpty()) openVpn.removeClients(clients.map { it.id })
        if (eap.isNotEmpty()) ipsec.removeEap(eap.map { it.user })
        accesses.filter { it.server == OCSERV }.ifEmpty { null }?.let { oc -> ocserv.removeUsers(oc.map { it.user }) }
        accesses.filter { it.server == L2TP }.ifEmpty { null }?.let { chap -> l2tp.removeUsers(chap.map { it.user }) }
        accesses.filter { it.server == PPTP }.ifEmpty { null }?.let { chap -> pptp.removeUsers(chap.map { it.user }) }
    }

    private companion object {
        const val EAP = "ipsec/eap"
        const val OCSERV = "ocserv"
        const val L2TP = "l2tp"
        const val PPTP = "pptp"
        val EXPORTS = listOf("mobileconfig", "sswan", "windows")
        val USER = listOf("name", "disabled", "servers", "addresses", "password", "rate-limit")

        // a name a comment of WireGuard, a common name of easy-rsa, an id of EAP and a user of ocpasswd all take
        val USER_NAME = Regex("[A-Za-z0-9_.@-]{1,64}")
    }
}
