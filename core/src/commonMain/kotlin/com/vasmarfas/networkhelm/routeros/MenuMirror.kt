package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.packs.MIRROR_AGE
import com.vasmarfas.networkhelm.packs.MIRROR_SINCE
import com.vasmarfas.networkhelm.packs.MIRROR_STAMP
import com.vasmarfas.networkhelm.packs.MIRROR_TIME
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.routeros.console.days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val DAY = 86_400L
private const val TOKEN_ROWS = 5
private const val CHECKED_MOST = 50
private const val KEPT = "mirror:"
// a time the console printed between two reads of the window lies between them, give or take the second it rounds to
private const val TIME_SLACK = 2L
// a reference to nothing and a date or a moment that never came
private const val NONE = 0xFFFFFFFFL
// the way the rows are written from the window: an agreement made before it changed is checked again
private const val WRITING = 1
// the uptime and the clock of the router go on as the time here does and are read again after this long
private val TICKS_KEPT = 30.seconds
private val NAMED_KINDS = setOf("enm", "set", "bool", "flag", "numflag", "multitristate")
private val RANGE_KINDS = setOf("numberrange", "numberrangelist", "multinumberrange")
private val WORDED_KINDS = setOf("enm", "set", "numflag", "multitristate")
// what the console leaves out of a row where the window holds an empty or a zero value
private val NOTHING = setOf("", "false", "0", "00:00:00", "0s")
private val CLOCK = Regex("(\\d+):(\\d\\d):(\\d\\d)")
private val DATE = Regex("(\\d{4})-(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)")
private val SPELLED = Regex("(?:(\\d+)w)?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")

// what the console was seen to print the same as the window: each field with the shape of its value and each value of a field the router names;
// what the console prints where the record has no field, the value it leaves out of a row, its words for values the window gives another way,
// the fields whose times it spells as 9m46s where it writes others as a clock, and those whose times it gives as a number of seconds
internal class Agreement(
    val tokens: Set<String> = emptySet(),
    val fills: Map<String, String> = emptyMap(),
    val drops: Map<String, String> = emptyMap(),
    val words: Map<String, Map<String, String>> = emptyMap(),
    val spelled: Set<String> = emptySet(),
    val counted: Set<String> = emptySet(),
) {
    operator fun plus(other: Agreement) = Agreement(
        tokens + other.tokens, fills + other.fills, drops + other.drops, (words.keys + other.words.keys).associateWith { words[it].orEmpty() + other.words[it].orEmpty() },
        spelled + other.spelled, counted + other.counted,
    )
}

internal sealed interface Check {
    class Agrees(val agreement: Agreement) : Check

    class Differs(val reason: String) : Check

    // the reads had no row in common to compare
    data object Unknown : Check
}

// a console menu as its window of the WinBox port holds it, each field under the name of the console and written as the console writes it
internal class MenuMirror(val spec: PackNative, private val classic: Boolean) {
    private val fields = spec.fields.associateBy { it.name }

    val names: Set<String> = fields.keys

    // a moment on the clock of the uptime of the router is written as the time between it and now, a moment of a date as the date it came at
    val ages = spec.fields.any { it.kind == MIRROR_AGE || it.kind == MIRROR_SINCE }

    val dates = spec.fields.any { it.kind == MIRROR_SINCE }

    // an agreement made with another shape of the window does not hold for this one
    val mark: String = "$WRITING;" + spec.handler.joinToString(",") + ";" + spec.fields.joinToString(";") { "${it.name}:${it.key}:${it.kind}" }

    // the one record of a window of settings has no id, as the console prints it
    fun row(item: Item, time: RouterTime? = null): Map<String, String> = buildMap {
        put(".id", if (spec.single) "" else item.id)
        for ((name, value) in item.values) put(name, written(name, value, time))
    }

    // the row as the console was seen to print it
    fun shown(row: Map<String, String>, agreement: Agreement): Map<String, String> = buildMap {
        for ((name, value) in row) {
            if (agreement.drops[name] == value) continue
            val time = if (CLOCK.matches(value)) seconds(value) else null
            val written = when {
                time == null -> null
                name in agreement.spelled -> duration(time)
                name in agreement.counted -> time.toString()
                else -> null
            }
            put(name, written ?: agreement.words[name]?.get(value) ?: value)
        }
        for ((name, value) in agreement.fills) if (name !in this) put(name, value)
    }

