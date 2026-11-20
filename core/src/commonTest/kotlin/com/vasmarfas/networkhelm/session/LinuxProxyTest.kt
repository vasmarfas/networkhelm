package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val system = MenuPath.parse("proxy/system")

    // the services, config files and listening sockets of a Debian 12 with Squid running, as the overview reads them; ss as iproute2 6.1 prints it
    private val overview = "--nh-conf-- /etc/squid/squid.conf\n--nh-service-- squid active enabled\n--nh-service-- tinyproxy inactive disabled\n" +
        listOf("3proxy", "danted", "sockd", "shadowsocks-libev", "shadowsocks-libev-server@config", "sing-box", "xray").joinToString("") { "--nh-service-- $it inactive disabled\n" } +
        "--nh-sockets--\n" +
        "tcp   LISTEN 0      256          0.0.0.0:3128       0.0.0.0:*    users:((\"squid\",pid=812,fd=11))\n" +
        "tcp   LISTEN 0      256             [::]:3128          [::]:*    users:((\"squid\",pid=812,fd=12))\n" +
        "udp   UNCONN 0      0            0.0.0.0:48911      0.0.0.0:*    users:((\"squid\",pid=812,fd=9))\n" +
        "tcp   LISTEN 0      128          0.0.0.0:22         0.0.0.0:*    users:((\"sshd\",pid=301,fd=3))\n"

    private fun files(password: String) = "--nh-proxy-environment--\nPATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n" +
        "http_proxy=\"http://nh-user:$password@proxy.example.net:3128/\"\nHTTP_PROXY=\"http://nh-user:$password@proxy.example.net:3128/\"\n" +
        "no_proxy=\"localhost,127.0.0.1\"\nNO_PROXY=\"localhost,127.0.0.1\"\n\n--nh-proxy-apt--\nAcquire::http::Proxy \"http://nh-user:$password@proxy.example.net:3128/\";\n\n" +
        "--nh-proxy-dnf--\n--nh-proxy-zypper--\n--nh-proxy-docker--\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("squid\nsystemctl\napt-get\nss\n"), "id -u" to ok("0\n"), "for c in /etc/squid/squid.conf" to ok(overview),
            "m() { [ -f \"\$1\" ] || return 0; cat" to ok(files("rd-test-old")), "m() { [ -f \"\$1\" ] || return 0; sed" to ok(files("(hidden)")),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theOverviewTellsWhatIsInstalledRunningOnWhichPortsAndWhatToInstall() = runTest {
        val rows = SshHostSession(host(), device, linux = true).rows("proxy/overview").associateBy { it.id }
        val squid = rows.getValue("squid")
        assertEquals(
            listOf("Squid", "true", "active", "true", "3128/tcp, 48911/udp", "/etc/squid/squid.conf", "squid"),
            listOf("name", "installed", "state", "autostart", "ports", "config", "packages").map { squid[it] },
        )
        assertEquals(listOf("false", "inactive", "", "dante-server"), listOf("installed", "state", "ports", "packages").map { rows.getValue("dante")[it] })
        assertEquals("https://github.com/XTLS/Xray-core/releases", rows.getValue("xray")["packages"])
        assertEquals("", rows.getValue("3proxy")["packages"])
    }

    @Test
    fun installNowPutsThePackagesOfTheChosenRowsOnTheHostAtOnceAndAnswersWithWhatTheManagerPrinted() = runTest {
        val host = host("DEBIAN_FRONTEND=noninteractive apt-get" to ok("Setting up squid (5.7-2+deb12u2) ...\nSetting up dante-server (1.4.2+dfsg-7+b8) ...\n"))
        val output = SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/overview"), "install", mapOf("numbers" to "squid,dante")).toList().single()["output"]
        assertEquals("Setting up squid (5.7-2+deb12u2) ...\nSetting up dante-server (1.4.2+dfsg-7+b8) ...", output)
        val installs = host.ran.filter { it.startsWith("DEBIAN_FRONTEND=noninteractive apt-get") }
        assertEquals(1, installs.size, "${host.ran}")
        assertTrue(" install 'squid' 'dante-server'" in installs.single(), installs.single())
    }

    @Test
    fun xrayComesFromTheReleaseOfXtlsForTheMachineAfterTheUnzipTheHostLacks() = runTest {
        val release = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-arm64-v8a.zip\n/tmp/tmp.Yq2/xray.zip: OK\nXray 26.11.9 (Xray, Penetrates Everything.) 1a2b3c4 (go1.26.3 linux/arm64)\n"
        val host = host(
            "uname -m" to ok("aarch64\n"), "command -v curl" to ok("unzip\n"), "DEBIAN_FRONTEND=noninteractive apt-get" to ok("Setting up unzip (6.0-28) ...\n"), "{\nset -e" to ok(release),
        )
        val output = SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/overview"), "install", mapOf("numbers" to "xray")).toList().single()["output"].orEmpty()
        assertTrue(output.startsWith("Setting up unzip (6.0-28) ...") && output.endsWith("Xray 26.11.9 (Xray, Penetrates Everything.) 1a2b3c4 (go1.26.3 linux/arm64)"), output)
        assertTrue(host.ran.any { it.startsWith("DEBIAN_FRONTEND=noninteractive apt-get") && it.contains(" install 'unzip'") }, "${host.ran}")
        val script = host.ran.single { it.startsWith("{\nset -e") }
        listOf("releases/latest/download/Xray-linux-arm64-v8a.zip", "sha256sum -c -", "install -m 755 \"\$t/xray/xray\" /usr/local/bin/xray", "/usr/local/share/xray/", "/etc/systemd/system/xray.service")
            .forEach { assertTrue(it in script, "$it is not in\n$script") }
        assertTrue(host.ran.indexOfFirst { it.startsWith("DEBIAN_FRONTEND") } < host.ran.indexOf(script), "${host.ran}")
    }

    @Test
    fun installNowOfSingBoxTakesTheExampleInboundOfItsPackageAwayBeforeAnythingStartsIt() = runTest {
        val example = """{ "log": { "level": "info" }, "inbounds": [ { "type": "shadowsocks", "listen": "::", "listen_port": 8080, "method": "2022-blake3-aes-128-gcm", """ +
            """"password": "${SING_BOX_EXAMPLE_PASSWORDS.last()}" } ], "outbounds": [ { "type": "direct" } ] }"""
        val host = host(
            "for t in" to ok("pacman\nsystemctl\nss\nsing-box\n"), "pacman --noconfirm -S" to ok("installing sing-box...\n"),
            "PATH=\"\$PATH:/usr/local/bin\"\nc=\nfor f in /etc/sing-box/config.json" to
                ok("--nh-json-- inactive disabled /etc/sing-box/config.json\n--nh-json-version-- sing-box version 1.14.4\n--nh-json-conf--\n$example\n"),
        )
        val output = SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/overview"), "install", mapOf("numbers" to "sing-box")).toList().single()["output"]
        assertEquals("installing sing-box...", output)
        assertTrue(Json.parseToJsonElement(host.given.single()).jsonObject.getValue("inbounds").jsonArray.isEmpty(), host.given.single())
        assertTrue(host.ran.any { "sing-box check -c '/etc/sing-box/config.json'" in it } && host.ran.none { it.startsWith("systemctl restart sing-box") }, "${host.ran}")
    }

    @Test
    fun aProxyTheHostHasNoPackageOrReleaseForStopsTheRestBeforeAnythingIsInstalled() = runTest {
        val host = host("uname -m" to ok("mips\n"))
        val session = SshHostSession(host, device, linux = true)
        val overview = MenuPath.parse("proxy/overview")
        val none = assertFailsWith<DeviceException> { session.run(overview, "install", mapOf("numbers" to "squid,3proxy")).toList() }
        assertEquals("the repositories of the host have no package of 3proxy", none.message)
        val machine = assertFailsWith<DeviceException> { session.run(overview, "install", mapOf("numbers" to "sing-box")).toList() }
        assertEquals("the release of sing-box has no build for mips", machine.message)
        assertTrue(host.ran.none { it.startsWith("DEBIAN_FRONTEND") || it.startsWith("{\nset -e") }, "${host.ran}")
    }

    @Test
    fun theProxyOfTheHostComesWithItsLoginButNoPassword() = runTest {
        val row = SshHostSession(host(), device, linux = true).rows("proxy/system").single()
        assertEquals(
            listOf("http://proxy.example.net:3128/", "", "localhost,127.0.0.1", "nh-user", "", "true", "true", "false", "false", "false"),
            listOf("http-proxy", "https-proxy", "no-proxy", "user", "password", "environment", "apt", "dnf", "zypper", "docker").map { row[it] },
        )
    }

    @Test
    fun aNewPasswordGoesEncodedIntoEachPlaceAndTheUnitOfDockerDoublesItsPercents() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).set(system, "system", mapOf("password" to "rd:test@new%", "docker" to "yes"))
        val (environment, apt, docker) = host.given
        val url = "http://nh-user:rd%3Atest%40new%25@proxy.example.net:3128/"
        assertTrue("http_proxy=\"$url\"" in environment.lines() && "HTTP_PROXY=\"$url\"" in environment.lines() && "PATH=" in environment, environment)
        assertEquals("Acquire::http::Proxy \"$url\";\n", apt)
        assertEquals("[Service]\nEnvironment=\"HTTP_PROXY=${url.replace("%", "%%")}\" \"NO_PROXY=localhost,127.0.0.1\"\n", docker)
        assertTrue(host.ran.any { it.startsWith("systemctl daemon-reload") }, "${host.ran}")
        assertTrue(host.sent.none { "rd%3Atest" in it || "rd:test" in it }, "${host.sent}")
    }

    @Test
    fun aLoginInsideTheAddressIsRefusedAndAProgramTheHostLacksIsNamed() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val login = assertFailsWith<DeviceException> { session.set(system, "system", mapOf("http-proxy" to "http://nh:rd-test-inline@proxy.example.net:3128")) }
        assertEquals("the login of the proxy goes into the fields user and password, not into its address", login.message)
        val zypper = assertFailsWith<DeviceException> { session.set(system, "system", mapOf("zypper" to "yes")) }
        assertEquals("the host has no zypper to take the proxy", zypper.message)
    }

    @Test
    fun thePasswordsOfEveryProxyLeaveTheTextsOfTheHost() {
        val text = listOf(
            "cache_peer parent.example.net parent 3128 0 login=nh:rd-test-1", "BasicAuth alice rd-test-2", "upstream http nh:rd-test-3@proxy.example.net:3128",
            "proxy_password=rd-test-4", "http_proxy=\"http://nh:rd-test-5@proxy.example.net:3128/\"", "cachemgr_passwd rd-test-6 all", "    \"password\": \"rd-test-7\",", "users nh:CL:rd-test-8",
        ).joinToString("\n")
        val masked = proxyHidden(text)
        assertTrue("rd-test" !in masked, masked)
        assertTrue("BasicAuth alice (hidden)" in masked && "upstream http nh:(hidden)@proxy.example.net:3128" in masked, masked)
    }

    @Test
    fun everyPasswordOfTheProxyWindowsIsASecretTheJournalHides() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val passwords = listOf("proxy/system", "proxy/squid/user").flatMap { session.describe(MenuPath.parse(it)).arguments }.filter { "password" in it.name }
        assertEquals(2, passwords.size)
        assertTrue(passwords.all { it.sensitive() }, "$passwords")
    }
}
