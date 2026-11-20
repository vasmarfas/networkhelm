package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.quote
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

// the log of the VPN sessions a host keeps where the owner asks for it, decision 1: a script of sh and awk that a timer of systemd, or
// cron under OpenRC, runs every 30 s. It takes the lines of wg, awg and the status files of OpenVPN that stand for live sessions, the
// preshared keys masked, and writes the start and the end of each and its counters every 5 minutes; of their formats it knows only
// which part of a line names a session, and the panel reads the rest
internal class LinuxVpnCollector(private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = mapOf(
        "vpn/collector" to HostTable(
            COLLECTOR, ::state, settings = true, slow = true, commands = listOf("install", "remove"), run = ::command,
            present = { tools().let { "wg" in it || "awg" in it || "openvpn" in it } },
        ),
    )

    // whether the script is there, what runs it and how many bytes the log holds
    private suspend fun state(): List<Item> {
        val lines = root.masked(STATE).lines()
        return listOf(
            Item(
                "collector",
                linkedMapOf("installed" to "${lines.getOrNull(0) == "yes"}", "runner" to lines.getOrElse(1) { "" }, "size" to lines.getOrElse(2) { "" }, "file" to LOG),
            ),
        )
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        when (command) {
            "install" -> install()
            "remove" -> remove(arguments["journal"] in ValueType.Flag.on)
            else -> throw DeviceException(ErrorCategory.MissingItem, "the collector has no command $command")
        }
        return emptyList()
    }

    // the timer of systemd runs the script every 30 s; cron counts in minutes, so its line runs it twice
    private suspend fun install() {
        val systemd = "systemctl" in tools()
        if (!systemd && root.exec("[ -d /etc/crontabs ]").exitStatus != 0) {
            throw DeviceException(ErrorCategory.Unsupported, "the host has neither systemd nor the crontabs of cron for the collector")
        }
        root.write(SCRIPT, COLLECTOR_SCRIPT, "sh -n ${quote(SCRIPT)}", "700")
        if (systemd) {
            root.write("$UNIT.service", SERVICE_UNIT, "true", "644")
            root.write("$UNIT.timer", TIMER_UNIT, "true", "644")
            root.masked("systemctl daemon-reload && systemctl enable --now $MARK.timer")
        } else {
            root.masked("f=/etc/crontabs/root; touch \"\$f\" && { grep -q $MARK \"\$f\" || echo ${quote(CRON)} >> \"\$f\"; } && { rc-update add crond default; rc-service crond start; } >/dev/null 2>&1; true")
        }
    }

    // the log stays unless it is asked to go too
    private suspend fun remove(journal: Boolean) {
        root.masked(
            "{ systemctl disable --now $MARK.timer; rm -f $UNIT.service $UNIT.timer; systemctl daemon-reload; } >/dev/null 2>&1; " +
                "[ ! -f /etc/crontabs/root ] || sed -i '/$MARK/d' /etc/crontabs/root; rm -f $SCRIPT $DIR/vpn-live $DIR/vpn-live.new $DIR/vpn-counted" +
                if (journal) " $LOG $LOG.1 $LOG.2" else "",
        )
    }
}

