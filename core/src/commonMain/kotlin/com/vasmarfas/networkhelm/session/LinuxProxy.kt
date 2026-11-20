package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.ssh.SshSession

// the proxy servers of a Linux host and the proxy the host itself goes out through
internal class LinuxProxy(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val packages: LinuxPackages,
    guard: LinuxGuard,
) {
    private val squid = LinuxProxySquid(root, tools)
    private val tinyproxy = LinuxProxyTiny(root, tools)
    private val system = LinuxProxySystem(root, tools)
    private val shadowsocks = LinuxProxyShadowsocks(ssh, root, tools)
    private val dante = LinuxProxyDante(root, tools)
    private val threeProxy = LinuxProxy3proxy(root, tools)
    private val json = LinuxProxyJson(ssh, root, tools, guard)
    private val client = LinuxProxyClient(ssh, root, tools)
    private val routing = LinuxProxyRouting(ssh, json, tools)

    val tables: Map<String, HostTable> = squid.tables + tinyproxy.tables + system.tables + shadowsocks.tables + dante.tables + threeProxy.tables + json.tables + routing.tables + client.tables +
        ("proxy/overview" to HostTable(OVERVIEW, ::overview, slow = true, commands = listOf("install"), run = { _, arguments -> listOf(Item("", mapOf("output" to install(arguments["numbers"].orEmpty())))) }))

    // what is installed and running, on which ports, with its config file, and what Install Now puts on the host: the packages of its manager
    // or the page of the release. The processes behind the ports are named to root alone, so a login sudo refuses sees the ports of its own
    // processes only
    private suspend fun overview(): List<Item> {
        val present = tools()
        val manager = MANAGERS.firstOrNull { it in present }
        val text = try {
            root.output(PROXY_OVERVIEW)
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.PermissionDenied) throw e
            ssh.exec(PROXY_OVERVIEW).output
        }
        val services = text.lines().filter { it.startsWith("--nh-service-- ") }.map { it.split(' ') }.associate { it[1] to it.drop(2) }
        val configs = text.lines().filter { it.startsWith("--nh-conf-- ") }.map { it.substringAfter(' ') }.toSet()
        val sockets = parseListening(text.substringAfter("--nh-sockets--\n", ""), "")
        return KINDS.map { (id, kind) ->
            val states = kind.services.mapNotNull { services[it] }
            val ports = sockets.filter { socket -> socket["process"].orEmpty().split(", ").any { it in kind.processes } }
                .map { "${it["port"]}/${it["protocol"].orEmpty().removeSuffix("6")}" }.distinct()
            Item(
                id,
                linkedMapOf(
                    "name" to kind.title, "installed" to "${kind.tools.any { it in present }}",
                    "state" to (states.firstOrNull { it.firstOrNull() !in IDLE }?.firstOrNull() ?: states.firstOrNull()?.firstOrNull().orEmpty()),
                    "autostart" to "${states.any { it.getOrNull(1) == "enabled" }}", "ports" to ports.joinToString(", "),
                    "config" to kind.configs.firstOrNull { it in configs }.orEmpty(), "packages" to (manager?.let { kind.packages[it] } ?: kind.release.orEmpty()),
                ),
            )
        }
    }

    // the chosen proxies at once: the packages of all of them through the manager, then the releases; one the host has neither for stops
    // the rest before anything is installed. The example inbound the package of sing-box ships goes right after
    private suspend fun install(numbers: String): String {
        val present = tools()
        val manager = MANAGERS.firstOrNull { it in present }
        fun names(kind: Kind) = manager?.let { kind.packages[it] }
        val chosen = numbers.split(',').filter { it.isNotEmpty() }.map { id -> KINDS.firstOrNull { it.first == id } ?: throw DeviceException(ErrorCategory.MissingItem, "proxy/overview has no $id") }
        val (packaged, released) = chosen.partition { names(it.second) != null }
        released.firstOrNull { it.second.release == null }?.let { throw DeviceException(ErrorCategory.Unsupported, "the repositories of the host have no package of ${it.second.title}") }
        val installed = packaged.flatMap { names(it.second).orEmpty().split(' ') }.takeIf { it.isNotEmpty() }?.let { packages.install(it) }
        val output = (listOfNotNull(installed) + released.map { (id, kind) -> release(id, kind.title) }).joinToString("\n\n")
        if (chosen.any { it.first == "sing-box" }) json.dropExample()
        return output
    }

    // the latest release of Xray or sing-box from GitHub for the machine, the way the install scripts of the projects take it: the program
    // goes to /usr/local/bin with a service of systemd or OpenRC, Xray with its geo files and checked against the digest beside its archive;
    // a config already there stays. curl or wget, and unzip for the archive of Xray, come first through the manager where the host lacks them
    private suspend fun release(id: String, title: String): String {
        val machine = ssh.exec("uname -m").output.trim()
        val build = (if (id == "xray") XRAY_BUILDS else SING_BOX_BUILDS)[machine] ?: throw DeviceException(ErrorCategory.Unsupported, "the release of $title has no build for $machine")
        val lacking = ssh.exec(LACKING + if (id == "xray") "; command -v unzip >/dev/null 2>&1 || echo unzip" else "").output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val helpers = lacking.takeIf { it.isNotEmpty() }?.let { packages.install(it) }
        return listOfNotNull(helpers, packages.run(if (id == "xray") xrayRelease(build) else singBoxRelease(build))).joinToString("\n\n")
    }

    // a proxy by the names of its programs, services, processes and config files where distributions differ, its packages by the manager
    // that installs them and, for one the repositories mostly lack, the page of its releases
    private class Kind(
        val title: String, val tools: List<String>, val services: List<String>, val processes: List<String>, val configs: List<String>, val packages: Map<String, String>,
        val release: String? = null,
    )

    private companion object {
        val OVERVIEW = listOf("name", "installed", "state", "autostart", "ports", "config", "packages")
        val MANAGERS = listOf("apt-get", "dnf", "zypper", "pacman", "apk")
        val IDLE = setOf("inactive", "unknown", "stopped")
        val KINDS = listOf(
            "squid" to Kind("Squid", listOf("squid"), listOf("squid"), listOf("squid"), listOf(SQUID_CONF), MANAGERS.associateWith { "squid" }),
            "tinyproxy" to Kind("tinyproxy", listOf("tinyproxy"), listOf("tinyproxy"), listOf("tinyproxy"), TINY_CONFS, MANAGERS.associateWith { "tinyproxy" }),
            "3proxy" to Kind("3proxy", listOf("3proxy"), listOf("3proxy"), listOf("3proxy"), listOf("/etc/3proxy/3proxy.cfg", "/etc/3proxy.cfg"), mapOf("dnf" to "3proxy", "apk" to "3proxy")),
            "dante" to Kind(
                "Dante", listOf("danted", "sockd"), listOf("danted", "sockd"), listOf("danted", "sockd"), listOf("/etc/danted.conf", "/etc/sockd.conf"),
                mapOf("apt-get" to "dante-server", "dnf" to "dante-server", "zypper" to "dante-server", "pacman" to "dante", "apk" to "dante-server"),
            ),
            "shadowsocks" to Kind(
                "shadowsocks-libev", listOf("ss-server"), listOf("shadowsocks-libev", "shadowsocks-libev-server@config"), listOf("ss-server"),
                listOf("/etc/shadowsocks-libev/config.json"), mapOf("apt-get" to "shadowsocks-libev", "pacman" to "shadowsocks-libev", "apk" to "shadowsocks-libev"),
            ),
            "sing-box" to Kind(
                "sing-box", listOf("sing-box"), listOf("sing-box"), listOf("sing-box"), listOf("/etc/sing-box/config.json"), mapOf("pacman" to "sing-box", "apk" to "sing-box"),
                "https://github.com/SagerNet/sing-box/releases",
            ),
            "xray" to Kind(
                "Xray", listOf("xray"), listOf("xray"), listOf("xray"), listOf("/usr/local/etc/xray/config.json", "/etc/xray/config.json"), emptyMap(),
                "https://github.com/XTLS/Xray-core/releases",
            ),
        )
    }
}

