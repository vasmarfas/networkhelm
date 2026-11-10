package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField

private const val SAMPLE = 50
private const val RARE = 5
private const val DAY = 86_400L
private val CLOCK = Regex("\\d\\d:\\d\\d:\\d\\d")
private val SPELLED = Regex("(\\d+w)?(\\d+d)?(\\d+h)?(\\d+m)?(\\d+s)?")

// a console menu that grows to tens of thousands of rows and its window of the WinBox port, which a subscription or getall reads
// page by page, without a pass of find for each part and without the whole answer in the memory of the router; the fields carry the names of the console
internal class NativeMirror(
    val spec: PackNative,
    // values that hold still between two reads, compared with the console as they are; the rest move and are compared by their shape
    val fixed: Set<String>,
    // counters only grow, and the console prints its sample after the window is read
    val counters: Set<String> = emptySet(),
    val durations: Set<String> = emptySet(),
    val dates: Set<String> = emptySet(),
    // moments on the clock of the uptime of the router, shown as the time left until them
    val ages: Set<String> = emptySet(),
    val names: Map<String, Map<String, String>> = emptyMap(),
    // fields the console prints even when they are empty
    val blanks: Set<String> = emptySet(),
    // whether the console prints the row: the window holds some the menu does not show
    val shown: (Map<String, String>) -> Boolean = { true },
) {
    // the row as the console prints it: a duration under a day as hh:mm:ss and longer as 1d2h3m4s, no empty string;
    // the router keeps a date in its own time, so it is written as it is
    fun row(item: Item, uptime: Long? = null): Map<String, String> = buildMap {
        put(".id", item.id)
        for ((name, value) in item.values) {
            val text = when (name) {
                in durations -> value.toLongOrNull()?.let(::interval) ?: value
                in dates -> value.toLongOrNull()?.let(::stamp) ?: value
                in ages -> uptime?.let { now -> value.toLongOrNull()?.let { interval(maxOf(0, it - now)) } } ?: value
                else -> names[name]?.get(value) ?: value
            }
            if (text.isNotEmpty() || name in blanks) put(name, text)
        }
    }

    // a few of the rows the console hides, of each name of a named field, to see that it does hide them
    fun unseen(hidden: List<Map<String, String>>): List<String> =
        hidden.groupBy { row -> names.keys.map { row[it] } }.values.flatMap { rows -> rows.take(RARE).map { it.getValue(".id") } }

    // the first rows, a few of the rows with a field the first ones lack, and a few with each name of a named field
    fun sample(rows: List<Map<String, String>>): List<String> {
        val chosen = LinkedHashSet<String>()
        val first = rows.take(SAMPLE)
        first.forEach { chosen += it.getValue(".id") }
        val seen = first.flatMapTo(HashSet()) { it.keys }
        val rare = rows.flatMapTo(LinkedHashSet()) { it.keys } - seen
        for (name in rare) rows.asSequence().filter { name in it }.take(RARE).forEach { chosen += it.getValue(".id") }
        for (name in names.keys) {
            for (value in rows.mapNotNullTo(LinkedHashSet()) { it[name] }) rows.asSequence().filter { it[name] == value }.take(RARE).forEach { chosen += it.getValue(".id") }
        }
        return chosen.toList()
    }

    // every row the console gives has the same values, and of the same shape where they move
    fun agrees(mirrored: Map<String, Map<String, String>>, console: List<Map<String, String>>): Boolean = console.isNotEmpty() && console.all { row ->
        val native = mirrored[row[".id"]] ?: return@all false
        native.keys == row.keys && row.all { (name, value) ->
            when (name) {
                ".id", in fixed -> native[name] == value
                in counters -> grew(native.getValue(name), value)
                else -> shape(native.getValue(name)) == shape(value)
            }
        }
    }
}

private fun grew(before: String, after: String): Boolean {
    val first = before.toULongOrNull() ?: return false
    val second = after.toULongOrNull() ?: return false
    return first <= second
}

internal fun shape(value: String) = when {
    value == "true" || value == "false" -> "flag"
    value.all(Char::isDigit) -> "number"
    CLOCK.matches(value) || SPELLED.matches(value) -> "duration"
    else -> "text"
}