// the sessions of the log, newest first: a start, the counters of its last count and an end by the key of each. A session of WireGuard
// or AmneziaWG carried the difference of its counters, which wg keeps since the peer came up, and began with the handshake its start
// shows; one of OpenVPN carried what its status file counted for it and began at the time the file gives; an IKE SA began as long before
// its start as it had been established and carried the bytes of its child SAs, which a rekeying of them starts again. The peers go by
// their names
internal fun parseCollected(text: String, offset: String, names: Map<String, String>): List<Item> {
    class Event(val time: Long, val kind: String, val key: String, val fields: List<String>)
    class Seen(val key: String, val first: List<String>?, var last: List<String>, val since: Long) {
        var at = since
        var end: Long? = null
    }
    val events = text.lines().mapNotNull { line ->
        val entry = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return@mapNotNull null
        val time = (entry["t"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val key = (entry["k"] as? JsonPrimitive)?.contentOrNull?.let(::decoded) ?: return@mapNotNull null
        val raw = (entry["r"] as? JsonPrimitive)?.contentOrNull?.let(::decoded).orEmpty()
        val fields = when {
            key.startsWith("ipsec/") -> listOf(raw)
            key.startsWith("wireguard/") || key.startsWith("amneziawg/") -> raw.split(TAB)
            else -> raw.split(FIELDS)
        }
        Event(time, (entry["e"] as? JsonPrimitive)?.contentOrNull.orEmpty(), key, fields)
    }.sortedBy { it.time }
    val open = linkedMapOf<String, Seen>()
    val done = mutableListOf<Seen>()
    for (event in events) {
        when (event.kind) {
            "start" -> {
                open.remove(event.key)?.let { done += it.apply { end = at } }
                open[event.key] = Seen(event.key, event.fields, event.fields, event.time)
            }
            "count" -> open.getOrPut(event.key) { Seen(event.key, null, event.fields, event.time) }.apply {
                last = event.fields
                at = event.time
            }
            "end" -> done += (open.remove(event.key) ?: Seen(event.key, null, event.fields, event.time)).apply {
                last = event.fields
                end = event.time
            }
        }
    }
    return (done + open.values).map { seen ->
        val service = seen.key.split('/').getOrElse(1) { "" }
        val last = seen.last
        // kind, user, endpoint, address, received and sent
        val shown: List<String>
        val start: Long
        when {
            seen.key.startsWith("ipsec/") -> {
                val sa = parseIkeSas(last.first()).firstOrNull()
                start = seen.since - (seen.first?.let { parseIkeSas(it.first()).firstOrNull() }?.values?.get("established")?.toLongOrNull() ?: 0L)
                shown = listOf(sa?.kind ?: "IKEv2", sa?.user.orEmpty(), sa?.endpoint.orEmpty(), sa?.address.orEmpty(), sa?.bytes("in").orEmpty(), sa?.bytes("out").orEmpty())
            }
            seen.key.startsWith("wireguard/") || seen.key.startsWith("amneziawg/") -> {
                val kind = seen.key.substringBefore('/')
                start = seen.first?.getOrNull(5)?.toLongOrNull()?.takeIf { it > 0 } ?: seen.since
                fun delta(at: Int) = seen.first?.getOrNull(at)?.toLongOrNull()?.let { from -> last.getOrNull(at)?.toLongOrNull()?.takeIf { it >= from }?.minus(from) }?.toString().orEmpty()
                shown = listOf(
                    if (kind == "amneziawg") AMNEZIA.title else WIREGUARD.title, names[seen.key]?.ifEmpty { null } ?: seen.key.removePrefix("$kind/$service/").take(8),
                    last.getOrElse(3) { "" }.takeIf { it != "(none)" }.orEmpty(),
                    last.getOrElse(4) { "" }.split(',').filter { it.endsWith("/32") || it.endsWith("/128") }.joinToString(", "), delta(6), delta(7),
                )
            }
            else -> {
                start = last.getOrNull(8)?.toLongOrNull()?.takeIf { it > 0 } ?: seen.since
                shown = listOf("OpenVPN", last.getOrElse(1) { "" }, last.getOrElse(2) { "" }, last.getOrElse(3) { "" }, last.getOrElse(5) { "" }, last.getOrElse(6) { "" })
            }
        }
        val end = seen.end
        Item(
            "log/${seen.key}/$start",
            linkedMapOf(
                "kind" to shown[0],
                "service" to service,
                "user" to shown[1],
                "endpoint" to shown[2],
                "address" to shown[3],
                "connected" to local(start, offset),
                "disconnected" to end?.let { local(it, offset) }.orEmpty(),
                "duration" to end?.let { duration((it - start).coerceAtLeast(0)) }.orEmpty(),
                "reason" to "",
                "rx" to shown[4],
                "tx" to shown[5],
                "start" to "$start",
                "seconds" to end?.let { "${(it - start).coerceAtLeast(0)}" }.orEmpty(),
            ),
        )
    }.sortedByDescending { it["start"]?.toLongOrNull() ?: 0L }
}

// a journal knows when and why a session ended, the log of the host what it carried: a session in both takes its bytes from the log,
// which shows it no more. charon names the address of a client without its port
internal fun mergeHistory(journal: List<Item>, collected: List<Item>): List<Item> {
    val used = mutableSetOf<String>()
    val merged = journal.map { row ->
        val start = row["start"]?.toLongOrNull() ?: 0L
        val endpoint = row["endpoint"].orEmpty()
        val twin = collected.firstOrNull { other ->
            val at = other["endpoint"].orEmpty()
            other.id !in used && listOf("kind", "service", "user").all { other[it] == row[it] } &&
                (at == endpoint || at.startsWith("$endpoint:") || at.startsWith("[$endpoint]:")) && abs((other["start"]?.toLongOrNull() ?: 0L) - start) <= TWINS
        }
        if (twin == null) row else Item(row.id, row.values + mapOf("rx" to twin["rx"].orEmpty(), "tx" to twin["tx"].orEmpty())).also { used += twin.id }
    }
    return (merged + collected.filter { it.id !in used }).sortedByDescending { it["start"]?.toLongOrNull() ?: 0L }
}

// the log, its older files first, with no more of the counts of a session than its last
internal const val COLLECTED = "cd /var/lib/networkhelm 2>/dev/null && cat vpn-sessions.jsonl.2 vpn-sessions.jsonl.1 vpn-sessions.jsonl 2>/dev/null | " +
    "awk '/\"e\":\"count\"/ { k = \$0; sub(/.*\"k\":\"/, \"\", k); sub(/\".*/, \"\", k); c[k] = \$0; next } { print } END { for (k in c) print c[k] }'; true"

// the key and the line in the log, %, \, " and tabs written as %25, %5C, %22 and %09, which needs no escapes awk handles differently
private fun decoded(text: String) = ESCAPED.replace(text) { CODES.getValue(it.groupValues[1]) }

private const val DIR = "/var/lib/networkhelm"
private const val LOG = "$DIR/vpn-sessions.jsonl"
private const val SCRIPT = "/usr/local/lib/networkhelm/vpn-collector.sh"
private const val MARK = "networkhelm-vpn-collector"
private const val UNIT = "/etc/systemd/system/$MARK"
private const val CRON = "* * * * * /bin/sh $SCRIPT; sleep 30; /bin/sh $SCRIPT # $MARK"
private const val TWINS = 120

private const val SERVICE_UNIT = "[Unit]\nDescription=NetworkHelm log of the VPN sessions\n\n[Service]\nType=oneshot\nExecStart=/bin/sh $SCRIPT\nLogLevelMax=notice\n"
private const val TIMER_UNIT = "[Unit]\nDescription=NetworkHelm log of the VPN sessions every 30 s\n\n[Timer]\nOnBootSec=30s\nOnUnitActiveSec=30s\nAccuracySec=5s\n\n" +
    "[Install]\nWantedBy=timers.target\n"

private const val STATE = "[ -f $SCRIPT ] && echo yes || echo no; if systemctl is-enabled -q $MARK.timer 2>/dev/null; then echo systemd; " +
    "elif grep -qs $MARK /etc/crontabs/root; then echo cron; else echo none; fi; s=0; for f in $LOG $LOG.1 $LOG.2; do [ ! -f \"\$f\" ] || s=\$((s + \$(wc -c < \"\$f\"))); done; echo \"\$s\""

private val TAB = Regex("\t")
private val FIELDS = Regex("[,\t]")
private val ESCAPED = Regex("%(25|5C|22|09)")
private val CODES = mapOf("25" to "%", "5C" to "\\", "22" to "\"", "09" to "\t")
private val COLLECTOR = listOf("installed", "runner", "size", "file")

// a line for each live session, by its key: wireguard/<interface>/<key> and amneziawg/<interface>/<key> for a peer whose handshake is
// under 180 s old, its preshared key masked, openvpn/<server>/<name>/<address> for a line of CLIENT_LIST of the status file of a server
// and ipsec/<connection>/<id> for an IKE SA of strongSwan; a session is new where its key was not there the run before and over where
// it is gone. The log goes to its next file at 5 MiB and keeps three
private val COLLECTOR_SCRIPT = $$"""#!/bin/sh
# NetworkHelm: the VPN sessions of this host, every 30 s, as lines of JSON in /var/lib/networkhelm/vpn-sessions.jsonl
umask 077
d=/var/lib/networkhelm
f=$d/vpn-sessions.jsonl
mkdir -p "$d" || exit 1
now=$(date +%s)
a() { awk -v k="$1" 'tolower($1) == k { print $2; exit }' "$2"; }
{
for t in wg awg; do
$t show all dump 2>/dev/null | awk -F '\t' -v OFS='\t' -v now="$now" -v t="$t" 'NF == 9 && $6 > 0 && now - $6 < 180 { if ($3 != "(none)") $3 = "(set)"; k = (t == "wg" ? "wireguard/" : "amneziawg/") $1 "/" $2; print k, $0 }'
done
for c in /etc/openvpn/server/*.conf /etc/openvpn/*.conf; do
[ -f "$c" ] || continue
n=${c##*/}; n=${n%.conf}
p=$(a status "$c"); case "$p" in ""|/*) ;; *) p=${c%/*}/$p;; esac
case "$c" in /etc/openvpn/server/*) q=/run/openvpn-server/status-$n.log;; *) q=/run/openvpn/$n.status;; esac
for s in "$p" "$q"; do [ -n "$s" ] && [ -f "$s" ] && { awk -F '[,\t]' -v n="$n" '$1 == "CLIENT_LIST" { print "openvpn/" n "/" $2 "/" $3 "\t" $0 }' "$s"; break; }; done
done
swanctl --list-sas --raw 2>/dev/null | awk '/^list-sa event \{/ { n = $0; sub(/^list-sa event \{/, "", n); sub(/ .*/, "", n); if (match($0, /uniqueid=[0-9]+/)) print "ipsec/" n "/" substr($0, RSTART + 9, RLENGTH - 9) "\t" $0 }'
} > "$d/vpn-live.new"
m=0
[ $((now - $(cat "$d/vpn-counted" 2>/dev/null || echo 0))) -lt 300 ] || { m=1; echo "$now" > "$d/vpn-counted"; }
touch "$d/vpn-live"
awk -v now="$now" -v count="$m" -v old="$d/vpn-live" '
function e(s) { gsub(/%/, "%25", s); gsub(/\\/, "%5C", s); gsub(/"/, "%22", s); gsub(/\t/, "%09", s); return s }
function put(kind, k, r) { printf "{\"t\":%s,\"e\":\"%s\",\"k\":\"%s\",\"r\":\"%s\"}\n", now, kind, e(k), e(r) }
{ k = $0; sub(/\t.*/, "", k); r = substr($0, length(k) + 2) }
FILENAME == old { was[k] = r; next }
{ seen[k] = 1; if (!(k in was)) put("start", k, r); else if (count) put("count", k, r) }
END { for (k in was) if (!(k in seen)) put("end", k, was[k]) }
' "$d/vpn-live" "$d/vpn-live.new" >> "$f"
mv -f "$d/vpn-live.new" "$d/vpn-live"
[ $(($(wc -c < "$f"))) -le 5242880 ] || { mv -f "$f.1" "$f.2" 2>/dev/null; mv -f "$f" "$f.1"; }
"""
