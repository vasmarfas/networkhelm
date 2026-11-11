package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.PackNativeField

private const val WORD = 0xFFFFFFFFL
private const val DAY_SECONDS = 86_400L
private const val KIBI = 1024L

private val yes = setOf("true", "yes", "on", "1", "enabled")
private val no = setOf("false", "no", "off", "0", "disabled")

// the fields of a request that a value typed for a native field stands for: the inverse of written(), and a flag of presence or of negation beside it
internal fun encoded(field: PackNativeField, text: String?, resolved: Map<Long, String>?): List<Pair<Int, M2Value>> {
    if (!field.writable) throw rejected(field, "is read-only")
    val fields = ArrayList<Pair<Int, M2Value>>()
    var body = text?.trim()
    field.negation?.let { key ->
        val negated = body?.startsWith("!") == true
        fields += key to M2Value.Bool(negated)
        if (negated) body = body.drop(1).trim()
    }
    field.flag?.let { key ->
        fields += key to M2Value.Bool(!body.isNullOrEmpty())
        if (body.isNullOrEmpty()) return fields
    }
    val typed = body ?: return fields + cleared(field)
    if (field.kind == "union") fields += member(field, typed, resolved) else fields += valued(field, typed, resolved)
    return fields
}

// a value taken off goes out as the empty value of its type; the router drops it only when the key with that type comes in 0xFF0014 beside it
internal fun cleared(field: PackNativeField): List<Pair<Int, M2Value>> {
    if (field.kind == "union") return field.members.flatMap { cleared(it) }
    val empty = when (field.letter) {
        "b" -> M2Value.Bool(false)
        "u" -> M2Value.U32(0)
        "q" -> M2Value.U64(0)
        "a" -> M2Value.Address6(ByteArray(IPV6_BITS / 8))
        "s" -> M2Value.Text("")
        "m" -> M2Value.Message(M2Message(emptyList()))
        "r" -> M2Value.Raw(ByteArray(0))
        "B" -> M2Value.Bools(BooleanArray(0))
        "U" -> M2Value.U32s(LongArray(0))
        "Q" -> M2Value.U64s(LongArray(0))
        "A" -> M2Value.Address6s(emptyList())
        "S" -> M2Value.Texts(emptyList())
        "M" -> M2Value.Messages(emptyList())
        "R" -> M2Value.Raws(emptyList())
        else -> throw rejected(field, "cannot be taken off")
    }
    val second = field.pair ?: return listOf(field.key to empty)
    return listOf(field.key to empty, second to if (field.kind == "network6") M2Value.U32(0) else empty)
}

// a value with a second number beside it, the mask of a network or the top of a range, takes two keys of the request
private fun valued(field: PackNativeField, text: String, resolved: Map<Long, String>?): List<Pair<Int, M2Value>> {
    val second = field.pair
    return when {
        field.kind == "multi" -> listOf(field.key to M2Value.Messages(listed(text).map { element -> M2Message(encoded(field.parts.single(), element, resolved).map { M2Field(it.first, it.second) }) }))
        second == null -> listOf(field.key to scalar(field, text, resolved))
        field.kind == "macnetwork" -> listOf(
            field.key to M2Value.Raw(macOf(field, text.substringBefore('/'))),
            second to M2Value.Raw(if ('/' in text) macOf(field, text.substringAfter('/')) else ByteArray(6) { -1 }),
        )
        field.kind == "numberrange" -> bounds(field, text).let { (low, high) -> listOf(field.key to wire(field, low), second to wire(field, high)) }
        field.kind == "network" -> network(field, text).let { (address, other) -> listOf(field.key to M2Value.U32(address), second to M2Value.U32(other)) }
        field.kind == "network6" -> listOf(
            field.key to M2Value.Address6(ipv6Of(field, text.substringBefore('/'))),
            second to M2Value.U32(prefixOf(field, text.substringAfter('/', IPV6_BITS.toString()), IPV6_BITS)),
        )
        else -> listOf(field.key to scalar(field, text, resolved))
    }
}

