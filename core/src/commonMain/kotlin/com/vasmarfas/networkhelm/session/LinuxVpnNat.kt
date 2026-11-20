package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.openwrt.quote

// NAT for the clients of a tunnel where ufw or firewalld keeps the rules of the host: masquerade of its networks, forwarding from it
// and the port of its server opened. All it adds carries networkhelm-nat-<tunnel> and goes again by that name; forwarding, kept in
// sysctl.d, stays on, as another tunnel may need it
internal class LinuxVpnNat(private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    // ufw on or firewalld running; null where neither keeps the rules
    suspend fun manager(): String? {
        val present = tools()
        return when {
            "ufw" in present && root.masked("ufw status").lineSequence().first().substringAfter("Status:").trim() == "active" -> UFW
            "firewall-cmd" in present && root.exec("firewall-cmd --state").output.trim() == "running" -> FIREWALLD
            else -> null
        }
    }

    // an uplink that forwards IPv6 takes the adverts of its router only with accept_ra 2, else its default route of IPv6 runs out
    suspend fun forward(v6: Boolean) {
        val six = if (v6) "\"net.ipv6.conf.\$(ip -6 route show default | awk '$DEVICE' | tr . /).accept_ra = 2\" 'net.ipv6.conf.all.forwarding = 1' " else ""
        root.masked(
            "f=/etc/sysctl.d/99-networkhelm-forward.conf; touch \"\$f\" && for l in $six'net.ipv4.ip_forward = 1'; do case \"\$l\" in *conf..accept_ra*) continue;; esac; " +
                "grep -qxF \"\$l\" \"\$f\" || echo \"\$l\" >> \"\$f\" || exit 1; done && sysctl -p \"\$f\" >/dev/null",
        )
    }

    // ports as 51820/udp; no networks take the NAT of the tunnel away. A server of IPsec has no interface: its rules go by the networks
    // of its clients and the policy of IPsec, and firewalld needs the networks to let them go again. A sealed port takes only what came
    // through IPsec, as UDP 1701 of L2TP, which in the clear would give the logins of its users to anyone
    suspend fun set(manager: String, tunnel: String, networks: List<String>, ports: List<String>, on: Boolean, policy: Boolean = false, sealed: List<String> = emptyList()) {
        val mark = "$NAT_MARK-$tunnel"
        if (manager == FIREWALLD) {
            guard.guarded(FIREWALLD_BACKUP) { root.masked(firewalld(tunnel, mark, networks, ports, on, policy, sealed)) }
            return
        }
        guard.guarded(UFW_BACKUP) {
            for ((file, v6) in listOf("/etc/ufw/before.rules" to false, "/etc/ufw/before6.rules" to true)) {
                val text = root.exec("cat $file").takeIf { it.exitStatus == 0 }?.output ?: continue
                val nat = ufwNat(text, mark, tunnel, if (on) networks.filter { (':' in it) == v6 } else emptyList(), policy)
                val edited = ufwSealed(nat, mark, if (on) sealed else emptyList(), if (v6) "ufw6-before-input" else "ufw-before-input")
                if (edited != text) root.masked("cp -p $file $file.networkhelm-new && cat > $file.networkhelm-new && mv -f $file.networkhelm-new $file", edited)
            }
            val route = if (policy) networks.joinToString("") { " && ufw route allow from $it comment $mark" } else " && ufw route allow in on ${quote(tunnel)} comment $mark"
            root.masked(ufwDrop(mark) + (if (on) route + ports.joinToString("") { " && ufw allow $it comment $mark" } else "") + " && ufw reload")
        }
    }

    // the ports of a server opened apart from its NAT, as networkhelm-port-<server>: what was opened for it before goes first, and no
    // ports only close them. ufw takes a rule at once, firewalld a service of its own by a reload under the guard
    suspend fun open(server: String, ports: List<String>) {
        val mark = "$PORT_MARK-$server"
        when (manager()) {
            UFW -> root.masked(ufwDrop(mark) + ports.joinToString("") { " && ufw allow $it comment $mark" })
            FIREWALLD -> if (ports.isNotEmpty() || root.exec("firewall-cmd --permanent --info-service=$mark").exitStatus == 0) {
                guard.guarded(FIREWALLD_BACKUP) { root.masked(firewalldDrop(mark) + (if (ports.isEmpty()) "" else "$ZONE && ${firewalldService(mark, ports)}") + "firewall-cmd --reload") }
            }
        }
    }

    // a policy of its own lets the tunnel, put in the trusted zone, out through the zone of the default route with masquerade, and a
    // service of its own opens the ports there; what was added before goes first. The networks of a server of IPsec go to the trusted
    // zone as sources, as its clients come in by the interface of the default route. A zone of firewalld cannot ask for IPsec, so a
    // sealed port is open in the service and a direct rule drops what comes to it in the clear
    private fun firewalld(tunnel: String, mark: String, networks: List<String>, ports: List<String>, on: Boolean, policy: Boolean, sealed: List<String>): String {
        val face = quote(tunnel)
        val unbind = if (policy) networks.joinToString("") { "firewall-cmd --permanent --zone=trusted --remove-source=$it >/dev/null 2>&1; " } else "firewall-cmd --permanent --zone=trusted --remove-interface=$face >/dev/null 2>&1; "
        val bind = if (policy) networks.joinToString(" && ") { "firewall-cmd --permanent --zone=trusted --add-source=$it" } else "firewall-cmd --permanent --zone=trusted --add-interface=$face"
        val direct = sealed.flatMap { port -> listOf("ipv4", "ipv6").map { "$it filter INPUT 0 -p ${port.substringAfter('/')} --dport ${port.substringBefore('/')} -m policy --dir in --pol none -j DROP" } }
        val off = "firewall-cmd --permanent --delete-policy=$mark >/dev/null 2>&1; " + firewalldDrop(mark) + unbind +
            direct.joinToString("") { "firewall-cmd --permanent --direct --remove-rule $it >/dev/null 2>&1; " }
        val open = ports + sealed
        val add = "$ZONE && $bind && firewall-cmd --permanent --new-policy=$mark && " +
            "firewall-cmd --permanent --policy=$mark --add-ingress-zone=trusted && firewall-cmd --permanent --policy=$mark --add-egress-zone=\"\$z\" && " +
            "firewall-cmd --permanent --policy=$mark --set-target=ACCEPT && firewall-cmd --permanent --policy=$mark --add-masquerade && " +
            (if (open.isEmpty()) "" else firewalldService(mark, open)) + direct.joinToString("") { "firewall-cmd --permanent --direct --add-rule $it && " }
        return off + (if (on) add else "") + "firewall-cmd --reload"
    }
}

