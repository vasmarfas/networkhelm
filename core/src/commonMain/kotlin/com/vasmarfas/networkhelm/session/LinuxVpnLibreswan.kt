package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// Libreswan of a Linux host: the conns of /etc/ipsec.conf and /etc/ipsec.d, those made here one to a file of /etc/ipsec.d with their
// preshared key in a file of secrets of their own. pluto exits with 0 for a conn it cannot use, so a written conn counts as loaded only
// where ipsec addconn says neither failed nor unoriented; else the old file comes back. Bringing a conn up goes under the guard, as its
// subnets may take the way this session comes by; an up that fails is taken down by the guard at once, as pluto keeps reviving it
internal class LinuxVpnLibreswan(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    private var libreswan: Boolean? = null

    val tables: Map<String, HostTable> = mapOf(
        "vpn/libreswan" to HostTable(
            LIBRESWAN_FIELDS, { state().rows() }, slow = true, arguments = { LIBRESWAN_ARGUMENTS }, add = ::add, set = ::set, remove = ::remove,
            commands = listOf("up", "down"), run = ::bring, present = ::present,
        ),
    )

    // the ipsec of strongSwan has the same name
    private suspend fun present(): Boolean {
        if ("ipsec" !in tools()) return false
        return libreswan ?: ("Libreswan" in ssh.exec("ipsec --version").output).also { libreswan = it }
    }

    suspend fun state() = parseLibreswan(root.masked(LIBRESWAN_STATE))

    private suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!LIBRESWAN_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (state().conns.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a conn $name of Libreswan already")
        val settings = values.filterKeys { it in LIBRESWAN_KEYS }.filterValues { it.isNotEmpty() }
        settings.forEach { (key, value) -> libreswanCheck(key, value) }
        write(name, settings, values["psk"].orEmpty())
        return name
    }

    // a conn made here takes its settings anew; an empty preshared key leaves the one it has
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val conn = own(state(), id)
        val settings = conn.settings.toMutableMap()
        for ((key, value) in changes.filterKeys { it in LIBRESWAN_KEYS }) {
            if (value.isNullOrEmpty()) settings.remove(key) else settings[key] = value.also { libreswanCheck(key, it) }
        }
        write(conn.name, settings, changes["psk"].orEmpty())
    }

    private suspend fun remove(ids: List<String>) {
        val host = state()
        for (conn in ids.map { own(host, it) }) root.masked("ipsec delete ${quote(conn.name)} >/dev/null 2>&1; rm -f ${quote(file(conn.name))} ${quote(secrets(conn.name))}")
    }

    private suspend fun bring(command: String, arguments: Map<String, String>): List<Item> {
        val host = state()
        for (id in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) {
            val conn = host.conns.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no conn $id of Libreswan")
            val name = quote(conn.name)
            // whack prints what pluto says on stdout, and the message of a failure is taken from stderr
            when (command) {
                "up" -> guard.guarded(LinuxGuard.Backup("vpn", "true", "ipsec down $name")) { root.masked("ipsec up $name >&2") }
                "down" -> root.masked("ipsec down $name >&2")
            }
        }
        return emptyList()
    }

    private fun own(host: LibreswanState, id: String?): LibreswanConn {
        val conn = host.conns.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no conn $id of Libreswan")
        if (conn.file != file(conn.name)) throw DeviceException(ErrorCategory.Unsupported, "${conn.file} is no file of NetworkHelm, and it stays as it is")
        return conn
    }

    // the conn in a file of its own, read again by pluto, and then its key in another; a conn pluto refuses puts its old file back and
    // leaves the key as it was
    private suspend fun write(name: String, settings: Map<String, String>, psk: String) {
        if (psk.any { it == '"' || it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "psk: a quote or a line break is not a value it takes")
        val text = (listOf("conn $name") + LIBRESWAN_KEYS.mapNotNull { key -> settings[key]?.let { "    $key=$it" } }).joinToString("\n")
        val conn = quote(name)
        try {
            root.write(file(name), text, "ipsec addconn $conn 2>&1", failed = ::libreswanFailed)
        } catch (e: DeviceException) {
            root.exec("ipsec delete $conn >/dev/null 2>&1; [ ! -f ${quote(file(name))} ] || ipsec addconn $conn >/dev/null 2>&1")
            throw e
        }
        val ids = listOf(settings["leftid"] ?: settings["left"], settings["rightid"] ?: settings["right"])
            .joinToString(" ") { it?.takeIf { value -> value != "%defaultroute" } ?: "%any" }
        if (psk.isNotEmpty()) root.write(secrets(name), "$ids : PSK \"$psk\"", "ipsec secrets 2>&1")
        // a conn with new ends keeps its key, which does not leave the host
        else root.masked("f=${quote(secrets(name))}; [ ! -f \"\$f\" ] || { sed -i ${quote("s|^.* : PSK |$ids : PSK |")} \"\$f\" && ipsec secrets >&2; }")
    }

    private fun file(name: String) = "/etc/ipsec.d/networkhelm-$name.conf"

    private fun secrets(name: String) = "/etc/ipsec.d/networkhelm-$name.secrets"
}

// a conn as the files show it, its settings by key, of the file that holds it; whether pluto has it and how many SAs it has
internal class LibreswanConn(val name: String, val file: String, val settings: Map<String, String>, val loaded: Boolean, val sas: Int)

internal class LibreswanState(val conns: List<LibreswanConn>, val active: Boolean) {
    fun rows(): List<Item> = conns.map { conn ->
        Item(
            conn.name,
            linkedMapOf("name" to conn.name, "loaded" to "${conn.loaded}", "sas" to "${conn.sas}", "file" to conn.file, "psk" to "") +
                LIBRESWAN_KEYS.associateWith { conn.settings[it].orEmpty() },
        )
    }
}

