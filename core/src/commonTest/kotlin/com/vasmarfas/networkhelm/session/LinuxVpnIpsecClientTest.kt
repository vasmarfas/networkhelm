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

class LinuxVpnIpsecClientTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val clients = MenuPath.parse("vpn/ipsec/client")

    // a client made here as the read of strongSwan shows it, its secret masked on the host, and its IKE SA in the form of --list-sas --raw
    // the lab printed on 17.11
    private val state = "--nh-loaded--\noffice\n--nh-file-- /etc/swanctl/conf.d/networkhelm-client-office.conf\nconnections {\n    office {\n" +
        "        version = 2\n        remote_addrs = 192.168.10.250\n        vips = 0.0.0.0\n        local {\n            auth = eap-mschapv2\n" +
        "            eap_id = nh-ike\n        }\n        remote {\n            auth = pubkey\n            id = vpn.example.net\n" +
        "            cacerts = networkhelm-client-office-ca.crt\n        }\n        children {\n            office {\n                remote_ts = 10.10.0.0/16\n" +
        "            }\n        }\n    }\n}\nsecrets {\n    eap-client-office {\n        id = nh-ike\n        secret = (hidden)\n    }\n}\n" +
        "--nh-unit-- strongswan.service active\n"

    private val sa = "list-sa event {office {uniqueid=3 version=2 state=ESTABLISHED remote-host=192.168.10.250 remote-port=4500 child-sas {}}}\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("swanctl\nsystemctl\n"), "id -u" to ok("0\n"), "echo --nh-loaded--" to ok(state), "swanctl --list-sas --raw" to ok(sa),
            "swanctl --stats" to ok("loaded plugins: charon random nonce x509 pubkey pem eap-identity eap-mschapv2 vici\n"), "cp '/etc/ssl" to ok(),
            "umask 077; mkdir -p" to ok("loaded connection 'office'\nsuccessfully loaded 1 connections, 0 unloaded\n"), "rm -f" to ok(),
            "cat '/etc/swanctl/conf.d/networkhelm-client-office.conf'" to ok("connections {\n}\nsecrets {\n    eap-client-office {\n        secret = \"rd-test-pw\"\n    }\n}\n"),
            "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(), "swanctl --initiate" to ok(), "if command -v swanctl" to ok(),
        ),
    )

    @Test
    fun aClientShowsItsSettingsAndItsSaAndOnlyItsTabChangesIt() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val (office) = session.rows("vpn/ipsec/client")
        assertEquals(
            listOf("ESTABLISHED", "true", "192.168.10.250", "vpn.example.net", "eap-mschapv2", "nh-ike", "10.10.0.0/16", "yes", "/etc/swanctl/x509ca/networkhelm-client-office-ca.crt", ""),
            listOf("state", "loaded", "server", "server-id", "auth", "user", "networks", "vip", "ca-file", "secret").map { office[it] },
        )
        val kept = assertFailsWith<DeviceException> { session.set(MenuPath.parse("vpn/ipsec"), "office", mapOf("dns" to "9.9.9.9")) }
        assertEquals("office is a client connection, which its tab keeps", kept.message)
    }

    @Test
    fun aNewClientHasItsSecretInItsFileAndTheCaOfItsServerBesideTheOthers() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        session.add(
            clients,
            mapOf("name" to "home", "server" to "vpn.example.net", "user" to "nh-ike", "secret" to "rd-test-pw", "ca-file" to "/etc/ssl/certs/ISRG_Root_X1.pem", "networks" to "0.0.0.0/0"),
        )
        assertTrue("cp '/etc/ssl/certs/ISRG_Root_X1.pem' '/etc/swanctl/x509ca/networkhelm-client-home-ca.crt' && chmod 644 '/etc/swanctl/x509ca/networkhelm-client-home-ca.crt'" in host.ran, "${host.ran}")
        val written = host.given.single().lines().map { it.trim() }
        assertTrue(
            listOf(
                "remote_addrs = vpn.example.net", "vips = 0.0.0.0", "auth = eap-mschapv2", "eap_id = nh-ike", "auth = pubkey", "id = vpn.example.net",
                "cacerts = networkhelm-client-home-ca.crt", "remote_ts = 0.0.0.0/0", "eap-client-home {", "id = nh-ike", "secret = \"rd-test-pw\"",
            ).all { it in written },
            "$written",
        )
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/swanctl/conf.d'") && it.endsWith("\nswanctl --load-all 2>&1") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-pw" in it }, "the secret is on no command line")
        val none = assertFailsWith<DeviceException> { session.add(clients, mapOf("name" to "lab", "server" to "vpn.example.net", "user" to "nh-ike", "secret" to "rd-test-pw")) }
        assertEquals("networks: a value is needed", none.message)

        // a change without a secret keeps the one of the file and the CA there is
        val changed = host()
        SshHostSession(changed, device, linux = true).set(clients, "office", mapOf("networks" to "10.20.0.0/16"))
        val rewritten = changed.given.single()
        assertTrue("remote_ts = 10.20.0.0/16" in rewritten && "secret = \"rd-test-pw\"" in rewritten && "cacerts = networkhelm-client-office-ca.crt" in rewritten, rewritten)
        assertTrue(changed.ran.none { it.startsWith("cp ") }, "${changed.ran}")
    }

    @Test
    fun aClientComesUpUnderTheGuardAndOneThatDoesNotIsEndedAtOnce() = runTest {
        val host = host("swanctl --list-sas --raw" to ok())
        SshHostSession(host, device, linux = true).run(clients, "up", mapOf("numbers" to "office")).toList()
        assertTrue("swanctl --initiate --ike 'office' --child 'office' >&2" in host.ran, "${host.ran}")
        assertTrue("swanctl --terminate --ike '\\''office'\\'' --force" in host.ran.single { it.startsWith("B=") }, "${host.ran}")

        val refused = host("swanctl --list-sas --raw" to ok(), "swanctl --initiate" to ExecResult("", "[IKE] received AUTHENTICATION_FAILED notify error\ninitiate failed: establishing CHILD_SA 'office' failed\n", 1))
        val failed = assertFailsWith<DeviceException> { SshHostSession(refused, device, linux = true).run(clients, "up", mapOf("numbers" to "office")).toList() }
        assertTrue("AUTHENTICATION_FAILED" in failed.message.orEmpty(), failed.message)
        assertTrue(refused.ran.last().startsWith("kill 4242; sh -c 'if command -v swanctl"), refused.ran.last())

        // one that is up already is left as it is, and down ends its SAs
        val up = host()
        val session = SshHostSession(up, device, linux = true)
        session.run(clients, "up", mapOf("numbers" to "office")).toList()
        assertTrue(up.ran.none { it.startsWith("swanctl --initiate") || it.startsWith("B=") }, "${up.ran}")
        session.run(clients, "down", mapOf("numbers" to "office")).toList()
        assertEquals(ikeTerminate("office"), up.ran.last())
    }

    // after an initiate that failed in the lab on 18.11 --list-sas still showed an SA that --terminate no longer found, and the removal
    // stopped there
    @Test
    fun aClientGoesWhereSwanctlFindsNoSaLeftToEnd() = runTest {
        val terminate = ikeTerminate("office")
        assertTrue("o=\$(swanctl --terminate --ike 'office' --force 2>&1) || case \"\$o\" in *'no matching SAs'*) ;;" in terminate, terminate)
        val host = host()
        SshHostSession(host, device, linux = true).remove(clients, listOf("office"))
        assertEquals(
            "$terminate && rm -f '/etc/swanctl/conf.d/networkhelm-client-office.conf' '/etc/swanctl/x509ca/networkhelm-client-office-ca.crt' && swanctl --load-all >/dev/null",
            host.ran.last(),
        )
    }

    // the profile for Android a server of IKEv2 made here gives, as the window of users writes it
    private val sswan = "{\"uuid\":\"uuid-1\",\"name\":\"ikev2\",\"type\":\"ikev2-eap\",\"remote\":{\"addr\":\"vpn.example.net\",\"cert\":\"MIIBrdTestCa==\"},\"local\":{\"eap_id\":\"nh-ike\"}}"

    @Test
    fun aClientIsImportedFromTheProfileOfItsServerWithThePasswordThroughTheInputAndComesUpUnderTheGuard() = runTest {
        val importing = MenuPath.parse("vpn/ipsec/import")
        assertEquals(listOf("client", "0.0.0.0/0"), SshHostSession(host(), device, linux = true).get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["networks"]) })

        val home = "--nh-file-- /etc/swanctl/conf.d/networkhelm-client-home.conf\nconnections {\n    home {\n        remote_addrs = vpn.example.net\n    }\n}\n"
        val host = VpnHost(host().answers, mapOf("echo --nh-loaded--" to listOf(ok(state), ok(state), ok(state + home))))
        val session = SshHostSession(host, device, linux = true)
        val reply = session.run(importing, "import", mapOf("name" to "home", "password" to "rd-test-pw", "networks" to "", "text" to sswan)).toList().single()
        assertEquals("home", reply["name"])
        assertEquals("-----BEGIN CERTIFICATE-----\nMIIBrdTestCa==\n-----END CERTIFICATE-----\n", host.given.first())
        val written = host.given[1].lines().map { it.trim() }
        assertTrue(
            listOf("remote_addrs = vpn.example.net", "eap_id = nh-ike", "cacerts = networkhelm-client-home-ca.crt", "remote_ts = 0.0.0.0/0", "secret = \"rd-test-pw\"").all { it in written },
            "$written",
        )
        assertTrue("swanctl --initiate --ike 'home' --child 'home' >&2" in host.ran && host.ran.any { it.startsWith("B=") } && host.ran.none { it.startsWith("cp ") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-pw" in it }, "the password is on no command line")

        val other = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "lab", "password" to "rd-test-pw", "text" to "[Interface]\nPrivateKey = rd-test")).toList() }
        assertEquals("text: a profile of IKEv2 or the certificate of its CA in PEM is needed", other.message)
        val bare = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "lab", "text" to sswan)).toList() }
        assertEquals("password: a value is needed", bare.message)
        assertEquals(2, host.given.size, "a refused import writes no file")
    }

    @Test
    fun thePanelLeavesOutTheConnectionsOfTheHostAsAClient() = runTest {
        val panel = "--nh-now-- 1794939096\n--nh-zone-- +0300\n--nh-ike-clients--\noffice\n--nh-ike-conns--\noffice\nrw\n--nh-ike-sas--\n$sa" +
            "list-sa event {rw {uniqueid=7 version=2 state=ESTABLISHED remote-host=203.0.113.5 remote-port=4500 remote-eap-id=alice child-sas {}}}\n"
        val session = SshHostSession(host("echo \"--nh-now--" to ok(panel)), device, linux = true)
        assertEquals(listOf("ipsec/rw/7"), session.rows("vpn/session").map { it.id })
    }
}