internal const val NAT_MARK = "networkhelm-nat"

private const val PORT_MARK = "networkhelm-port"

// the tunnels whose NAT ufw or firewalld keeps, by the marks of what was added for them
internal const val NAT_STATE = "grep -ho 'networkhelm-nat-[A-Za-z0-9_=+.-]*' /etc/ufw/before.rules /etc/ufw/before6.rules 2>/dev/null | sed 's/^/--nh-nat-- /'\n" +
    "for c in /etc/firewalld/policies/networkhelm-nat-*.xml; do [ -f \"\$c\" ] && { c=\${c##*/}; echo \"--nh-nat-- \${c%.xml}\"; }; done"

private const val UFW = "ufw"
private const val FIREWALLD = "firewalld"

// the interface of the default route out of ip route show default
private const val DEVICE = "{ for (i = 1; i < NF; i++) if (\$i == \"dev\") { print \$(i + 1); exit } }"

// the zone of firewalld the default route goes out by, in $z
private const val ZONE = "z=\$(firewall-cmd --get-zone-of-interface=\"\$(ip route show default | awk '$DEVICE')\" 2>/dev/null) || z=\$(firewall-cmd --get-default-zone)"

// the rules of ufw that carry a mark, deleted from the last, as ufw numbers them anew after each
private fun ufwDrop(mark: String) =
    "for n in \$(ufw status numbered | sed -n 's/^\\[ *\\([0-9]*\\)\\].*# $mark *\$/\\1/p' | sort -rn); do ufw --force delete \$n >/dev/null || exit 1; done"

// a service of firewalld out of every zone and gone
private fun firewalldDrop(mark: String) = "for z in \$(firewall-cmd --permanent --get-zones); do firewall-cmd --permanent --zone=\$z --remove-service=$mark >/dev/null 2>&1; done; " +
    "firewall-cmd --permanent --delete-service=$mark >/dev/null 2>&1; "

// a service of firewalld with the ports given, put in the zone $z
private fun firewalldService(mark: String, ports: List<String>) = "firewall-cmd --permanent --new-service=$mark && " +
    ports.joinToString("") { "firewall-cmd --permanent --service=$mark --add-port=$it && " } + "firewall-cmd --permanent --zone=\"\$z\" --add-service=$mark && "

// the nat table of ufw for one tunnel, a block of its own before the filter table of before.rules as the guide of ufw puts it; no
// networks take the block out. What clients of IPsec send back into a tunnel of IPsec keeps its address
internal fun ufwNat(text: String, mark: String, tunnel: String, networks: List<String>, policy: Boolean = false): String {
    val kept = ufwWithout(text.lines(), "# $mark")
    if (networks.isEmpty()) return kept.joinToString("\n")
    val at = kept.indexOfFirst { it.startsWith("*filter") }.coerceAtLeast(0)
    val rule = if (policy) "-m policy --dir out --pol none" else "! -o $tunnel"
    val block = listOf("# $mark", "*nat", ":POSTROUTING ACCEPT [0:0]") + networks.map { "-A POSTROUTING -s $it $rule -j MASQUERADE" } + listOf("COMMIT", "# $mark end")
    return (kept.take(at) + block + kept.drop(at)).joinToString("\n")
}

// the sealed ports, a block of its own in the filter table of before.rules right after the chains it declares, which takes into the
// input chain of ufw only what came through IPsec; ufw drops the rest. No ports take the block out
internal fun ufwSealed(text: String, mark: String, ports: List<String>, chain: String): String {
    val kept = ufwWithout(text.lines(), "# $mark sealed")
    val table = kept.indexOfFirst { it.startsWith("*filter") }
    if (ports.isEmpty() || table < 0) return kept.joinToString("\n")
    val at = (table + 1 until kept.size).firstOrNull { !kept[it].startsWith(":") } ?: kept.size
    val rules = ports.map { "-A $chain -p ${it.substringAfter('/')} --dport ${it.substringBefore('/')} -m policy --dir in --pol ipsec -j ACCEPT" }
    return (kept.take(at) + listOf("# $mark sealed") + rules + "# $mark sealed end" + kept.drop(at)).joinToString("\n")
}

private fun ufwWithout(lines: List<String>, head: String): List<String> {
    val start = lines.indexOf(head)
    val end = lines.indexOf("$head end")
    return if (start >= 0 && end > start) lines.take(start) + lines.drop(end + 1) else lines
}
