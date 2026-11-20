package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val overview = MenuPath.parse("vpn/overview")

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private suspend fun install(host: VpnHost, rows: String) = SshHostSession(host, device, linux = true).run(overview, "install", mapOf("numbers" to rows)).toList()

    @Test
    fun theRowsChosenGoToTheManagerOfTheHostAndItsOutputComesBack() = runTest {
        val host = VpnHost(listOf("for t in" to ok("apt-get\nsystemctl\n"), "id -u" to ok("0\n"), "DEBIAN_FRONTEND=noninteractive apt-get" to ok("Setting up rd-test (1.0) ...\n")))
        assertEquals("Setting up rd-test (1.0) ...", install(host, "openvpn,wireguard").single()["output"])
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60 install 'wireguard-tools' 'openvpn' 'easy-rsa' 'socat' 2>&1", host.ran.last())

        val rows = SshHostSession(VpnHost(listOf("for t in" to ok("apk\nawg\n"), "id -u" to ok("0\n"))), device, linux = true).rows("vpn/overview")
        val amnezia = rows.single { it.id == "amneziawg" }
        assertEquals(listOf("AmneziaWG", "true", "amneziawg-tools amneziawg-go"), listOf("name", "installed", "packages").map { amnezia[it] })
    }

    // no distribution of the lab packages AmneziaWG: its tools and amneziawg-go come by a script of their own, after the packages of the
    // other kinds, and what it printed follows theirs
    @Test
    fun amneziaWgIsBuiltByAScriptOfItsOwnAfterThePackagesOfTheOtherKinds() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("apt-get\nsystemctl\n"), "id -u" to ok("0\n"), "DEBIAN_FRONTEND=noninteractive apt-get" to ok("Setting up openvpn (2.6.3-1+deb12u3) ...\n"),
                "{\nset -e" to ok("amneziawg-tools v3.1.20260812\ngo1.27.4.linux-amd64.tar.gz\n/var/tmp/networkhelm-awg.Ab12Cd/go.tar.gz: OK\n"),
            ),
        )
        val output = install(host, "openvpn,amneziawg").single()["output"].orEmpty()
        assertTrue(output.startsWith("Setting up openvpn (2.6.3-1+deb12u3) ...\n\namneziawg-tools v3.1.20260812"), output)
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60 install 'openvpn' 'easy-rsa' 'socat' 2>&1", host.ran[host.ran.size - 2])
        assertEquals("$AWG_INSTALL 2>&1", host.ran.last())
        assertTrue("add-apt-repository -y ppa:amnezia/ppa" in AWG_INSTALL && "sha256sum -c -" in AWG_INSTALL && "amneziawg-go/v3@latest" in AWG_INSTALL, AWG_INSTALL)
    }

    // what dnf5 of Fedora 44, pacman of Arch and apt of Debian 12 printed in the lab on 18.11 for packages their repositories lack,
    // .notes/lab/samples/vpn-hosts-1811.txt; a package the window did not ask for is not taken for its own
    @Test
    fun aKindTheRepositoriesLackSaysWhereItComesFrom() = runTest {
        val fedora = VpnHost(
            listOf(
                "for t in" to ok("dnf\n"), "id -u" to ok("0\n"),
                "dnf -y install" to ExecResult(
                    "Updating and loading repositories:\n Fedora 44 - x86_64 - Updates           100% |   9.9 KiB/s |   8.2 KiB |  00m01s\nRepositories loaded.\n" +
                        "Failed to resolve the transaction:\nNo match for argument: zerotier-one\nNo match for argument: amneziawg-tools\nYou can try to add to command line:\n" +
                        "  --skip-unavailable to skip unavailable packages\n",
                    "", 1,
                ),
            ),
        )
        assertEquals(
            "the repositories of the host have no ZeroTier: its own repository comes with curl -s https://install.zerotier.com | sudo bash",
            assertFailsWith<DeviceException> { install(fedora, "zerotier") }.message,
        )
        val arch = VpnHost(
            listOf("for t in" to ok("pacman\n"), "id -u" to ok("0\n"), "pacman --noconfirm -S" to ExecResult("error: target not found: ocserv\nerror: target not found: amneziawg-tools\n", "", 1)),
        )
        assertEquals(
            "the repositories of the host have no ocserv: it comes from a repository the host does not have yet",
            assertFailsWith<DeviceException> { install(arch, "wireguard,ocserv") }.message,
        )
        val debian = VpnHost(
            listOf(
                "for t in" to ok("apt-get\n"), "id -u" to ok("0\n"),
                "DEBIAN_FRONTEND" to ExecResult(
                    "Reading package lists...\nBuilding dependency tree...\nReading state information...\nE: Unable to locate package tailscale\nE: Unable to locate package amneziawg\n", "", 100,
                ),
            ),
        )
        assertEquals(
            "the repositories of the host have no Tailscale: its own repository comes with curl -fsSL https://tailscale.com/install.sh | sh",
            assertFailsWith<DeviceException> { install(debian, "tailscale") }.message,
        )
        // written to the form apt prints a lock it waited for in vain
        val locked = VpnHost(
            listOf(
                "for t in" to ok("apt-get\n"), "id -u" to ok("0\n"),
                "DEBIAN_FRONTEND" to ExecResult("E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 812 (apt-get)\n", "", 100),
            ),
        )
        assertEquals("E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 812 (apt-get)", assertFailsWith<DeviceException> { install(locked, "zerotier") }.message)
    }
}