// the member of a union that takes the text: a name of its list, or else a number of its own kind
private fun member(field: PackNativeField, text: String, resolved: Map<Long, String>?): Pair<Int, M2Value> {
    for (member in field.members) {
        try {
            return member.key to scalar(member, text, resolved)
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.ArgumentValue) throw e
        }
    }
    throw rejected(field, "does not take \"$text\"")
}

private fun scalar(field: PackNativeField, text: String, resolved: Map<Long, String>?): M2Value = when (field.kind) {
    "string", "script" -> M2Value.Text(text)
    "bool", "flag" -> flag(field, text)
    "macaddr" -> M2Value.Raw(macOf(field, text))
    "ip6addr" -> M2Value.Address6(ipv6Of(field, text))
    "multistring" -> M2Value.Texts(listed(text))
    "multiip6addr" -> M2Value.Address6s(listed(text).map { ipv6Of(field, it) })
    "multiipaddr" -> M2Value.U32s(listed(text).map { dottedOf(field, it) }.toLongArray())
    "multinumber" -> M2Value.U32s(listed(text).map { number(field, it, resolved) }.toLongArray())
    "addr" -> M2Value.Message(M2Message(addressOf(field, text, resolved).map { M2Field(it.first, it.second) }))
    else -> wire(field, numberOf(field, text, resolved))
}