private fun two(number: Long) = number.toString().padStart(2, '0')

private fun interval(seconds: Long) = if (seconds == 0L) "0s" else if (seconds < DAY) "${two(seconds / 3_600)}:${two(seconds % 3_600 / 60)}:${two(seconds % 60)}" else duration(seconds)

// seconds since 1970 as the date and time the console of RouterOS 7.10 and later prints
internal fun stamp(seconds: Long): String {
    val days = seconds.floorDiv(DAY)
    val shifted = days + 719_468
    val era = shifted.floorDiv(146_097)
    val dayOfEra = shifted - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val month = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * month + 2) / 5 + 1
    val calendarMonth = if (month < 10) month + 3 else month - 9
    val year = yearOfEra + era * 400 + if (calendarMonth <= 2) 1 else 0
    val time = seconds - days * DAY
    return "$year-${two(calendarMonth)}-${two(day)} ${two(time / 3_600)}:${two(time % 3_600 / 60)}:${two(time % 60)}"
}

private fun field(name: String, key: Int, kind: String, letter: String) = PackNativeField(name, key, kind, letter = letter)

private fun number(name: String, key: Int) = field(name, key, "number", "u")

private fun counter(name: String, key: Int) = field(name, key, "number", "q")

private fun flag(name: String, key: Int) = field(name, key, "bool", "b")

private fun text(name: String, key: Int) = field(name, key, "string", "s")

private fun address(name: String, key: Int) = field(name, key, "ipaddr", "u")

private fun pair(name: String, vararg parts: PackNativeField) = PackNativeField(name, NATIVE_NO_KEY, "tuple", parts = parts.toList())

// the uptime of the router in seconds, the clock the moments of a window are counted on
internal val UPTIME = PackNative(listOf(24, 2), listOf(number("uptime", 0x1)), single = true)

// the time on the clock of the router, in seconds since 1970 counted in its own zone
internal val ROUTER_CLOCK = PackNative(listOf(24, 0), listOf(number("now", 0x7)), single = true)

// the numbers of the protocols as the console names them
private val PROTOCOLS = mapOf(
    1 to "icmp", 2 to "igmp", 3 to "ggp", 4 to "ip-encap", 5 to "st", 6 to "tcp", 8 to "egp", 12 to "pup", 17 to "udp", 20 to "hmp", 22 to "xns-idp",
    27 to "rdp", 29 to "iso-tp4", 33 to "dccp", 36 to "xtp", 37 to "ddp", 38 to "idpr-cmtp", 41 to "ipv6-encap", 43 to "ipv6-route", 44 to "ipv6-frag",
    46 to "rsvp", 47 to "gre", 50 to "ipsec-esp", 51 to "ipsec-ah", 58 to "icmpv6", 59 to "ipv6-nonxt", 60 to "ipv6-opts", 73 to "rspf", 81 to "vmtp",
    89 to "ospf", 94 to "ipip", 97 to "etherip", 98 to "encap", 103 to "pim", 112 to "vrrp", 115 to "l2tp", 132 to "sctp", 136 to "udp-lite",
).mapKeys { it.key.toString() }

private val DNS_TYPES = mapOf(
    1 to "A", 2 to "NS", 3 to "MD", 4 to "MF", 5 to "CNAME", 6 to "SOA", 7 to "MB", 8 to "MG", 9 to "MR", 10 to "NULL", 11 to "WKS", 12 to "PTR",
    13 to "HINFO", 14 to "MINFO", 15 to "MX", 16 to "TXT", 28 to "AAAA", 33 to "SRV",
).mapKeys { it.key.toString() }

// the console prints these records of the cache; reverse, SOA and negative ones are only in its view of all
private val DNS_SHOWN = setOf("A", "NS", "CNAME", "MX", "TXT", "AAAA", "SRV")

private val TCP_STATES = listOf("", "syn-sent", "syn-recv", "established", "fin-wait", "close-wait", "last-ack", "time-wait", "close", "listen")
    .withIndex().associate { (at, name) -> "$at" to name }