    private fun written(name: String, value: String, router: RouterTime?): String {
        val field = fields[name] ?: return value
        if (field.kind == "netmask") return prefix(value) ?: value
        // the console writes a list with commas alone and a range of numbers with a dash
        if (field.kind in RANGE_KINDS) return value.replace(", ", ",").replace("..", "-")
        val number = value.toLongOrNull() ?: return if (field.kind == "multi") value.replace(", ", ",") else value
        val scale = field.scale?.takeIf { it > 1 } ?: 1
        return when {
            field.kind == MIRROR_TIME -> time(number / scale)
            // a moment still to come is the time left until it, as Expires After, one gone the time since it, as Last Seen
            field.kind == MIRROR_AGE -> if (number == NONE) "" else router?.let { time(abs(number / scale - it.uptime)) } ?: value
            field.kind == MIRROR_SINCE -> if (number == NONE) "" else router?.now?.let { now -> stamp(now - router.uptime + number / scale) } ?: value
            field.kind == MIRROR_STAMP -> if (number == NONE) "" else stamp(number)
            // a reference to nothing is no value, and one to an element that is gone is printed as the id of the element
            field.refers != null -> if (number == NONE) "" else "*" + number.toString(16).uppercase()
            else -> value
        }
    }

    // the console of RouterOS 7.17 and later serializes a time under a day as a clock and a longer one as a date of 1970, which is read as 1d2h3m4s
    private fun time(seconds: Long) = if (classic || seconds >= DAY) duration(seconds) else clock(seconds)

    // an enumeration, a name of a special value of a number and a word of the console are checked value by value, the rest by the shape of the value
    fun tokens(row: Map<String, String>, agreement: Agreement): Set<String> = row.mapNotNullTo(HashSet()) { (name, value) ->
        val field = fields[name] ?: return@mapNotNullTo null
        val named = field.kind in NAMED_KINDS || value in field.names.values || agreement.words[name]?.values?.contains(value) == true
        if (named) "$name=$value" else "$name:${shape(value)}"
    }

