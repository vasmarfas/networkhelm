package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxAdminTest {

    // answers a command, or the command sudo runs, by how it starts; records what ran as root and what went to sudo -S
    private class Host(private val answers: List<Pair<String, ExecResult>>) : SshSession {
        val ran = mutableListOf<String>()
        val rooted = mutableListOf<String>()
        val given = mutableListOf<String>()

        override suspend fun exec(command: String): ExecResult {
            val sudo = listOf("sudo -n sh -c ", "sudo -S -p '' sh -c ").firstOrNull { command.startsWith(it) }
            val inner = if (sudo == null) command else unquote(command.removePrefix(sudo))
            ran += inner
            if (sudo != null) rooted += inner
            return answers.firstOrNull { inner.startsWith(it.first) }?.second ?: ExecResult("", "not found", 127)
        }

        override suspend fun exec(command: String, input: String): ExecResult {
            given += input
            return exec(command)
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit

        private fun unquote(text: String) = text.removePrefix("'").removeSuffix("'").replace("'\\''", "'")
    }

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    @Test
    fun theWordsOfALineAreSplitAsShSplitsThem() {
        assertEquals(listOf("-p", "tcp", "-m", "comment", "--comment", "rd ssh", "-j", "ACCEPT"), words("""-p tcp -m comment --comment "rd ssh" -j ACCEPT"""))
        assertEquals(listOf("a b", "c'd", ""), words("""'a b' c\'d """""))
        assertFailsWith<DeviceException> { words("--comment \"open") }
    }

    @Test
    fun ufwRulesAreNumberedAsUfwDeleteNumbersThemOnOrOff() {
        val rules = parseUfwTuples(
            "*filter\n### tuple ### allow tcp 80 0.0.0.0/0 1000:2000 192.0.2.0/24 in_eth0 comment=72642062\n### tuple ### allow tcp 22 0.0.0.0/0 any 0.0.0.0/0 OpenSSH - in\n" +
                "### tuple ### route:allow any any 0.0.0.0/0 any 0.0.0.0/0 in_eth0!out_eth1\n",
            "### tuple ### deny any 25 ::/0 any ::/0 out\n",
        )
        assertEquals(listOf("1", "2", "3", "4"), rules.map { it.id })
        assertEquals(
            listOf("allow", "in", "tcp", "", "80", "192.0.2.0/24", "1000:2000", "eth0", "rd b", "false"),
            listOf("action", "direction", "protocol", "to", "to-port", "from", "from-port", "interface", "comment", "v6").map { rules[0][it] },
        )
        assertEquals("OpenSSH", rules[1]["to-port"])
        assertEquals(listOf("allow", "route", "in eth0, out eth1"), listOf("action", "direction", "interface").map { rules[2][it] })
        assertEquals(listOf("deny", "out", "25", "true"), listOf("action", "direction", "to-port", "v6").map { rules[3][it] })
    }

    @Test
    fun firewalldZonesGiveOneRowAnEntryRichRulesIncluded() {
        val zones = parseFirewalldZones(
            "block\n  target: %%REJECT%%\n  services: \n  rich rules: \n\npublic (default, active)\n  target: default\n  interfaces: eth0\n  services: dhcpv6-client ssh\n" +
                "  ports: 8080/tcp\n  masquerade: no\n  rich rules: \n\trule family=\"ipv4\" source address=\"192.0.2.0/24\" drop\n",
        )
        assertEquals(listOf("block", "public"), zones.map { it.name })
        val public = zones[1]
        assertTrue(public.default && public.active && !zones[0].active)
        assertEquals(
            listOf("interface" to "eth0", "service" to "dhcpv6-client", "service" to "ssh", "port" to "8080/tcp", "rich-rule" to "rule family=\"ipv4\" source address=\"192.0.2.0/24\" drop"),
            public.entries,
        )
    }

    @Test
    fun theNftRulesetGivesChainsAndRulesWithTheirHandles() {
        val (chains, rules) = parseNftRuleset(
            "table inet filter { # handle 1\n\tchain input { # handle 1\n\t\ttype filter hook input priority filter; policy accept;\n" +
                "\t\ttcp dport 22 counter packets 20 bytes 4682 accept comment \"rd ssh\" # handle 4\n\t\tip saddr 192.0.2.0/24 drop # handle 5\n\t}\n\n" +
                "\tset blocked { # handle 6\n\t\ttype ipv4_addr\n\t\telements = { 192.0.2.7 } # handle 7\n\t}\n}\n" +
                "table ip rdnat { # handle 2\n\tchain post { # handle 1\n\t\ttype nat hook postrouting priority srcnat; policy accept;\n\t\toifname \"eth0\" masquerade # handle 4\n\t}\n}\n",
        )
        assertEquals(listOf("inet/filter/input", "ip/rdnat/post"), chains.map { it.id })
        assertEquals(listOf("filter", "input", "filter", "accept", "2"), listOf("type", "hook", "priority", "policy", "rules").map { chains[0][it] })
        assertEquals(listOf("inet/filter/input/4", "inet/filter/input/5", "ip/rdnat/post/4"), rules.map { it.id })
        assertEquals(listOf("tcp dport 22 counter accept", "rd ssh", "20", "4682"), listOf("rule", "comment", "packets", "bytes").map { rules[0][it] })
    }

    @Test
    fun iptablesSaveGivesRulesByTheirPlaceInTheChain() {
        val (chains, rules) = parseIptablesSave(
            "# Generated by iptables-save v1.8.13 (nf_tables)\n*filter\n:INPUT ACCEPT [0:0]\n:RDTEST - [0:0]\n" +
                "[22:4854] -A INPUT -p tcp -m tcp --dport 22 -m comment --comment \"rd ssh\" -j ACCEPT\n[0:0] -A INPUT -j RDTEST\n[0:0] -A RDTEST -s 192.0.2.0/24 -j DROP\nCOMMIT\n" +
                "*nat\n:POSTROUTING ACCEPT [0:0]\n[1:108] -A POSTROUTING -o eth0 -j MASQUERADE\nCOMMIT\n",
        )
        assertEquals(listOf("filter/INPUT", "filter/RDTEST", "nat/POSTROUTING"), chains.map { it.id })
        assertEquals(listOf("ACCEPT", "2"), listOf("policy", "rules").map { chains[0][it] })
        assertEquals("", chains[1]["policy"])
        assertEquals(listOf("filter/INPUT/1", "filter/INPUT/2", "filter/RDTEST/1", "nat/POSTROUTING/1"), rules.map { it.id })
        assertEquals(listOf("ACCEPT", "rd ssh", "22", "4854"), listOf("target", "comment", "packets", "bytes").map { rules[0][it] })
        assertEquals("-o eth0 -j MASQUERADE", rules[3]["rule"])
    }

    @Test
    fun thePackagesOfEachManagerAreReadTheSameWay() {
        assertEquals(
            listOf("bash", "5.2.15-2+b7", "amd64", "7335936", "GNU Bourne Again SHell"),
            parseDpkg("ii \tbash\t5.2.15-2+b7\tamd64\t7164\tGNU Bourne Again SHell\nrc \told\t1\tall\t1\tremoved\n").single().let { row -> listOf("name", "version", "architecture", "size", "description").map { row[it] } },
        )
        assertEquals(listOf("libgcc"), parseRpm("libgcc\t16.1.1-2.fc44\tx86_64\t277148\tGCC support\ngpg-pubkey\t1-1\t(none)\t0\tkey\n").map { it.id })
        assertEquals(listOf("acl", "2.4.0-1", "1258291"), parsePacman("Name            : acl\nVersion         : 2.4.0-1\nInstalled Size  : 1.20 MiB\n\nName : b\n").first().let { listOf(it.id, it["version"], it["size"]) })
        assertEquals(listOf("alpine-baselayout-data", "3.7.2-r1"), parseApk("alpine-baselayout-data-3.7.2-r1 x86_64 {alpine-baselayout} (GPL-2.0-only) [installed]\n").single().let { listOf(it.id, it["version"]) })

        assertEquals(
            listOf("apt", "2.6.1", "2.6.2", "oldstable-security"),
            parseAptUpgradable("Listing...\napt/oldstable-security 2.6.2 amd64 [upgradable from: 2.6.1]\n").single().let { row -> listOf(row.id, row["version"], row["new-version"], row["repository"]) },
        )
        assertEquals(
            listOf("NetworkManager" to "1:1.54.4-3.el9", "coreutils" to "9.10-5.fc44"),
            parseDnfUpdates("\nUpgrades\nNetworkManager.x86_64   1:1.54.4-3.el9   baseos\ncoreutils.x86_64  9.10-5.fc44  updates\nObsoleting Packages\nold.x86_64 1 updates\n").map { it.id to it["new-version"] },
        )
        assertEquals(
            listOf("aaa_base", "84.87+git1", "84.87+git2", "repo-oss (16.0)"),
            parseZypperUpdates("S  | Repository      | Name     | Current Version | Available Version | Arch\n---+-\nv  | repo-oss (16.0) | aaa_base | 84.87+git1 | 84.87+git2 | x86_64\n")
                .single().let { row -> listOf(row.id, row["version"], row["new-version"], row["repository"]) },
        )
        assertEquals(listOf("zlib", "1.3.2-r1", "1.4-r0"), parseApkUpdates("zlib-1.4-r0 x86_64 {zlib} (Zlib) [upgradable from: zlib-1.3.2-r1]\n").single().let { listOf(it.id, it["version"], it["new-version"]) })
        assertEquals(listOf("linux" to "6.17.8.arch1-1"), parsePacmanUpdates("linux 6.17.7.arch1-1 -> 6.17.8.arch1-1\n").map { it.id to it["new-version"] })
    }

    @Test
    fun aChangeGoesAsRootThroughSudoWithoutAPasswordOrWithTheOneOfTheLogin() = runTest {
        val free = Host(listOf("id -u" to ok("1000\n"), "sudo -n true" to ok(), "ip -j link show lo" to ok("[]"), "ip -j -d -s link show" to ok("""[{"ifindex":2,"ifname":"eth0","flags":["UP"],"mtu":1500}]"""), "ip link set" to ok()))
        SshHostSession(free, device, linux = true).set(MenuPath.parse("interface"), "2", mapOf("mtu" to "1400", "comment" to "rd test"))
        assertEquals(listOf("ip link set dev 'eth0' mtu 1400 && ip link set dev 'eth0' alias 'rd test'"), free.rooted)

        val asking = Host(listOf("id -u" to ok("1000\n"), "sudo -n true" to ExecResult("", "sudo: a password is required", 1), "sudo -S -p '' true" to ok(), "ip -4 addr add" to ok()))
        SshHostSession(asking, device, linux = true, password = "rd-test-pw").add(MenuPath.parse("ip/address"), mapOf("address" to "192.0.2.5/24", "interface" to "eth0"))
        assertEquals(listOf("ip -4 addr add 192.0.2.5/24 dev 'eth0'"), asking.rooted)
        assertEquals(listOf("rd-test-pw\n", "rd-test-pw\n"), asking.given)

        val keyed = Host(listOf("id -u" to ok("1000\n"), "sudo -n true" to ExecResult("", "sudo: a password is required", 1)))
        val refused = assertFailsWith<DeviceException> { SshHostSession(keyed, device, linux = true).add(MenuPath.parse("ip/address"), mapOf("address" to "192.0.2.5/24", "interface" to "eth0")) }
        assertEquals(ErrorCategory.PermissionDenied, refused.category)
    }

    @Test
    fun theLinesOfSyslogKeepTheirTimeLevelAndMessageInEachForm() {
        assertEquals(
            mapOf("time" to "Nov 17 11:41:29", "topics" to "authpriv.info", "message" to "sshd[1]: Accepted"),
            syslogLine("Nov 17 11:41:29 nh-alpine324 authpriv.info sshd[1]: Accepted"),
        )
        assertEquals(
            mapOf("time" to "2026-11-17 11:41:29", "topics" to "", "message" to "sshd[1]: Accepted"),
            syslogLine("2026-11-17T11:41:29.123456+03:00 nh-ubuntu2404 sshd[1]: Accepted"),
        )
        assertEquals(
            mapOf("time" to "Fri Oct 23 17:41:46 2026", "topics" to "authpriv.info", "message" to "dropbear[2657]: Child connection"),
            syslogLine("Fri Oct 23 17:41:46 2026 authpriv.info dropbear[2657]: Child connection"),
        )
    }

    @Test
    fun aLoginOutsideTheGroupsOfTheJournalReadsTheLogsThroughSudoEveryHalfMinute() = runTest {
        val journal = "+0300\n--rd-section--\n" +
            """{"__CURSOR":"c1","__REALTIME_TIMESTAMP":"1792766506000000","PRIORITY":"6","SYSLOG_FACILITY":"4","SYSLOG_IDENTIFIER":"sshd","MESSAGE":"Accepted password for rd-test"}""" + "\n"
        val host = Host(
            listOf(
                "for t in" to ok("journalctl\n"),
                "journalctl -q -n 1 -o cat --no-pager _PID=1" to ok(""),
                "id -u" to ok("1000\n"),
                "sudo -n true" to ok(),
                "date +%z" to ok(journal),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val auth = session.rows("log/auth").single()
        assertEquals(listOf("auth.info", "sshd: Accepted password for rd-test"), listOf("topics", "message").map { auth[it] })
        assertEquals(listOf("date +%z; echo --rd-section--; journalctl -o json -n 500 --no-pager SYSLOG_FACILITY=4 SYSLOG_FACILITY=10"), host.rooted)
        session.rows("log/auth")
        assertEquals(1, host.rooted.size, "a log read through sudo is kept for half a minute")
    }

    @Test
    fun onlyAnAddressAskedToBeKeptGoesToTheNetworkConfigurationAndARemovalSpendsNoErrorOnOneThatIsNotThere() = runTest {
        val host = Host(listOf("id -u" to ok("0\n"), "ip -4 addr add" to ok(), "ip addr del" to ok(), "echo \$SSH_CONNECTION" to ok("198.51.100.7 50000 192.0.2.1 22\n")))
        val session = SshHostSession(host, device, linux = true)
        val addresses = MenuPath.parse("ip/address")
        session.add(addresses, mapOf("address" to "192.0.2.5/24", "interface" to "eth0"))
        assertEquals(listOf("id -u", "ip -4 addr add 192.0.2.5/24 dev 'eth0'"), host.ran)

        val kept = assertFailsWith<DeviceException> { session.add(addresses, mapOf("address" to "192.0.2.6/24", "interface" to "eth0", "persist" to "yes")) }
        assertEquals(ErrorCategory.Unsupported, kept.category)
        assertTrue("until a reboot" in kept.message.orEmpty(), kept.message)
        assertTrue("ip -4 addr add 192.0.2.6/24 dev 'eth0'" in host.ran)

        session.remove(addresses, listOf("eth0/192.0.2.5/24"))
        assertTrue("ip addr del 192.0.2.5/24 dev 'eth0'" in host.ran)
    }

    @Test
    fun theInterfaceOfTheSessionIsNotTurnedOff() = runTest {
        val host = Host(
            listOf(
                "id -u" to ok("0\n"), "echo \$SSH_CONNECTION" to ok("192.0.2.2 51956 192.0.2.50 22\n"), "ip -j link show lo" to ok("[]"),
                "ip -j -d -s link show" to ok("""[{"ifindex":2,"ifname":"eth0","flags":["UP"],"mtu":1500}]"""),
                "ip -j -4 addr show" to ok("""[{"ifname":"eth0","addr_info":[{"local":"192.0.2.50","prefixlen":24}]}]"""), "ip -j -6 addr show" to ok("[]"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.set(MenuPath.parse("interface"), "2", mapOf("disabled" to "yes")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.remove(MenuPath.parse("ip/address"), listOf("eth0/192.0.2.50/24")) }.category)
        assertTrue(host.ran.none { it.startsWith("ip link set") || it.startsWith("ip addr del") }, "${host.ran}")
    }

    @Test
    fun aRuleOfTheFirewallComesWithAGuardThatTheAnswerOfTheHostTakesOff() = runTest {
        val host = Host(
            listOf(
                "for t in" to ok("iptables-save\nip6tables-save\nsystemctl\n"), "id -u" to ok("0\n"), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-firewall.Ab12Cd\n"),
                "iptables -w" to ok(), "true" to ok(), "kill" to ok(),
                "iptables-save -c" to ok("*filter\n:INPUT ACCEPT [0:0]\n[0:0] -A INPUT -s 192.0.2.0/24 -j DROP\nCOMMIT\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("filter/INPUT/1"), session.rows("firewall/iptables").map { it.id })
        session.add(MenuPath.parse("firewall/iptables"), mapOf("table" to "filter", "chain" to "INPUT", "rule" to "-p tcp --dport 22 -m comment --comment \"rd ssh\" -j ACCEPT", "position" to "1"))
        val guard = host.ran.single { it.startsWith("B=") }
        assertTrue("iptables-save > \"\$B/rules\"" in guard && "sleep 60; iptables-restore < \"\$1/rules\"" in guard, guard)
        val change = host.ran.indexOfFirst { it.startsWith("iptables -w") }
        assertEquals("iptables -w -t filter -I INPUT 1 '-p' 'tcp' '--dport' '22' '-m' 'comment' '--comment' 'rd ssh' '-j' 'ACCEPT'", host.ran[change])
        assertEquals("kill 4242; rm -rf '/tmp/networkhelm-firewall.Ab12Cd'", host.ran.last())

        session.remove(MenuPath.parse("firewall/iptables"), listOf("filter/INPUT/1", "filter/INPUT/3"))
        assertTrue("iptables -w -t filter -D INPUT 3 && iptables -w -t filter -D INPUT 1" in host.ran, "${host.ran}")
        assertEquals(listOf("ip6tables", "iptables"), session.describe(MenuPath.parse("firewall")).children)
    }

    @Test
    fun aChangeAfterWhichANewLoginGetsNoFurtherIsTakenBackAtOnce() = runTest {
        fun host() = Host(
            listOf(
                "for t in" to ok("iptables-save\n"), "id -u" to ok("0\n"), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-firewall.Ab12Cd\n"),
                "iptables -w" to ok(), "true" to ok(), "kill" to ok(),
            ),
        )
        val rule = mapOf("table" to "filter", "chain" to "INPUT", "rule" to "-p tcp --dport 22 -m conntrack --ctstate NEW -j DROP", "position" to "1")
        val locked = host()
        var logins = 0
        val session = SshHostSession(locked, device, linux = true) { if (++logins > 1) throw DeviceException(ErrorCategory.ConnectionLost, "timed out") }
        val refused = assertFailsWith<DeviceException> { session.add(MenuPath.parse("firewall/iptables"), rule) }
        assertTrue("no new SSH session" in refused.message.orEmpty(), refused.message)
        assertEquals(2, logins)
        assertEquals("kill 4242; sh -c 'iptables-restore < \"\$1/rules\"' restore '/tmp/networkhelm-firewall.Ab12Cd'; rm -rf '/tmp/networkhelm-firewall.Ab12Cd'", locked.ran.last())

        val single = host()
        var tried = 0
        SshHostSession(single, device, linux = true) {
            tried++
            throw DeviceException(ErrorCategory.PermissionDenied, "a second session is refused")
        }.add(MenuPath.parse("firewall/iptables"), rule)
        assertEquals(1, tried)
        assertEquals("kill 4242; rm -rf '/tmp/networkhelm-firewall.Ab12Cd'", single.ran.last())
    }

    @Test
    fun ufwIsNotTurnedOnWithoutARuleForThePortOfTheSession() = runTest {
        val host = Host(listOf("for t in" to ok("ufw\n"), "id -u" to ok("0\n"), "echo \$SSH_CONNECTION" to ok("192.0.2.2 51956 192.0.2.50 2222\n"), "ufw show added" to ok("Added user rules:\nufw allow 22/tcp\n")))
        val session = SshHostSession(host, device, linux = true)
        val refused = assertFailsWith<DeviceException> { session.set(MenuPath.parse("firewall/ufw/settings"), null, mapOf("enabled" to "yes")) }
        assertTrue("2222/tcp" in refused.message.orEmpty(), refused.message)
        assertTrue(host.ran.none { it.startsWith("ufw --force enable") || it.startsWith("B=") }, "${host.ran}")
    }

    @Test
    fun packagesAndServicesGoThroughTheManagerTheHostHas() = runTest {
        val host = Host(
            listOf(
                "for t in" to ok("apt-get\nrc-service\n"), "id -u" to ok("0\n"), "DEBIAN_FRONTEND=noninteractive apt-get" to ok("Setting up rd-test (1.0) ...\n"),
                "rc-service" to ok(), "rc-update" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val menuAgain = async(start = CoroutineStart.UNDISPATCHED) { session.structure.first() }
        assertEquals("rd-test", session.add(MenuPath.parse("system/package"), mapOf("name" to "rd-test")))
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60 install 'rd-test' 2>&1", host.ran.last())
        menuAgain.await()
        val upgraded = session.run(MenuPath.parse("system/package/update"), "upgrade", mapOf("numbers" to "bash,apt")).toList()
        assertEquals("Setting up rd-test (1.0) ...", upgraded.single()["output"])
        assertTrue(host.ran.last().contains("install --only-upgrade 'bash' 'apt'"), host.ran.last())
        assertFailsWith<DeviceException> { session.add(MenuPath.parse("system/package"), mapOf("name" to "rd;rm")) }

        session.run(MenuPath.parse("system/service"), "restart", mapOf("numbers" to "sshd")).toList()
        session.run(MenuPath.parse("system/service"), "disable", mapOf("numbers" to "crond")).toList()
        assertEquals(listOf("rc-service sshd restart", "rc-update -a del crond"), host.ran.takeLast(2))
    }

    @Test
    fun theFormOfAPackageShowsTheCommandOfTheManagerOfTheHost() = runTest {
        val session = SshHostSession(Host(listOf("for t in" to ok("pacman\nsystemctl\n"), "id -u" to ok("0\n"))), device, linux = true)
        val packages = MenuPath.parse("system/package")
        val wireguard = mapOf("name" to "wireguard-tools")
        assertEquals(null, session.hostCommand(packages, EditKind.Add, emptyList(), wireguard), "the manager is not known before the table is read")
        session.describe(packages)
        assertEquals("pacman --noconfirm -S 'wireguard-tools'", session.hostCommand(packages, EditKind.Add, emptyList(), wireguard))
        assertEquals("pacman --noconfirm -R 'nano'", session.hostCommand(packages, EditKind.Remove, listOf("nano"), emptyMap()))
        assertEquals(null, session.hostCommand(packages, EditKind.Add, emptyList(), mapOf("name" to "rd;rm")))
        assertEquals(null, session.hostCommand(MenuPath.parse("system/service"), EditKind.Add, emptyList(), wireguard))
    }

    @Test
    fun listeningPortsNameTheirProcessesAndUsers() {
        val rows = parseListening(
            "tcp   LISTEN 0      4096         0.0.0.0:22        0.0.0.0:*    users:((\"sshd\",pid=812,fd=3)) ino:7810 sk:7 <->\n" +
                "udp   UNCONN 0      0      127.0.0.53%lo:53        0.0.0.0:*    users:((\"systemd-resolve\",pid=152,fd=16)) uid:991 ino:1234 sk:1 <->\n" +
                "tcp   LISTEN 0      511             [::]:80           [::]:*    users:((\"nginx\",pid=900,fd=7),(\"nginx\",pid=901,fd=7)) ino:5 sk:2 v6only:1 <->\n" +
                "tcp   LISTEN 0      128        127.0.0.1:631       0.0.0.0:*\n",
            "root:x:0:0:root:/root:/bin/bash\nsystemd-resolve:x:991:991::/:/usr/sbin/nologin\n",
        )
        val fields = listOf("protocol", "address", "port", "process", "pid", "user", "interface")
        assertEquals(listOf("tcp", "0.0.0.0", "22", "sshd", "812", "root", ""), fields.map { rows[0][it] })
        assertEquals(listOf("udp", "127.0.0.53", "53", "systemd-resolve", "152", "systemd-resolve", "lo"), fields.map { rows[1][it] })
        assertEquals(listOf("tcp6", "::", "80", "nginx", "900, 901", "root", ""), fields.map { rows[2][it] })
        assertEquals(listOf("tcp", "127.0.0.1", "631", "", "", "", ""), fields.map { rows[3][it] })
    }

    @Test
    fun theDashboardOfAHostShowsItsDisksNetworkAndServices() {
        val status = parseHostStatus(
            listOf(
                "rd-test\n", "6.8.4-2-pve\n", "lxc\n", "model name\t: AMD Ryzen 7 5700U\n",
                "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/mapper/pve-vm--1--disk--0 4044016 1500000 2300000 40% /\n" +
                    "tmpfs 1000 0 1000 0% /run\n/dev/sdb1 1000000 250000 750000 25% /srv/data\n",
                "/dev/mapper/pve-vm--1--disk--0 / ext4 rw 0 0\ntmpfs /run tmpfs rw 0 0\n/dev/sdb1 /srv/data xfs rw 0 0\n",
                "default via 192.0.2.1 dev eth0 proto dhcp src 192.0.2.10 metric 100\n", "nameserver 192.0.2.1\nnameserver 2001:db8::1\n",
                "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever\n2: eth0    inet 192.0.2.10/24 brd 192.0.2.255 scope global dynamic eth0\\       valid_lft 1\n" +
                    "2: eth0    inet6 fe80::1/64 scope link \\       valid_lft forever\n",
                "rd-test.service loaded failed failed RD test\n", "ssh.service loaded active running OpenSSH\ncron.service loaded active running Cron\n",
                "nhtest   pts/0        2026-11-17 10:00 (192.0.2.2)\nnhtest   pts/1        2026-11-17 10:05 (192.0.2.2)\n",
                "  812 root      3.5  7340 sshd\n", "  900 www-data  0.1 51200 nginx\n", "x86_pkg_temp 47000\n", "tcp LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*\n",
                "firewalld inactive\nufw active\nnftables inactive\n", "yes\n", "2026-11-17 12:00:00\n",
            ),
        )
        assertEquals(listOf("rd-test", "6.8.4-2-pve", "lxc", "AMD Ryzen 7 5700U"), listOf("hostname", "kernel", "virtualization", "cpu-model").map { status[it] })
        assertEquals(listOf("/", "ext4", "/srv/data", "xfs"), listOf("disk.0.mount", "disk.0.type", "disk.1.mount", "disk.1.type").map { status[it] })
        assertEquals(null, status["disk.2.mount"])
        assertEquals(listOf("192.0.2.1", "eth0", "192.0.2.1, 2001:db8::1", "192.0.2.10/24"), listOf("gateway", "gateway-interface", "dns-servers", "net.eth0.addresses").map { status[it] })
        assertEquals(null, status["net.lo.addresses"])
        assertEquals(listOf("rd-test", "2", "nhtest", "1", "ufw", "true"), listOf("failed", "running-services", "users", "listening", "firewall", "reboot-required").map { status[it] })
        assertEquals(listOf("sshd", "3.5", "${7340 * 1024}", "nginx"), listOf("top-cpu.0.name", "top-cpu.0.cpu", "top-cpu.0.memory", "top-memory.0.name").map { status[it] })
        assertEquals(listOf("x86_pkg_temp", "47"), listOf("temperature.0.zone", "temperature.0.celsius").map { status[it] })
    }
}