// the builds of the releases by uname -m
private val XRAY_BUILDS = mapOf(
    "x86_64" to "64", "aarch64" to "arm64-v8a", "armv7l" to "arm32-v7a", "armv6l" to "arm32-v6", "i686" to "32", "i386" to "32", "riscv64" to "riscv64",
    "s390x" to "s390x", "ppc64le" to "ppc64le", "loongarch64" to "loong64",
)
private val SING_BOX_BUILDS = mapOf(
    "x86_64" to "amd64", "aarch64" to "arm64", "armv7l" to "armv7", "armv6l" to "armv6", "i686" to "386", "i386" to "386", "riscv64" to "riscv64",
    "s390x" to "s390x", "ppc64le" to "ppc64le", "loongarch64" to "loong64",
)

private const val LACKING = "command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || echo curl"

private const val FETCH = $$"""g() { if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"; else wget -q -O "$2" "$1"; fi; }
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT"""

private fun xrayRelease(build: String) = $$"""{
set -e
$$FETCH
u=https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-$$build.zip
echo "$u"
g "$u" "$t/xray.zip"
g "$u.dgst" "$t/xray.dgst"
echo "$(sed -n 's/^SHA2-256= *//p' "$t/xray.dgst")  $t/xray.zip" | sha256sum -c -
unzip -q "$t/xray.zip" -d "$t/xray"
install -d /usr/local/bin /usr/local/share/xray /usr/local/etc/xray
install -m 755 "$t/xray/xray" /usr/local/bin/xray
install -m 644 "$t/xray/geoip.dat" "$t/xray/geosite.dat" /usr/local/share/xray/
[ -e /usr/local/etc/xray/config.json ] || (umask 077; echo '{}' > /usr/local/etc/xray/config.json)
$${serviceUnit("xray", "/usr/local/bin/xray", "run -config /usr/local/etc/xray/config.json")}
/usr/local/bin/xray version | head -n 1
}"""