    // a few rows for each token the agreement lacks
    fun sample(rows: List<Map<String, String>>, agreement: Agreement): List<String> {
        val chosen = LinkedHashSet<String>()
        val counts = HashMap<String, Int>()
        for (row in rows) {
            val fresh = tokens(row, agreement) - agreement.tokens
            if (fresh.any { (counts[it] ?: 0) < TOKEN_ROWS } && chosen.size < CHECKED_MOST) {
                chosen += row.getValue(".id")
                fresh.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        return chosen.toList()
    }

    // a value the window names its own way: a name of an enumeration, a name the window gives as its number, a word the console puts for a time
    // as auto; two times are no names of each other
    private fun worded(name: String, held: String, printed: String): Boolean {
        val field = fields.getValue(name)
        if (seconds(printed) != null || moment(printed) != null) return false
        // nothing the window holds is a word too, never for Last Seen
        if (seconds(held) != null || moment(held) != null || held.isEmpty()) return printed.none(Char::isDigit)
        return field.kind in WORDED_KINDS || field.names.isNotEmpty() || held.toLongOrNull() != null && printed.toLongOrNull() == null
    }

    // the console rows printed between two reads of the window: the rows both reads hold are printed, those of an ordered table in the same order, a value both reads
    // hold the same is printed the same or always by the same other word, a value that moved between them stays near them, the console prints
    // nothing where the window holds nothing or the one value it leaves out, and nothing the table shows that the window lacks
    fun check(before: List<Map<String, String>>, console: List<Map<String, String>>, after: List<Map<String, String>>, known: Agreement? = null, shown: Set<String> = emptySet()): Check {
        val first = before.associateBy { it.getValue(".id") }
        val second = after.associateBy { it.getValue(".id") }
        val printed = console.associateBy { it[".id"] ?: "".takeIf { spec.single } }
        val stable = before.map { it.getValue(".id") }.filter { it in second }
        printed.keys.firstOrNull { it == null || it !in first && it !in second }?.let { return Check.Differs("the window has no row $it") }
        stable.firstOrNull { it !in printed }?.let { return Check.Differs("the console does not print $it") }
        val shared = stable.toSet()
        if (spec.move && console.mapNotNull { it[".id"] }.filter { it in shared } != stable) return Check.Differs("the console prints the rows in another order")
        if (stable.isEmpty()) return if (console.isEmpty() && before.isEmpty() && after.isEmpty()) Check.Agrees(known ?: Agreement()) else Check.Unknown
        val fills = HashMap(known?.fills.orEmpty())
        val drops = HashMap(known?.drops.orEmpty())
        val words = HashMap(known?.words.orEmpty().mapValues { HashMap(it.value) })
        val spelled = HashSet(known?.spelled.orEmpty())
        val counted = HashSet(known?.counted.orEmpty())
        // a time the console spells, or gives as a number of seconds, where the window gives a clock, the same within the second it rounds to
        fun respelled(name: String, text: String, held: String, later: String): Boolean {
            if (!CLOCK.matches(held) || CLOCK.matches(text)) return false
            val number = text.toLongOrNull()
            if (number != null) {
                val from = seconds(held) ?: return false
                val to = seconds(later) ?: return false
                if (number !in minOf(from, to) - TIME_SLACK..maxOf(from, to) + TIME_SLACK) return false
                counted += name
                return true
            }
            if (seconds(text) == null || !near(text, held, later)) return false
            spelled += name
            return true
        }
        for (id in stable) {
            val row = printed.getValue(id)
            val was = first.getValue(id)
            val now = second.getValue(id)
            row.keys.firstOrNull { it in shown && it !in names }?.let { return Check.Differs("$it: the table shows it and the window lacks it") }
            for (name in names) {
                val text = row[name]
                val held = was[name]
                val later = now[name]
                val fine = when {
                    held == null && later == null -> text == null || text in NOTHING && fills.getOrPut(name) { text } == text
                    held == null || later == null -> text == null || text == held || text == later
                    held == later -> when {
                        text == held -> true
                        text != null && respelled(name, text, held, later) -> true
                        // a date worked out from the uptime may stand a second off the one the router keeps
                        text != null && fields.getValue(name).kind == MIRROR_SINCE -> near(text, held, later)
                        text == null -> held in NOTHING || drops.getOrPut(name) { held } == held
                        worded(name, held, text) -> words.getOrPut(name) { HashMap() }.getOrPut(held) { text } == text
                        else -> false
                    }
                    text == null -> held in NOTHING || later in NOTHING
                    else -> text == held || text == later || respelled(name, text, held, later) || near(text, held, later)
                }
                if (!fine) return Check.Differs("$name of $id: the console prints ${text ?: "nothing"}, the window ${held ?: "nothing"}")
            }
        }
        val learned = Agreement(emptySet(), fills, drops, words, spelled, counted)
        val tokens = stable.flatMapTo(HashSet()) { id -> listOf(first.getValue(id), second.getValue(id)).flatMap { tokens(shown(it, learned), learned) } }
        return Check.Agrees(Agreement((known?.tokens.orEmpty()) + tokens, fills, drops, words, spelled, counted))
    }
}

private fun two(number: Long) = number.toString().padStart(2, '0')

private fun clock(seconds: Long) = "${two(seconds / 3_600)}:${two(seconds % 3_600 / 60)}:${two(seconds % 60)}"

// the console writes a mask as the number of its bits
private fun prefix(mask: String): String? {
    val parts = mask.split('.').map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return null }.takeIf { it.size == 4 } ?: return null
    val bits = parts.fold(0L) { all, part -> all shl 8 or part.toLong() }
    val ones = bits.countOneBits()
    return ones.toString().takeIf { bits == (0xFFFFFFFFL shl (32 - ones)) and 0xFFFFFFFFL }
}

private fun seconds(text: String): Long? {
    CLOCK.matchEntire(text)?.let { found -> return found.groupValues[1].toLong() * 3_600 + found.groupValues[2].toLong() * 60 + found.groupValues[3].toLong() }
    if (text.isEmpty() || text.all(Char::isDigit)) return null
    val found = SPELLED.matchEntire(text) ?: return null
    return found.groupValues.drop(1).zip(listOf(604_800L, DAY, 3_600L, 60L, 1L)).sumOf { (count, size) -> (count.toLongOrNull() ?: 0) * size }
}

private fun moment(text: String): Long? {
    val found = DATE.matchEntire(text) ?: return null
    val (year, month, day, hour, minute, second) = found.destructured
    return days(year.toInt(), month.toInt(), day.toInt()) * DAY + hour.toLong() * 3_600 + minute.toLong() * 60 + second.toLong()
}

// a time or a date that moved between two reads of the window is printed between them; another moving value keeps the shape of theirs
private fun near(text: String, held: String, later: String): Boolean {
    for (read in listOf(::seconds, ::moment)) {
        val at = read(text)
        val from = read(held)
        val to = read(later)
        if (at != null && from != null && to != null) return at in minOf(from, to) - TIME_SLACK..maxOf(from, to) + TIME_SLACK
    }
    return shape(text) == shape(held)
}

// the uptime of the router and the time on its clock when the rows were read
internal class RouterTime(val uptime: Long, val now: Long?)

private class Read(val items: List<Item>, val time: RouterTime?)

// the console menus WinBox reads from their windows of the WinBox port: such a menu is read from its window once the console printed the same,
// which is checked once for each build of the router and kept with its schema
internal class MenuMirrors(
    private val windows: NativeWindows,
    private val classic: Boolean,
    private val recall: suspend (String) -> List<Map<String, String>>?,
    private val keep: suspend (String, List<Map<String, String>>) -> Unit,
) {
    private val agreed = MutableStateFlow(emptyMap<String, Agreement>())
    private val refused = MutableStateFlow(emptySet<String>())
    private val ticks = MutableStateFlow(emptyMap<String, Pair<Long, TimeMark>>())

    // the rows of the menu, or null where the console reads it this time; a menu not checked yet is read from the console between two reads of the window,
    // and a row with a value not checked yet is printed by the console once
    suspend fun rows(
        path: MenuPath,
        filter: Filter?,
        properties: List<String>?,
        console: (suspend () -> List<Map<String, String>>)?,
        printed: suspend (List<String>) -> List<Map<String, String>>?,
    ): List<Map<String, String>>? {
        val menu = path.toString()
        if (menu in refused.value) return null
        val mirror = windows.mirror(path)?.let { MenuMirror(it, classic) } ?: return null
        if (properties != null && !mirror.names.containsAll(properties - ".id")) return null
        if (filter != null && !mirror.names.containsAll(filter.properties() - ".id")) return null
        if (filter != null && properties == null && mirror.spec.partial) return null
        val known = agreement(menu, mirror)
        if (known == null) {
            if (console == null || filter != null || properties != null) return null
            val before = read(menu, mirror)?.rows(mirror) ?: return null
            val rows = console()
            val after = read(menu, mirror)?.rows(mirror) ?: return rows
            judged(menu, mirror, mirror.check(before, rows, after, shown = windows.shown(path)))
            return rows
        }
        val read = read(menu, mirror) ?: return null
        var agreement: Agreement = known
        val unseen = read.rows(mirror).map { mirror.shown(it, known) }.filter { !known.tokens.containsAll(mirror.tokens(it, known)) }
        if (unseen.isNotEmpty()) {
            val ids = mirror.sample(unseen, known).toSet()
            val shown = printed(ids.toList()) ?: return null
            val after = read(menu, mirror)?.rows(mirror) ?: return null
            val before = read.rows(mirror).filter { it[".id"] in ids }
            agreement = judged(menu, mirror, mirror.check(before, shown, after.filter { it[".id"] in ids }, known, windows.shown(path))) ?: return null
        }
        return read.rows(mirror).map { mirror.shown(it, agreement) }.filter { filter?.matches(it) != false }.map { row ->
            if (properties == null) row else row.filterKeys { it == ".id" || it in properties }
        }
    }

    private fun Read.rows(mirror: MenuMirror) = items.map { mirror.row(it, time) }

    private suspend fun read(menu: String, mirror: MenuMirror): Read? = try {
        windows.whole(mirror.spec)?.let { items ->
            val kind = mirror.spec.only
            val uptime = if (mirror.ages) ticking(UPTIME, "uptime") ?: return null else null
            val now = if (mirror.dates) ticking(ROUTER_CLOCK, "now") ?: return null else null
            Read(items.filter { item -> kind.all { (name, value) -> item.values[name] == value } }, uptime?.let { RouterTime(it, now) })
        }
    } catch (e: DeviceException) {
        // a window the router does not answer, or drops the channel over, leaves the menu to the console
        if (e.category == ErrorCategory.Unsupported) refused.update { it + menu }
        null
    }

    private suspend fun ticking(spec: PackNative, name: String): Long? {
        ticks.value[name]?.takeIf { (_, at) -> at.elapsedNow() < TICKS_KEPT }?.let { (value, at) -> return value + at.elapsedNow().inWholeSeconds }
        val value = windows.item(spec, "#0")?.get(name)?.toLongOrNull() ?: return null
        ticks.update { it + (name to (value to TimeSource.Monotonic.markNow())) }
        return value
    }

    private suspend fun judged(menu: String, mirror: MenuMirror, check: Check): Agreement? = when (check) {
        is Check.Agrees -> check.agreement.also { agreement ->
            agreed.update { it + (menu to agreement) }
            keep(KEPT + menu, listOf(kept(mirror, agreement)))
        }
        is Check.Differs -> {
            refused.update { it + menu }
            null
        }
        Check.Unknown -> null
    }

    private fun kept(mirror: MenuMirror, agreement: Agreement) = mapOf(
        "mark" to mirror.mark,
        "tokens" to agreement.tokens.sorted().joinToString("\n"),
        "fills" to agreement.fills.entries.joinToString("\n") { "${it.key}=${it.value}" },
        "drops" to agreement.drops.entries.joinToString("\n") { "${it.key}=${it.value}" },
        "words" to agreement.words.flatMap { (name, words) -> words.map { (held, text) -> "$name\t$held\t$text" } }.joinToString("\n"),
        "spelled" to agreement.spelled.sorted().joinToString("\n"),
        "counted" to agreement.counted.sorted().joinToString("\n"),
    )

    private suspend fun agreement(menu: String, mirror: MenuMirror): Agreement? {
        agreed.value[menu]?.let { return it }
        val kept = recall(KEPT + menu)?.firstOrNull()?.takeIf { it["mark"] == mirror.mark } ?: return null
        fun pairs(key: String) = kept[key].orEmpty().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val words = kept["words"].orEmpty().lines().mapNotNull { line -> line.split('\t').takeIf { it.size == 3 } }.groupBy({ it[0] }) { it[1] to it[2] }.mapValues { it.value.toMap() }
        fun lines(key: String) = kept[key].orEmpty().lines().filter { it.isNotEmpty() }.toSet()
        return Agreement(lines("tokens"), pairs("fills"), pairs("drops"), words, lines("spelled"), lines("counted")).also { found -> agreed.update { it + (menu to found) } }
    }
}

private val agreementsOf = MapSerializer(String.serializer(), ListSerializer(MapSerializer(String.serializer(), String.serializer())))

internal fun encodeAgreements(agreements: Map<String, List<Map<String, String>>>): String = Json.encodeToString(agreementsOf, agreements)

internal fun decodeAgreements(text: String?): Map<String, List<Map<String, String>>> = try {
    text?.let { Json.decodeFromString(agreementsOf, it) }.orEmpty()
} catch (e: SerializationException) {
    emptyMap()
}

private fun Filter.properties(): Set<String> = when (this) {
    is Filter.Equals -> setOf(property)
    is Filter.Has -> setOf(property)
    is Filter.Lacks -> setOf(property)
    is Filter.Less -> setOf(property)
    is Filter.Greater -> setOf(property)
    is Filter.Not -> filter.properties()
    is Filter.And -> filters.flatMapTo(HashSet()) { it.properties() }
    is Filter.Or -> filters.flatMapTo(HashSet()) { it.properties() }
}
