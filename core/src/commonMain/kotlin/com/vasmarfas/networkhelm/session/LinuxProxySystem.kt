package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.transport.urlEncoded

// the proxy the host itself goes out through: the variables of /etc/environment, which pam_env gives each login and pacman and apk take
// from it, a file of apt of its own, the [main] of dnf.conf, /etc/sysconfig/proxy of zypper and a drop-in of the unit of Docker. The
// login of the proxy stands inside the address in each of them, dnf apart, which has keys of its own; the read masks the password
internal class LinuxProxySystem(private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = mapOf(
        "proxy/system" to HostTable(
            SYSTEM_FIELDS, { listOf(parseSystemProxy(root.output(systemState(full = false))).row()) }, settings = true, slow = true,
            arguments = { SYSTEM_ARGUMENTS }, set = ::set,
        ),
    )

    // each place the proxy goes to is written only when its text changes, and the unit of Docker is reloaded and Docker restarted then
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val present = tools()
        val old = parseSystemProxy(root.output(systemState(full = true)))
        val was = old.proxy()
        val user = changes["user"]?.trim() ?: was.user
        val proxy = SystemProxy(
            changes["http-proxy"]?.trim() ?: was.http, changes["https-proxy"]?.trim() ?: was.https, changes["no-proxy"]?.replace(" ", "") ?: was.no, user,
            changes["password"]?.ifEmpty { null } ?: was.password.takeIf { user == was.user }.orEmpty(),
        )
        proxy.check()
        for ((target, file) in TARGET_FILES) {
            val on = changes[target]?.let { it in ValueType.Flag.on } ?: old.has(target)
            val tool = TARGET_TOOLS[target]
            if (on && tool != null && tool !in present) throw DeviceException(ErrorCategory.Unsupported, "the host has no $tool to take the proxy")
            val before = old.sections[target].orEmpty()
            val after = when (target) {
                "environment" -> environmentText(before, proxy, on)
                "apt" -> if (on) aptText(proxy) else ""
                "dnf" -> dnfText(before, proxy, on)
                "zypper" -> if (on || before.isNotBlank()) zypperText(before, proxy, on) else ""
                else -> if (on) dockerText(proxy) else ""
            }
            if (after.trimEnd() == before.trimEnd()) continue
            when {
                after.isNotBlank() -> root.write(file, after, if (target == "apt") APT_CHECK else "true", mode = if (target == "docker") "600" else "644")
                target == "apt" || target == "docker" -> root.proxied("rm -f $file")
                else -> root.write(file, after, "true", mode = "644")
            }
            if (target == "docker") root.proxied("systemctl daemon-reload && { ! systemctl is-active -q docker || systemctl restart docker; }")
        }
    }
}

// the proxy without its login in the addresses; the login goes into each address as user:password@, percent-encoded
internal class SystemProxy(val http: String, val https: String, val no: String, val user: String, val password: String) {
    fun url(address: String): String? = address.ifEmpty { null }?.let {
        if (user.isEmpty()) it else it.replaceFirst("://", "://${urlEncoded(user)}${if (password.isEmpty()) "" else ":${urlEncoded(password)}"}@")
    }

    fun check() {
        for ((field, address) in listOf("http-proxy" to http, "https-proxy" to https)) {
            if ('@' in address) throw DeviceException(ErrorCategory.ArgumentValue, "the login of the proxy goes into the fields user and password, not into its address")
            if (address.isNotEmpty() && !PROXY_URL.matches(address)) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $address is not a value it takes")
        }
        if (!NO_PROXY.matches(no)) throw DeviceException(ErrorCategory.ArgumentValue, "no-proxy: $no is not a value it takes")
        if (user.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
    }
}

// the files the proxy of the host stands in, each by itself; read for the window their passwords come masked
internal class SystemProxyState(val sections: Map<String, String>) {
    private fun pairs(target: String, pattern: Regex) = sections[target].orEmpty().lines().filterNot { it.trim().startsWith("#") }
        .flatMap { pattern.findAll(it).toList() }.associate { it.groupValues[1] to it.groupValues[2] }

    private fun environment() = pairs("environment", ENV_LINE).mapKeys { it.key.lowercase() }

    private fun apt() = pairs("apt", APT_LINE)

