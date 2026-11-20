package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.quote

// the limits of the rates of the users of the VPN by the addresses they have in their tunnels: HTB on the device of the tunnel for what
// goes to a client, HTB on an IFB that the ingress of the device is redirected to for what comes from it. The limits are lines of
// /etc/networkhelm/vpn-limits, which a script of its own makes into tc for one device from nothing, at once and, by a rule of udev,
// whenever the device appears again
internal class LinuxVpnLimits(private val root: Root) {
    suspend fun read(): List<VpnLimit> = parseVpnLimits(root.exec("cat $LIMITS").takeIf { it.exitStatus == 0 }?.output.orEmpty())

    // the lines of a user replaced by those given, and tc made again on every device either of them is on
    suspend fun set(user: String, limits: List<VpnLimit>) {
        val before = read()
        val devices = (before.filter { it.user == user } + limits).map { it.device }.distinct()
        if (devices.isEmpty()) return
        root.write(LIMITS_SCRIPT_FILE, LIMITS_SCRIPT, "true", mode = "755")
        root.write(LIMITS_RULE, "ACTION==\"add\", SUBSYSTEM==\"net\", KERNEL!=\"ifb-*\", RUN+=\"$LIMITS_SCRIPT_FILE %k\"", "udevadm control --reload 2>/dev/null; true", mode = "644")
        root.write(LIMITS, (before.filter { it.user != user } + limits).joinToString("\n") { "${it.user} ${it.device} ${it.address} ${it.down} ${it.up}" }, "true", mode = "644")
        root.masked(devices.joinToString(" && ") { "$LIMITS_SCRIPT_FILE ${quote(it)}" })
    }
}

// a limit of a user on one address of one device, in kbit/s toward the client and from it; 0 for none
internal class VpnLimit(val user: String, val device: String, val address: String, val down: Long, val up: Long)

internal fun parseVpnLimits(text: String): List<VpnLimit> = text.lines().map { it.trim().split(Regex("\\s+")) }.filter { it.size == 5 }.mapNotNull { words ->
    VpnLimit(words[0], words[1], words[2], words[3].toLongOrNull() ?: return@mapNotNull null, words[4].toLongOrNull() ?: return@mapNotNull null)
}

// a rate as RouterOS writes it, upload/download from the client: 5M/20M, 512k, or one rate for both ways, in kbit/s
internal fun vpnRates(text: String): Pair<Long, Long> {
    val parts = text.split('/').map { it.trim() }
    val rates = parts.mapNotNull { part ->
        LIMIT_RATE.matchEntire(part)?.destructured?.let { (count, unit) -> if (unit.isEmpty()) count.toLong() / 1_000 else count.toLong() * LIMIT_UNITS.getValue(unit.lowercase()) }
    }
    if (parts.size > 2 || rates.size != parts.size) throw DeviceException(ErrorCategory.ArgumentValue, "rate-limit: $text is not a value it takes")
    return rates.first() to rates.last()
}

// kbit/s as RouterOS writes it, 20M or 512k
internal fun vpnRate(kbit: Long): String = when {
    kbit == 0L -> "0"
    kbit % 1_000_000 == 0L -> "${kbit / 1_000_000}G"
    kbit % 1_000 == 0L -> "${kbit / 1_000}M"
    else -> "${kbit}k"
}

private const val LIMITS = "/etc/networkhelm/vpn-limits"
private const val LIMITS_SCRIPT_FILE = "/usr/local/sbin/networkhelm-vpn-limits"
private const val LIMITS_RULE = "/etc/udev/rules.d/90-networkhelm-vpn-limits.rules"

private val LIMIT_RATE = Regex("(\\d+)([kKmMgG]?)")
private val LIMIT_UNITS = mapOf("k" to 1L, "m" to 1_000L, "g" to 1_000_000L)

// tc of one device from the lines of the limits: HTB on it with a class of each address toward the client, the ingress redirected to
// an IFB of its own with a class of each address from the client, all else in a class without a limit
private val LIMITS_SCRIPT = $$"""#!/bin/sh
# the limits of the rates of the users of the VPN on one device, made by NetworkHelm from /etc/networkhelm/vpn-limits
PATH=/usr/sbin:/usr/bin:/sbin:/bin
d=$1
f=/etc/networkhelm/vpn-limits
i=ifb-$(printf %s "$d" | cut -c1-11)
tc qdisc del dev "$d" root 2>/dev/null
tc qdisc del dev "$d" ingress 2>/dev/null
ip link del "$i" 2>/dev/null
[ -f "$f" ] && awk -v d="$d" '$2 == d { found = 1 } END { exit !found }' "$f" || exit 0
modprobe ifb numifbs=0 2>/dev/null
ip link add "$i" type ifb && ip link set "$i" up || exit 1
for x in "$d" "$i"; do tc qdisc add dev "$x" root handle 1: htb default 1 && tc class add dev "$x" parent 1: classid 1:1 htb rate 10gbit || exit 1; done
tc qdisc add dev "$d" handle ffff: ingress && tc filter add dev "$d" parent ffff: matchall action mirred egress redirect dev "$i" || exit 1
n=10
while read -r user dev addr down up; do
  [ "$dev" = "$d" ] || continue
  n=$((n + 1))
  case "$addr" in *:*) p=ipv6; m=ip6;; *) p=ip; m=ip;; esac
  if [ "$down" -gt 0 ]; then tc class add dev "$d" parent 1: classid 1:$n htb rate "${down}kbit" && tc filter add dev "$d" parent 1: protocol $p prio 1 u32 match $m dst "$addr" flowid 1:$n || exit 1; fi
  if [ "$up" -gt 0 ]; then tc class add dev "$i" parent 1: classid 1:$n htb rate "${up}kbit" && tc filter add dev "$i" parent 1: protocol $p prio 1 u32 match $m src "$addr" flowid 1:$n || exit 1; fi
done < "$f"
"""