// the files with their conns, the conns pluto has, the SAs of ipsec trafficstatus by conn, and the service
internal fun parseLibreswan(text: String): LibreswanState {
    val conns = mutableListOf<Triple<String, String, MutableMap<String, String>>>()
    val loaded = mutableSetOf<String>()
    val sas = mutableMapOf<String, Int>()
    var active = false
    var file = ""
    var section = ""
    var current: MutableMap<String, String>? = null
    for (line in text.lines()) {
        when {
            line.startsWith("--nh-file-- ") -> {
                file = line.removePrefix("--nh-file-- ").trim()
                section = "file"
                current = null
            }
            line.startsWith("--nh-loaded--") -> section = "loaded"
            line.startsWith("--nh-traffic--") -> section = "traffic"
            line.startsWith("--nh-unit-- ") -> active = line.removePrefix("--nh-unit-- ").trim().startsWith("active")
            section == "loaded" -> line.trim().ifEmpty { null }?.let { loaded += it }
            section == "traffic" -> LIBRESWAN_SA.find(line)?.let { sas[it.groupValues[1]] = (sas[it.groupValues[1]] ?: 0) + 1 }
            section == "file" && line.startsWith("conn ") -> current = mutableMapOf<String, String>().also { conns += Triple(line.removePrefix("conn ").trim(), file, it) }
            section == "file" && line.isNotEmpty() && !line[0].isWhitespace() -> current = null
            section == "file" && '=' in line && !line.trim().startsWith("#") -> current?.put(line.substringBefore('=').trim(), line.substringAfter('=').trim())
        }
    }
    return LibreswanState(conns.filter { it.first != "%default" }.map { (name, at, settings) -> LibreswanConn(name, at, settings, name in loaded, sas[name] ?: 0) }, active)
}

// pluto takes a conn it cannot use with exit 0, as the lab on 17.11 showed: "bad": failed to resolve 'left=nonsense' ... "bad": added
// unoriented IKEv2 connection
internal fun libreswanFailed(output: String) = output.lines().any { "failed" in it || "unoriented" in it || "error" in it.lowercase() }

private fun libreswanCheck(key: String, value: String) {
    val valid = when (key) {
        "auto" -> value in listOf("add", "start", "ondemand", "route", "ignore")
        "type" -> value in listOf("tunnel", "transport", "passthrough")
        "authby" -> value in listOf("secret", "rsasig", "ecdsa", "rsasig,ecdsa", "never")
        "keyexchange" -> value in listOf("ikev2", "ikev1")
        else -> LIBRESWAN_VALUE.matches(value)
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$key: $value is not a value it takes")
}

private val LIBRESWAN_NAME = Regex("[A-Za-z0-9_-]{1,32}")
private val LIBRESWAN_VALUE = Regex("[A-Za-z0-9%@.:/,_=+-]+")

// #3: "office"[1] 203.0.113.9, type=ESP, add_time=1794939000, inBytes=1024, outBytes=2048, ..., id='@office'; Libreswan 4 puts 006 before it
private val LIBRESWAN_SA = Regex("^(?:\\d+ )?#\\d+: \"([^\"]+)\"")

private val LIBRESWAN_KEYS = listOf(
    "left", "leftid", "leftsubnet", "right", "rightid", "rightsubnet", "keyexchange", "authby", "ike", "esp", "type", "auto", "dpddelay",
)
private val LIBRESWAN_FIELDS = listOf("name", "loaded", "sas") + LIBRESWAN_KEYS + listOf("psk", "file")
private val LIBRESWAN_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("left", ValueType.Text(), optional = true, placeholder = "%defaultroute"),
    Argument("leftid", ValueType.Text(), optional = true),
    Argument("leftsubnet", ValueType.Text(), optional = true, placeholder = "192.168.1.0/24"),
    Argument("right", ValueType.Text(), optional = true, placeholder = "203.0.113.9"),
    Argument("rightid", ValueType.Text(), optional = true),
    Argument("rightsubnet", ValueType.Text(), optional = true, placeholder = "192.168.2.0/24"),
    Argument("keyexchange", ValueType.Enumeration(closed = true), listOf("ikev2", "ikev1"), optional = true, placeholder = "ikev2"),
    Argument("authby", ValueType.Enumeration(closed = true), listOf("secret", "rsasig", "ecdsa", "rsasig,ecdsa", "never"), optional = true),
    Argument("psk", ValueType.Secret, optional = true),
    Argument("ike", ValueType.Text(), optional = true, placeholder = "aes256-sha2;dh19"),
    Argument("esp", ValueType.Text(), optional = true, placeholder = "aes256-sha2"),
    Argument("type", ValueType.Enumeration(closed = true), listOf("tunnel", "transport", "passthrough"), optional = true, placeholder = "tunnel"),
    Argument("auto", ValueType.Enumeration(closed = true), listOf("add", "start", "ondemand", "route", "ignore"), optional = true, placeholder = "add"),
    Argument("dpddelay", ValueType.Text(), optional = true, placeholder = "30"),
)

// the files of conns, which hold no secrets, the conns pluto has, the SAs ipsec trafficstatus shows and the service of pluto
private val LIBRESWAN_STATE = $$"""for f in /etc/ipsec.conf /etc/ipsec.d/*.conf; do [ -f "$f" ] && { echo "--nh-file-- $f"; cat "$f"; }; done
echo --nh-loaded--
ipsec status 2>/dev/null | sed -n 's/^[0-9 ]*"\([^"]*\)":.*/\1/p' | sort -u
echo --nh-traffic--
ipsec trafficstatus 2>/dev/null
echo "--nh-unit-- $(systemctl is-active ipsec 2>/dev/null)"
true"""