private fun listed(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun flag(field: PackNativeField, text: String): M2Value {
    val on = when (text.lowercase()) {
        in yes -> true
        in no -> false
        else -> throw rejected(field, "takes yes or no, not \"$text\"")
    }
    return if (field.letter == "u") M2Value.U32(if (on) 1 else 0) else M2Value.Bool(on)
}

// the letter of the id in the description says which type goes on the wire
private fun wire(field: PackNativeField, number: Long): M2Value = when (field.letter) {
    "q" -> M2Value.U64(number)
    "b" -> M2Value.Bool(number != 0L)
    else -> M2Value.U32(number and WORD)
}

private fun numberOf(field: PackNativeField, text: String, resolved: Map<Long, String>?): Long {
    // a name of the list of the field stands for its number whatever the kind: idle-timeout of Hotspot reads none for 0xFFFFFFFF
    val number = named(field, text, resolved)?.takeIf { field.kind != "set" } ?: when (field.kind) {
        "enm" -> named(field, text, resolved) ?: number(field, text.removeSuffix(field.postfix.orEmpty()), resolved)
        "set" -> bits(field, text)
        "interval", "age" -> {
            val scale = field.scale?.takeIf { it > 1 } ?: 1
            val steps = interval(field, text) * scale
            if (steps % 1_000 != 0L) throw rejected(field, "does not take \"$text\": its step is ${1_000 / scale}ms")
            steps / 1_000
        }
        "clocktime" -> clock(field, text)
        "clockdate" -> epochDay(field, text.take(10)) * DAY_SECONDS
        "dateandtime" -> dateTime(field, text)
        "timezone" -> zone(field, text)
        "ipaddr", "netmask" -> dottedOf(field, text)
        "kbytes" -> number(field, text, resolved) / KIBI
        "fixedpoint", "decimal", "bigdecimal" -> scaled(field, text.removeSuffix(field.postfix.orEmpty()))
        "unit", "bigunit" -> prefixed(field, text.trim().removeSuffix(field.postfix.orEmpty()).trim())
        else -> number(field, text.removeSuffix(field.postfix.orEmpty()).trim(), resolved)
    }
    val signed = field.kind == "integer" || field.kind == "timezone"
    if (!signed && number < 0 || field.letter != "q" && number !in (if (signed) Int.MIN_VALUE.toLong() else 0L)..WORD) throw rejected(field, "does not take $number: out of range")
    return number
}

private fun number(field: PackNativeField, text: String, resolved: Map<Long, String>?): Long {
    val digits = text.trim()
    return (if (digits.startsWith("0x")) digits.drop(2).toLongOrNull(16) else digits.toLongOrNull())
        ?: named(field, digits, resolved)
        ?: throw rejected(field, "takes a number, not \"$text\"")
}

// a name of the list the field offers stands for its number, in the static map of the node or in the table the node refers to
private fun named(field: PackNativeField, text: String, resolved: Map<Long, String>?): Long? =
    (field.names.entries + resolved?.entries.orEmpty()).firstOrNull { it.value.equals(text, ignoreCase = true) }?.key

private fun bits(field: PackNativeField, text: String): Long = listed(text).fold(0L) { mask, word ->
    val bit = named(field, word, null) ?: word.toLongOrNull() ?: throw rejected(field, "has no flag \"$word\"")
    if (bit !in 0..31) throw rejected(field, "has no flag \"$word\"")
    mask or (1L shl bit.toInt())
}

private val routerOs = Regex("""(?:(\d+)w)?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m(?!s))?(?:(\d+)s)?(?:(\d+)ms)?""")
private val winBox = Regex("""(?:(\d+)d\s*)?(\d+):(\d{2}):(\d{2})""")

// 1w2d3h4m5s6ms as the console writes an interval, or 1d 02:03:04 as WinBox does, or seconds; counted in milliseconds
private fun interval(field: PackNativeField, text: String): Long {
    val word = text.trim()
    word.toLongOrNull()?.let { return it * 1_000 }
    winBox.matchEntire(word)?.destructured?.let { (days, hours, minutes, seconds) ->
        return ((days.toLongOrNull() ?: 0) * DAY_SECONDS + hours.toLong() * 3_600 + minutes.toLong() * 60 + seconds.toLong()) * 1_000
    }
    val parts = routerOs.matchEntire(word)?.takeIf { word.isNotEmpty() }?.destructured?.toList()?.map { it.toLongOrNull() ?: 0L }
        ?: throw rejected(field, "takes a time like 1d 02:03:04 or 1w2d3h, not \"$text\"")
    return (parts[0] * 604_800 + parts[1] * DAY_SECONDS + parts[2] * 3_600 + parts[3] * 60 + parts[4]) * 1_000 + parts[5]
}

private fun clock(field: PackNativeField, text: String): Long {
    val parts = text.trim().split(':')
    val numbers = parts.map { it.toLongOrNull() ?: throw rejected(field, "takes a time like 12:30:00, not \"$text\"") }
    if (numbers.size !in 2..3 || numbers[0] !in 0..23 || numbers[1] !in 0..59 || numbers.getOrElse(2) { 0 } !in 0..59) throw rejected(field, "takes a time like 12:30:00, not \"$text\"")
    return numbers[0] * 3_600 + numbers[1] * 60 + numbers.getOrElse(2) { 0 }
}

private fun dateTime(field: PackNativeField, text: String): Long {
    val parts = text.trim().split(' ', limit = 2)
    return epochDay(field, parts[0]) * DAY_SECONDS + clock(field, parts.getOrElse(1) { "00:00:00" })
}

private fun epochDay(field: PackNativeField, text: String): Long {
    val parts = text.trim().split('-').map { it.toIntOrNull() ?: throw rejected(field, "takes a date like 2026-07-25, not \"$text\"") }
    if (parts.size != 3 || parts[1] !in 1..12 || parts[2] !in 1..31) throw rejected(field, "takes a date like 2026-07-25, not \"$text\"")
    val (year, month, day) = parts
    val shifted = if (month <= 2) year - 1 else year
    val era = shifted.floorDiv(400)
    val yearOfEra = shifted - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468
}

// the offset from UTC is a signed number of seconds that the wire carries unsigned
private fun zone(field: PackNativeField, text: String): Long {
    val match = Regex("""([+-])(\d{1,2}):(\d{2})""").matchEntire(text.trim()) ?: throw rejected(field, "takes an offset like +03:00, not \"$text\"")
    val seconds = match.groupValues[2].toLong() * 3_600 + match.groupValues[3].toLong() * 60
    return if (match.groupValues[1] == "-") -seconds else seconds
}

// the first byte of an address is the lowest of its number
private fun dottedOf(field: PackNativeField, text: String): Long {
    val parts = text.trim().split('.')
    val bytes = parts.map { it.toIntOrNull()?.takeIf { part -> part in 0..255 && it.length <= 3 } ?: throw rejected(field, "takes an address like 192.168.88.1, not \"$text\"") }
    if (bytes.size != 4) throw rejected(field, "takes an address like 192.168.88.1, not \"$text\"")
    return bytes.foldIndexed(0L) { at, number, part -> number or (part.toLong() shl (8 * at)) }
}

private fun macOf(field: PackNativeField, text: String): ByteArray {
    val parts = text.trim().split(':', '-')
    if (parts.size != 6) throw rejected(field, "takes an address like 00:0C:42:AA:BB:CC, not \"$text\"")
    return ByteArray(6) { at -> parts[at].takeIf { it.length in 1..2 }?.toIntOrNull(16)?.toByte() ?: throw rejected(field, "takes an address like 00:0C:42:AA:BB:CC, not \"$text\"") }
}

private fun ipv6Of(field: PackNativeField, text: String): ByteArray {
    val halves = text.trim().split("::")
    fun groups(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { group -> group.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
    val head = groups(halves[0])
    val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
    val count = (head?.size ?: 0) + (tail?.size ?: 0)
    if (halves.size > 2 || head == null || tail == null || (if (halves.size == 1) count != 8 else count > 7)) throw rejected(field, "takes an address like fe80::1, not \"$text\"")
    val all = head + List(8 - count) { 0 } + tail
    return ByteArray(16) { at -> (all[at / 2] shr (if (at % 2 == 0) 8 else 0)).toByte() }
}

// a decimal fraction has as many digits as the scale has zeros
private fun scaled(field: PackNativeField, text: String): Long {
    val scale = field.scale?.takeIf { it > 1 } ?: return text.trim().toLongOrNull() ?: throw rejected(field, "takes a number, not \"$text\"")
    val digits = scale.toString().length - 1
    val negative = text.trim().startsWith("-")
    val body = text.trim().removePrefix("-")
    val whole = body.substringBefore('.').toLongOrNull() ?: throw rejected(field, "takes a number, not \"$text\"")
    val fraction = body.substringAfter('.', "").padEnd(digits, '0')
    if (fraction.length > digits || !fraction.all(Char::isDigit)) throw rejected(field, "takes at most $digits digits after the point")
    val number = whole * scale + (fraction.toLongOrNull() ?: 0L)
    return if (negative) -number else number
}

private const val STEP_DIGITS = 9
private const val STEP_ONE = 1_000_000_000L
private const val IPV4_BITS = 32
private const val IPV6_BITS = 128

// 10M is ten million, the way a rate of the router is written: the prefixes step by a thousand
private fun prefixed(field: PackNativeField, text: String): Long {
    val step = when (text.lastOrNull()) {
        'k', 'K' -> 1_000L
        'M' -> 1_000_000L
        'G' -> 1_000_000_000L
        else -> 1L
    }
    val digits = if (step == 1L) text else text.dropLast(1)
    val fraction = digits.substringAfter('.', "")
    val whole = digits.substringBefore('.').toLongOrNull()
    if (whole == null || fraction.any { !it.isDigit() } || fraction.isNotEmpty() && step == 1L) throw rejected(field, "takes a number like 10M, not \"$text\"")
    return whole * step + fraction.padEnd(STEP_DIGITS, '0').take(STEP_DIGITS).toLong() * step / STEP_ONE
}

// from..to, or one number for both ends
private fun bounds(field: PackNativeField, text: String): Pair<Long, Long> {
    val numbers = text.split("..", "-").map { number(field, it.trim(), null) }
    return when (numbers.size) {
        1 -> numbers[0] to numbers[0]
        2 -> numbers[0] to numbers[1]
        else -> throw rejected(field, "takes a range like 1000..1500, not \"$text\"")
    }
}

private fun prefixOf(field: PackNativeField, text: String, most: Int): Long =
    text.trim().toIntOrNull()?.takeIf { it in 0..most }?.toLong() ?: throw rejected(field, "takes a prefix length up to $most, not \"$text\"")

// the address and the mask in the byte order of the address; with a range the first and the last address
private fun network(field: PackNativeField, text: String): Pair<Long, Long> {
    val body = text.trim()
    if (field.range && '-' in body) return dottedOf(field, body.substringBefore('-').trim()) to dottedOf(field, body.substringAfter('-').trim())
    val address = dottedOf(field, body.substringBefore('/'))
    val tail = body.substringAfter('/', "")
    if ('.' in tail) return address to dottedOf(field, tail)
    val prefix = if (tail.isEmpty()) IPV4_BITS else prefixOf(field, tail, IPV4_BITS).toInt()
    if (field.range) {
        val first = swapped(address) and (WORD shl (IPV4_BITS - prefix) and WORD)
        return unswapped(first) to unswapped(first + (1L shl (IPV4_BITS - prefix)) - 1)
    }
    val octet = { at: Int -> (0xFF shl (8 - (prefix - 8 * at).coerceIn(0, 8)) and 0xFF).toLong() shl (8 * at) }
    return address to (0..3).fold(0L) { mask, at -> mask or octet(at) }
}

private fun swapped(address: Long) = (0..3).fold(0L) { all, at -> (all shl 8) or ((address shr (8 * at)) and 0xFF) }

private fun unswapped(address: Long) = (0..3).fold(0L) { all, at -> all or (((address shr (8 * (3 - at))) and 0xFF) shl (8 * at)) }

// an address of a request is a message with a key for each shape of it, and the marks of the console stand behind the host: / % @
private fun addressOf(field: PackNativeField, text: String, resolved: Map<Long, String>?): List<Pair<Int, M2Value>> {
    var rest = text.trim()
    fun cut(mark: Char): String {
        val tail = rest.substringAfter(mark, "")
        rest = rest.substringBefore(mark)
        return tail
    }
    val vrf = cut('@')
    val interfaceName = cut('%')
    val length = cut('/')
    val parts = rest.split('.')
    val groups = rest.split(':')
    val fields = ArrayList<Pair<Int, M2Value>>()
    // a word that names an interface of the router is that interface, as the console takes gateway=ether1
    val named = resolved?.entries?.firstOrNull { it.value.equals(rest, ignoreCase = true) }?.key?.takeIf { interfaceName.isEmpty() }
    when {
        rest.isEmpty() && interfaceName.isNotEmpty() -> Unit
        rest.isEmpty() -> throw rejected(field, "takes an address")
        parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) } -> fields += ADDRESS_V4 to M2Value.U32(dottedOf(field, rest))
        groups.size == 6 && groups.all { it.length == 2 } -> fields += ADDRESS_MAC to M2Value.Raw(macOf(field, rest))
        groups.size > 2 -> fields += ADDRESS_V6 to M2Value.Address6(ipv6Of(field, rest))
        named != null -> fields += ADDRESS_INTERFACE to M2Value.U32(named)
        else -> fields += ADDRESS_NAME to M2Value.Text(rest)
    }
    if (length.isNotEmpty()) fields += ADDRESS_LENGTH to M2Value.U32(prefixOf(field, length, IPV6_BITS))
    if (interfaceName.isNotEmpty()) {
        val number = interfaceName.toLongOrNull() ?: resolved?.entries?.firstOrNull { it.value.equals(interfaceName, ignoreCase = true) }?.key
            ?: throw rejected(field, "has no interface \"$interfaceName\"")
        fields += ADDRESS_INTERFACE to M2Value.U32(number)
    }
    if (vrf.isNotEmpty()) fields += ADDRESS_VRF to M2Value.U32(vrf.toLongOrNull() ?: throw rejected(field, "takes a number after @, not \"$vrf\""))
    return fields
}

private fun rejected(field: PackNativeField, what: String) = DeviceException(ErrorCategory.ArgumentValue, "${field.name} $what")