// the version is in the names of the archives of sing-box, so it comes from the API of GitHub first
private fun singBoxRelease(build: String) = $$"""{
set -e
$$FETCH
g https://api.github.com/repos/SagerNet/sing-box/releases/latest "$t/latest.json"
v=$(sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p' "$t/latest.json" | head -n 1)
u=https://github.com/SagerNet/sing-box/releases/download/v$v/sing-box-$v-linux-$$build.tar.gz
echo "$u"
g "$u" "$t/sing-box.tar.gz"
tar -xzf "$t/sing-box.tar.gz" -C "$t"
install -d /usr/local/bin /etc/sing-box /var/lib/sing-box
install -m 755 "$(find "$t" -type f -name sing-box | head -n 1)" /usr/local/bin/sing-box
[ -e /etc/sing-box/config.json ] || (umask 077; echo '{}' > /etc/sing-box/config.json)
$${serviceUnit("sing-box", "/usr/local/bin/sing-box", "-D /var/lib/sing-box -c /etc/sing-box/config.json run")}
/usr/local/bin/sing-box version | head -n 1
}"""

// the service of a proxy the app installs, of a release or of a client, under systemd, or OpenRC without it: the program runs as root, which
// owns its config of mode 600, with no more capabilities than a proxy needs; one running already starts again with the new program
internal fun serviceUnit(name: String, program: String, arguments: String) = """if command -v systemctl >/dev/null 2>&1; then
cat > /etc/systemd/system/$name.service <<'EOF'
[Unit]
Description=$name
After=network-online.target nss-lookup.target
Wants=network-online.target

[Service]
ExecStart=$program $arguments
Restart=on-failure
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
NoNewPrivileges=true
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl try-restart $name
else
cat > /etc/init.d/$name <<'EOF'
#!/sbin/openrc-run
command="$program"
command_args="$arguments"
command_background=true
pidfile="/run/$name.pid"
depend() {
    need net
}
EOF
chmod 755 /etc/init.d/$name
if rc-service $name status >/dev/null 2>&1; then rc-service $name restart; fi
fi"""

// a service of a proxy under systemd or OpenRC; one not active 3 s after its start failed, and the end of its journal says why
internal class ProxyService(private val root: Root, private val tools: suspend () -> Set<String>, private val name: String) {
    suspend fun bring(command: String) {
        val systemd = "systemctl" in tools()
        if (command == "down") root.proxied(if (systemd) "systemctl stop $name" else "rc-service $name stop") else start(if (systemd) "systemctl start $name" else "rc-service $name start")
    }

    suspend fun restart() = start(if ("systemctl" in tools()) "systemctl restart $name" else "rc-service $name restart")

    // a file the service reads at its start, through the check it has: a running service starts again with it, a stopped one too when
    // asked to start, and when it does not come up the old file comes back and the service starts again with it or stays stopped
    suspend fun rewrite(path: String, before: String, after: String, running: Boolean, mode: String, check: String = "true", start: Boolean = false) {
        root.write(path, after, check, mode = mode)
        if (!running && !start) return
        try {
            restart()
        } catch (e: DeviceException) {
            root.write(path, before, "true", mode = mode)
            try {
                if (running) restart() else bring("down")
            } catch (again: DeviceException) {
                e.addSuppressed(again)
            }
            throw e
        }
    }

    suspend fun autostart(on: Boolean) {
        root.proxied(if ("systemctl" in tools()) "systemctl ${if (on) "enable" else "disable"} $name" else "rc-update ${if (on) "add" else "del"} $name default")
    }

    private suspend fun start(line: String) {
        val systemd = "systemctl" in tools()
        val active = if (systemd) "systemctl is-active -q $name" else "rc-service $name status >/dev/null 2>&1"
        val journal = if (systemd) "journalctl -u $name -n 12 --no-pager -o cat" else "tail -n 12 /var/log/messages"
        val result = root.exec("$line >/dev/null 2>&1; sleep 3; if $active; then exit 0; fi; $journal 2>/dev/null; exit 1")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, "$name did not start: ${proxyHidden(result.output.trim()).ifEmpty { "exit ${result.exitStatus}" }}")
    }
}

