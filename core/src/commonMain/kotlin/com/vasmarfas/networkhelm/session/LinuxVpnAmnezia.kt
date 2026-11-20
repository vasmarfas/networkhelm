package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ValueType
import kotlin.random.Random

// AmneziaWG of a Linux host is WireGuard with its packets disguised, kept by awg-quick in /etc/amnezia/amneziawg, and goes through
// the window of WireGuard of this kind. The disguise is a set of keys in [Interface]: the junk packets a handshake starts with (Jc,
// Jmin, Jmax), the padding of the messages of the handshake (S1, S2) and, from version 2.0, of the cookie reply and the data (S3, S4),
// the types of the four messages (H1 to H4, from 2.0 a range each) and, from version 1.5, packets of its own a client sends first
// (I1 to I5). Both ends need the same paddings and types
internal val DISGUISE = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5")

internal val AMNEZIA = WgKind("AmneziaWG", "vpn/amneziawg", "awg", "awg-quick", "/etc/amnezia/amneziawg", DISGUISE, AWG_OPENRC)

// the ranges of the documentation of amneziawg-go, which counts on a way of 1280 bytes: the padded messages of the handshake fit it
internal val DISGUISE_LIMITS = mapOf("jc" to 1..128, "jmin" to 0..1280, "jmax" to 1..1280, "s1" to 0..1132, "s2" to 0..1188, "s3" to 0..1216, "s4" to 0..32)

internal val HEADERS = setOf("h1", "h2", "h3", "h4")

// what awg of version 1.0 takes without listing it in its usage
private val FIRST = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

internal val DISGUISE_ARGUMENTS = listOf(
    limited("jc", "Junk Packets (Jc)"), limited("jmin", "Junk Min Size (Jmin)"), limited("jmax", "Junk Max Size (Jmax)"), limited("s1", "Init Padding (S1)"),
    limited("s2", "Response Padding (S2)"), limited("s3", "Cookie Padding (S3)"), limited("s4", "Data Padding (S4)"),
) + listOf("Init", "Response", "Cookie", "Data").mapIndexed { at, message ->
    Argument("h${at + 1}", ValueType.Text(), optional = true, label = "$message Type (H${at + 1})", tab = "disguise", placeholder = "1234567890")
} + (1..5).map { Argument("i$it", ValueType.Text(), optional = true, label = "Special Junk (I$it)", tab = "disguise") }

// the names awg set lists in its usage, one a line; the usage goes to stderr with exit 1
internal const val AWG_USAGE = """awg set 2>&1 | tr ' ' '\n' | sed -n 's/^\[\([a-z0-9-]*\)$/\1/p'; true"""

// the keys of the disguise the awg of the host takes: those of 1.0 and the ones its usage lists, which versions from 1.5 on do
internal fun awgKnows(usage: String): Set<String> = FIRST + usage.lines().map { it.trim() }.filter { field -> DISGUISE.any { it.lowercase() == field } }

// the disguise AmneziaVPN gives a new server, in the form of version 1.0 every client of AmneziaWG reads: 4 to 6 junk packets of 10 to
// 50 bytes, paddings of 15 to 149 bytes that leave the first two messages of the handshake of different lengths, and four types of
// messages above the four of WireGuard
internal fun proposedDisguise(random: Random = Random): Map<String, String> {
    val s1 = random.nextInt(15, 150)
    val s2 = generateSequence { random.nextInt(15, 150) }.first { it != s1 && it != s1 + 56 }
    val types = generateSequence { random.nextInt(5, Int.MAX_VALUE) }.distinct().take(4).toList()
    return linkedMapOf("jc" to "${random.nextInt(4, 7)}", "jmin" to "10", "jmax" to "50", "s1" to "$s1", "s2" to "$s2") + types.mapIndexed { at, type -> "h${at + 1}" to "$type" }
}

// a type of a message, from version 2.0 a range of them, above the four of WireGuard and within a signed number of 32 bits, which
// AmneziaVPN keeps to
internal fun headerRange(value: String): LongRange? {
    val ends = value.split('-').map { it.trim().toLongOrNull() ?: return null }
    if (ends.size > 2 || ends.any { it !in 5..Int.MAX_VALUE.toLong() } || ends.first() > ends.last()) return null
    return ends.first()..ends.last()
}

// what the awg of the host takes and both ends need: the keys it knows, ranges of H1 to H4 only with version 2.0, Jmin up to Jmax,
// S2 other than S1 + 56, which would leave the first two messages of a handshake of one length, and four types of messages apart
internal fun checkDisguise(values: Map<String, String>, knows: Set<String>) {
    val set = values.filterValues { it.isNotEmpty() }
    set.keys.firstOrNull { it !in knows }?.let { throw unknownDisguise(key(it)) }
    set.entries.firstOrNull { (field, value) -> field in HEADERS && '-' in value && "s3" !in knows }?.let { throw unknownDisguise("${key(it.key)} = ${it.value}") }
    val jmin = set["jmin"]?.toIntOrNull()
    val jmax = set["jmax"]?.toIntOrNull()
    if (jmin != null && jmax != null && jmin > jmax) throw DeviceException(ErrorCategory.ArgumentValue, "jmax: Jmin $jmin is above Jmax $jmax")
    if ((set["s1"]?.toIntOrNull() ?: 0) + 56 == (set["s2"]?.toIntOrNull() ?: 0)) {
        throw DeviceException(ErrorCategory.ArgumentValue, "s2: with S2 = S1 + 56 the first two messages of the handshake are of one length")
    }
    val types = set.filterKeys { it in HEADERS }.mapNotNull { (field, value) -> headerRange(value)?.let { field to it } }
    types.forEachIndexed { at, (field, range) ->
        if (types.take(at).any { (_, other) -> range.first <= other.last && other.first <= range.last }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "$field: H1, H2, H3 and H4 need values of their own and ranges that do not overlap")
        }
    }
}