    private fun dnf(): Map<String, String> {
        val lines = sections["dnf"].orEmpty().lines()
        val start = lines.indexOfFirst { it.trim() == "[main]" }
        if (start < 0) return emptyMap()
        return lines.drop(start + 1).takeWhile { !it.trim().startsWith("[") }.filter { '=' in it && !it.trim().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    private fun zypper() = pairs("zypper", SYSCONFIG_LINE)

    private fun docker() = pairs("docker", DOCKER_PAIR).mapKeys { it.key.lowercase() }

    fun has(target: String) = when (target) {
        "environment" -> listOf("http_proxy", "https_proxy").any { environment()[it].orEmpty().isNotEmpty() }
        "apt" -> apt().isNotEmpty()
        "dnf" -> dnf()["proxy"].orEmpty().isNotEmpty()
        "zypper" -> zypper()["PROXY_ENABLED"] == "yes"
        else -> listOf("http_proxy", "https_proxy").any { docker()[it].orEmpty().isNotEmpty() }
    }

    // the first place that has an address gives it, as the variables of a login would; the login comes with the address it stood in
    fun proxy(): SystemProxy {
        val zypper = zypper().takeIf { has("zypper") }.orEmpty()
        val dnf = dnf()
        val http = listOfNotNull(environment()["http_proxy"], apt()["http"], dnf["proxy"], zypper["HTTP_PROXY"], docker()["http_proxy"]).firstOrNull { it.isNotEmpty() }.orEmpty()
        val https = listOfNotNull(environment()["https_proxy"], apt()["https"], zypper["HTTPS_PROXY"], docker()["https_proxy"]).firstOrNull { it.isNotEmpty() }.orEmpty()
        val no = listOfNotNull(environment()["no_proxy"], zypper["NO_PROXY"], docker()["no_proxy"]).firstOrNull { it.isNotEmpty() }.orEmpty()
        val login = listOf(http, https).map { URL_LOGIN.find(it)?.groupValues?.get(2) }.firstOrNull { it != null }
        val user = login?.substringBefore(':')?.let(::urlDecoded) ?: dnf["proxy_username"].orEmpty()
        val password = login?.substringAfter(':', "")?.let(::urlDecoded) ?: dnf["proxy_password"].orEmpty()
        return SystemProxy(URL_LOGIN.replace(http, "\$1"), URL_LOGIN.replace(https, "\$1"), no, user, password)
    }

    fun row(): Item {
        val proxy = proxy()
        return Item(
            "system",
            linkedMapOf(
                "http-proxy" to proxy.http, "https-proxy" to proxy.https, "no-proxy" to proxy.no, "user" to proxy.user, "password" to "",
            ) + TARGET_FILES.keys.associateWith { "${has(it)}" },
        )
    }
}

internal fun parseSystemProxy(text: String): SystemProxyState {
    val sections = mutableMapOf<String, String>()
    var section: String? = null
    for (line in text.lines()) {
        if (line.startsWith("--nh-proxy-") && line.endsWith("--")) {
            section = line.removePrefix("--nh-proxy-").removeSuffix("--")
            sections[section] = ""
        } else if (section != null) {
            sections[section] = sections.getValue(section) + line + "\n"
        }
    }
    return SystemProxyState(sections)
}

// the variables in both cases, as programs read one or the other
private fun environmentText(before: String, proxy: SystemProxy, on: Boolean): String {
    val kept = before.lines().filterNot { line -> ENV_LINE.matchEntire(line.trim())?.groupValues?.get(1)?.lowercase() in ENV_KEYS }.dropLastWhile { it.isBlank() }
    val values = if (!on) emptyList() else listOfNotNull(
        proxy.url(proxy.http)?.let { "http_proxy" to it }, proxy.url(proxy.https)?.let { "https_proxy" to it }, proxy.no.ifEmpty { null }?.let { "no_proxy" to it },
    )
    return (kept + values.flatMap { (name, value) -> listOf("$name=\"$value\"", "${name.uppercase()}=\"$value\"") }).joinToString("\n")
}

private fun aptText(proxy: SystemProxy) =
    listOfNotNull(proxy.url(proxy.http)?.let { "Acquire::http::Proxy \"$it\";" }, proxy.url(proxy.https)?.let { "Acquire::https::Proxy \"$it\";" }).joinToString("\n")

// dnf takes one proxy for every scheme, with the login in keys of its own
private fun dnfText(before: String, proxy: SystemProxy, on: Boolean): String {
    val lines = before.lines().dropLastWhile { it.isBlank() }.toMutableList()
    var start = lines.indexOfFirst { it.trim() == "[main]" }
    if (start < 0) {
        if (!on) return before
        lines += "[main]"
        start = lines.lastIndex
    }
    val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
    (end - 1 downTo start + 1).filter { lines[it].substringBefore('=').trim() in DNF_KEYS }.forEach { lines.removeAt(it) }
    val address = proxy.http.ifEmpty { proxy.https }
    if (on && address.isNotEmpty()) {
        lines.addAll(start + 1, listOfNotNull("proxy=$address", proxy.user.ifEmpty { null }?.let { "proxy_username=$it" }, proxy.password.ifEmpty { null }?.let { "proxy_password=$it" }))
    }
    return lines.joinToString("\n")
}

private fun zypperText(before: String, proxy: SystemProxy, on: Boolean): String {
    val values = mapOf(
        "PROXY_ENABLED" to if (on) "yes" else "no", "HTTP_PROXY" to (proxy.url(proxy.http).takeIf { on }.orEmpty()),
        "HTTPS_PROXY" to (proxy.url(proxy.https).takeIf { on }.orEmpty()), "NO_PROXY" to proxy.no,
    )
    val lines = before.lines().dropLastWhile { it.isBlank() }.toMutableList()
    for ((key, value) in values) {
        val line = "$key=\"$value\""
        val index = lines.indexOfFirst { SYSCONFIG_LINE.matchEntire(it.trim())?.groupValues?.get(1) == key }
        if (index >= 0) lines[index] = line else lines += line
    }
    return lines.joinToString("\n")
}

// a percent sign is a specifier in a unit of systemd and goes doubled
private fun dockerText(proxy: SystemProxy): String {
    val pairs = listOfNotNull(proxy.url(proxy.http)?.let { "HTTP_PROXY=$it" }, proxy.url(proxy.https)?.let { "HTTPS_PROXY=$it" }, proxy.no.ifEmpty { null }?.let { "NO_PROXY=$it" })
    return if (pairs.isEmpty()) "" else "[Service]\nEnvironment=${pairs.joinToString(" ") { "\"${it.replace("%", "%%")}\"" }}"
}

internal fun urlDecoded(text: String) = PERCENT.findAll(text).flatMap { match ->
    match.groupValues[1].ifEmpty { null }?.let { sequenceOf(it.toInt(16).toByte()) } ?: match.value.encodeToByteArray().asSequence()
}.toList().toByteArray().decodeToString()

private val ENV_KEYS = setOf("http_proxy", "https_proxy", "no_proxy")
private val DNF_KEYS = setOf("proxy", "proxy_username", "proxy_password")
private val ENV_LINE = Regex("(?:export\\s+)?([A-Za-z_]+)\\s*=\\s*\"?([^\"\\n]*)\"?")
private val APT_LINE = Regex("Acquire::(https?)::Proxy\\s+\"([^\"]*)\"")
private val SYSCONFIG_LINE = Regex("([A-Z_]+)=\"?([^\"\\n]*)\"?")
private val DOCKER_PAIR = Regex("([A-Za-z_]+_PROXY|[a-z_]+_proxy)=([^\"\\s]*)")
private val URL_LOGIN = Regex("^([A-Za-z0-9+.-]+://)([^/@]*)@")
private val PROXY_URL = Regex("[A-Za-z0-9+.-]+://[^\\s\"'`$\\\\@]+")
private val NO_PROXY = Regex("[^\\s\"'`$\\\\]*")
private val PERCENT = Regex("%([0-9A-Fa-f]{2})|[^%]+|%")

private val TARGET_FILES = linkedMapOf(
    "environment" to "/etc/environment", "apt" to "/etc/apt/apt.conf.d/95networkhelm-proxy", "dnf" to "/etc/dnf/dnf.conf", "zypper" to "/etc/sysconfig/proxy",
    "docker" to "/etc/systemd/system/docker.service.d/http-proxy.conf",
)
private val TARGET_TOOLS = mapOf("apt" to "apt-get", "dnf" to "dnf", "zypper" to "zypper", "docker" to "systemctl")
private val SYSTEM_FIELDS = listOf("http-proxy", "https-proxy", "no-proxy", "user", "password") + TARGET_FILES.keys
private val SYSTEM_ARGUMENTS = listOf(
    Argument("http-proxy", ValueType.Text(), optional = true, placeholder = "http://proxy.example.net:3128"),
    Argument("https-proxy", ValueType.Text(), optional = true, placeholder = "http://proxy.example.net:3128"),
    Argument("no-proxy", ValueType.Text(), optional = true, placeholder = "localhost,127.0.0.1,.example.net"),
    Argument("user", ValueType.Text(), optional = true),
    Argument("password", ValueType.Secret, optional = true),
) + TARGET_FILES.keys.map { Argument(it, ValueType.YesNo, optional = true) }

// apt-config reads every file of apt.conf.d and fails on one it cannot parse; what it prints comes back masked
private val APT_CHECK = $$"""o=$(apt-config dump 2>&1 >/dev/null); r=$?; printf '%s\n' "$o" | $$PROXY_MASK; exit $r"""

// each file under its own mark, masked for the window or whole for a change
private fun systemState(full: Boolean) = $$"""m() { [ -f "$1" ] || return 0; $${if (full) "cat" else PROXY_MASK} "$1"; echo; }
""" + TARGET_FILES.entries.joinToString("\n") { (target, file) -> "echo --nh-proxy-$target--; m $file" } + "\ntrue"