// the ids are those of the descriptions RouterOS 7.25 and 7.26 give for the windows Address Lists, Connections and DNS Cache; the console checks each before it is used
internal val nativeMirrors: Map<String, NativeMirror> = mapOf(
    "ip/firewall/address-list" to NativeMirror(
        PackNative(
            listOf(20, 35),
            whole = true,
            fields = listOf(
                text("list", 0xFE0010),
                PackNativeField(
                    "address", 0x03, "union",
                    members = listOf(PackNativeField("address", 0x03, "network", letter = "u", pair = 0x04, range = true), text("address", 0x08)),
                ),
                number("timeout", 0x05),
                number("creation-time", 0x0A),
                flag("dynamic", 0xFE0007),
                flag("disabled", 0xFE000A),
                text("comment", 0xFE0009),
            ),
        ),
        fixed = setOf("list", "address", "creation-time", "dynamic", "disabled", "comment"),
        durations = setOf("timeout"),
        dates = setOf("creation-time"),
    ),
    "ip/firewall/connection" to NativeMirror(
        PackNative(
            listOf(20, 32),
            whole = true,
            fields = listOf(
                number("protocol", 0x01),
                flag("expected", 0x02),
                flag("seen-reply", 0x03),
                flag("assured", 0x04),
                flag("confirmed", 0x05),
                flag("dying", 0x06),
                address("src-address", 0x07),
                address("dst-address", 0x08),
                number("src-port", 0x09),
                number("dst-port", 0x0A),
                address("reply-src-address", 0x0B),
                address("reply-dst-address", 0x0C),
                number("reply-src-port", 0x0D),
                number("reply-dst-port", 0x0E),
                number("tcp-state", 0x0F),
                number("icmp-type", 0x10),
                number("icmp-code", 0x11),
                number("icmp-id", 0x12),
                number("gre-key", 0x15),
                text("connection-type", 0x16),
                number("timeout", 0x17),
                text("connection-mark", 0x18),
                flag("fasttrack", 0x1E),
                flag("hw-offload", 0x1F),
                counter("orig-packets", 0x1F),
                counter("orig-bytes", 0x20),
                counter("orig-fasttrack-packets", 0x21),
                counter("orig-fasttrack-bytes", 0x22),
                counter("repl-packets", 0x23),
                counter("repl-bytes", 0x24),
                counter("repl-fasttrack-packets", 0x25),
                counter("repl-fasttrack-bytes", 0x26),
                flag("srcnat", 0x27),
                flag("dstnat", 0x28),
                counter("orig-rate", 0x29),
                counter("repl-rate", 0x2A),
                flag("uses-helper", 0x2B),
            ),
        ),
        fixed = setOf(
            "protocol", "src-address", "dst-address", "src-port", "dst-port", "reply-src-address", "reply-dst-address", "reply-src-port", "reply-dst-port",
            "icmp-type", "icmp-code", "icmp-id", "gre-key", "connection-type", "connection-mark",
        ),
        counters = setOf(
            "orig-packets", "orig-bytes", "orig-fasttrack-packets", "orig-fasttrack-bytes", "repl-packets", "repl-bytes", "repl-fasttrack-packets", "repl-fasttrack-bytes",
        ),
        durations = setOf("timeout"),
        names = mapOf("protocol" to PROTOCOLS, "tcp-state" to TCP_STATES),
    ),
    "ip/dns/cache" to NativeMirror(
        PackNative(
            listOf(14, 1),
            whole = true,
            fields = listOf(
                text("name", 0x01),
                number("type", 0x02),
                PackNativeField(
                    "data", 0x05, "union",
                    members = listOf(
                        address("data", 0x05),
                        field("data", 0x13, "ip6addr", "a"),
                        text("data", 0x06),
                        pair("data", number("data", 0x09), text("data", 0x0A)),
                        pair("data", number("data", 0x1B), text("data", 0x1C)),
                    ),
                ),
                number("ttl", 0x04),
                flag("static", 0x03),
                flag("negative", 0x14),
            ),
        ),
        fixed = setOf("name", "type", "data", "static"),
        ages = setOf("ttl"),
        names = mapOf("type" to DNS_TYPES),
        blanks = setOf("name"),
        shown = { it["type"] in DNS_SHOWN && it["negative"] != "true" },
    ),
)