internal fun unknownDisguise(what: String) = DeviceException(ErrorCategory.Unsupported, "the awg of the host does not know $what; update amneziawg-tools or leave it out")

private fun key(field: String) = DISGUISE.first { it.lowercase() == field }

private fun limited(field: String, label: String) =
    DISGUISE_LIMITS.getValue(field).let { Argument(field, ValueType.Integer(it.first.toLong(), it.last.toLong()), optional = true, label = label, tab = "disguise") }

// Install Now of AmneziaWG, whose packages no distribution of the lab has. The tools come from the PPA of Amnezia on Ubuntu, elsewhere
// they are built from the source of their latest release with the compiler of the manager. Where the module of the kernel does not
// load, in a container always, the tunnel runs in amneziawg-go, which awg-quick starts over /dev/net/tun by itself; it is built by
// Go, whose archive goes to a folder of its own for the build, checked against the sum go.dev gives, and is gone after it. The PPA
// has the module for the kernels of Ubuntu, which it builds by DKMS outside a container
internal const val AWG_INSTALL = $$"""{
set -e
t=$(mktemp -d /var/tmp/networkhelm-awg.XXXXXX)
trap 'rm -rf "$t"' EXIT
m=; for x in apt-get dnf apk pacman; do command -v $x >/dev/null 2>&1 && { m=$x; break; }; done
p() { case $m in
apt-get) DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60 install "$@";;
dnf) dnf -y install "$@";;
apk) apk add "$@";;
pacman) pacman --noconfirm --needed -S "$@";;
*) echo 'the host has none of apt, dnf, apk and pacman' >&2; exit 1;;
esac; }
command -v curl >/dev/null 2>&1 || p curl
ID=; [ ! -r /etc/os-release ] || . /etc/os-release
box=; if systemd-detect-virt -cq 2>/dev/null || tr '\0' '\n' < /proc/1/environ 2>/dev/null | grep -q '^container='; then box=1; fi
if ! command -v awg >/dev/null 2>&1 || ! command -v awg-quick >/dev/null 2>&1; then
if [ "$ID" = ubuntu ]; then
command -v add-apt-repository >/dev/null 2>&1 || p software-properties-common
add-apt-repository -y ppa:amnezia/ppa
if [ -n "$box" ]; then p amneziawg-tools; else p amneziawg-tools amneziawg-dkms "linux-headers-$(uname -r)"; fi
else
case $m in apt-get) p gcc make libc6-dev;; dnf) p gcc make;; apk) p build-base linux-headers bash iproute2;; pacman) p gcc make;; esac
curl -fsSL -o "$t/tools.json" https://api.github.com/repos/amnezia-vpn/amneziawg-tools/releases/latest
v=$(sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' "$t/tools.json" | head -n 1)
echo "amneziawg-tools $v"
curl -fsSL -o "$t/tools.tar.gz" "https://github.com/amnezia-vpn/amneziawg-tools/archive/refs/tags/$v.tar.gz"
tar -xzf "$t/tools.tar.gz" -C "$t"
make -C "$t/amneziawg-tools-${v#v}/src" WITH_WGQUICK=yes install
fi
fi
if [ ! -e /sys/module/amneziawg ] && ! modprobe amneziawg 2>/dev/null && ! command -v amneziawg-go >/dev/null 2>&1; then
case $(uname -m) in x86_64) a=amd64;; aarch64) a=arm64;; armv6l|armv7l) a=armv6l;; i686|i386) a=386;; riscv64) a=riscv64;; s390x) a=s390x;; ppc64le) a=ppc64le;; loongarch64) a=loong64;; *) echo "Go has no build for $(uname -m)" >&2; exit 1;; esac
curl -fsSL -o "$t/go.json" 'https://go.dev/dl/?mode=json'
f=$(sed -n "s/.*\"filename\": *\"\(go[0-9.]*\.linux-$a\.tar\.gz\)\".*/\1/p" "$t/go.json" | head -n 1)
s=$(awk -v f="\"$f\"" 'index($0, f) { h = 1 } h && /"sha256"/ { sub(/.*"sha256": *"/, ""); sub(/".*/, ""); print; exit }' "$t/go.json")
echo "$f"
curl -fsSL -o "$t/go.tar.gz" "https://go.dev/dl/$f"
echo "$s  $t/go.tar.gz" | sha256sum -c -
tar -xzf "$t/go.tar.gz" -C "$t"
GOTOOLCHAIN=local CGO_ENABLED=0 GOPATH="$t/gopath" GOCACHE="$t/cache" GOBIN=/usr/bin "$t/go/bin/go" install github.com/amnezia-vpn/amneziawg-go/v3@latest
fi
awg --version
! command -v amneziawg-go >/dev/null 2>&1 || amneziawg-go --version
}"""

// the service of OpenRC for awg-quick, which no package of Alpine brings: awg-quick.<interface> is a link to it, as wg-quick.<interface>
// is to the one of wireguard-tools-openrc
internal const val AWG_OPENRC = $$"""#!/sbin/openrc-run
# awg-quick for the interface after the dot of the name of the service, made by NetworkHelm
description="AmneziaWG interface ${RC_SVCNAME#*.}"

depend() {
    need net
    use dns
}

start() {
    ebegin "Starting awg-quick for ${RC_SVCNAME#*.}"
    awg-quick up "${RC_SVCNAME#*.}"
    eend $?
}

stop() {
    ebegin "Stopping awg-quick for ${RC_SVCNAME#*.}"
    awg-quick down "${RC_SVCNAME#*.}"
    eend $?
}"""