internal fun running(state: String) = state == "active" || state == "started"

// a command as root on the files of proxies: what it says when it fails comes with the passwords in it masked
internal suspend fun Root.proxied(command: String, input: String? = null): String {
    val result = if (input == null) exec(command) else exec(command, input)
    if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, proxyHidden(result.error.trim()).ifEmpty { "exit ${result.exitStatus}" })
    return result.output
}

// a text of the host with the passwords of proxies in it masked, and any key as hidden() masks it
internal fun proxyHidden(text: String) = PROXY_SECRETS.fold(hidden(text)) { masked, (pattern, replacement) -> pattern.replace(masked, replacement) }

private val PROXY_SECRETS = listOf(
    Regex("(login=)\\S+") to "\$1(hidden)",
    Regex("(://[^\\s:/@\"]*:)[^\\s@\"]*@") to "\$1(hidden)@",
    Regex("(BasicAuth[ \\t]+\\S+)[ \\t].*", RegexOption.IGNORE_CASE) to "\$1 (hidden)",
    Regex("(Upstream[ \\t].*[ \\t][^\\s:@]*:)[^\\s@]*@", RegexOption.IGNORE_CASE) to "\$1(hidden)@",
    Regex("(proxy_password\\s*=).*") to "\$1 (hidden)",
    Regex("(cachemgr_passwd\\s+)\\S+") to "\$1(hidden)",
    Regex("(:[A-Z]{2}:)[^\\s\"]+") to "\$1(hidden)",
    Regex("(\"(?:password|uuid|id|private_key|privateKey|psk)\"\\s*:\\s*\")[^\"]*\"") to "\$1(hidden)\"",
)

// the same masks on the host, for what a check prints
internal const val PROXY_MASK = """sed -e 's/\(login=\)[^[:space:]]*/\1(hidden)/g' -e 's#\(://[^:/@[:space:]"]*:\)[^@[:space:]"]*@#\1(hidden)@#g' """ +
    """-e 's/\([Bb][Aa][Ss][Ii][Cc][Aa][Uu][Tt][Hh][[:space:]][[:space:]]*[^[:space:]][^[:space:]]*\)[[:space:]].*/\1 (hidden)/' """ +
    """-e 's/^\([[:space:]]*[Uu][Pp][Ss][Tt][Rr][Ee][Aa][Mm][[:space:]].*[[:space:]][^[:space:]:@]*:\)[^@[:space:]]*@/\1(hidden)@/' """ +
    """-e 's/\(proxy_password[[:space:]]*=\).*/\1 (hidden)/' -e 's/\(cachemgr_passwd[[:space:]][[:space:]]*\)[^[:space:]]*/\1(hidden)/' """ +
    """-e 's/\("password"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g' -e 's/\("uuid"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g' -e 's/\("id"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g' -e 's/\("private_key"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g' -e 's/\("privateKey"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g' -e 's/\("psk"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/g'"""

// the state of a service and whether it starts at boot, as systemd or OpenRC tell them: active enabled, stopped disabled
internal const val SERVICE_STATE = $$"""s() { x=$(systemctl is-active "$1" 2>/dev/null); [ -n "$x" ] || x=$(rc-service "$1" status 2>/dev/null | sed -n 's/.*status: *//p'); y=$(systemctl is-enabled "$1" 2>/dev/null); [ ! -e "/etc/runlevels/default/$1" ] || y=enabled; echo "${x:-unknown} ${y:-disabled}"; }"""

internal const val SQUID_CONF = "/etc/squid/squid.conf"

internal val TINY_CONFS = listOf("/etc/tinyproxy/tinyproxy.conf", "/etc/tinyproxy.conf")

private val PROXY_OVERVIEW =
    $$"""for c in $$SQUID_CONF $${TINY_CONFS.joinToString(" ")} /etc/3proxy/3proxy.cfg /etc/3proxy.cfg /etc/danted.conf /etc/sockd.conf /etc/shadowsocks-libev/config.json /etc/sing-box/config.json /usr/local/etc/xray/config.json /etc/xray/config.json; do [ -f "$c" ] && echo "--nh-conf-- $c"; done
$$SERVICE_STATE
for n in squid tinyproxy 3proxy danted sockd shadowsocks-libev shadowsocks-libev-server@config sing-box xray; do echo "--nh-service-- $n $(s "$n")"; done
echo --nh-sockets--
ss -H -tulnp 2>/dev/null
true"""
