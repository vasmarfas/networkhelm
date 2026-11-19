package com.vasmarfas.networkhelm.sync

import kotlin.math.abs
import kotlin.time.Clock

const val SHORTEST = 12
const val PHRASE_WORDS = 5

enum class Weakness { Short, Common, Word, Keyboard, Repeat, Sequence, Date }

// weakness: why the password is short of the top score, or the largest pattern found in it
class Strength(val guesses: Double, val score: Int, val weakness: Weakness?) {
    // twelve characters at least, not a common password, and the top score, as the vault asks
    val accepted get() = weakness != Weakness.Short && weakness != Weakness.Common && score == 4
}

// the lists the estimate draws on, each entry with the guesses it takes: a common password its place by use, a word of the phrases
// the size of its list, as the generator picks among them all alike
class Dictionaries(passwords: List<String>, val words: List<String>) {
    internal val common = HashMap<String, Double>().apply { passwords.forEachIndexed { index, password -> getOrPut(lower(password)) { index + 1.0 } } }
    internal val plain = words.associate { lower(it) to words.size.toDouble() }
    internal val longest = maxOf(common.keys.maxOf { it.length }, plain.keys.maxOf { it.length })
}

// the year that dates and years in a password are measured from
fun thisYear(clock: Clock = Clock.System) = (1970 + clock.now().toEpochMilliseconds() / 31_556_952_000L).toInt()

// in the way of zxcvbn: the password is cut into what an attacker tries first, common passwords and words, runs of keys, repeats,
// sequences, years and dates, and the rest counts as typed at random; the cut with the fewest guesses is the estimate. Only sums,
// products and quotients go into the number, no logarithm, so every platform comes to the same one. Past ANALYSED characters the
// rest is not looked at, which only lowers the estimate
fun strength(password: String, dictionaries: Dictionaries, year: Int): Strength {
    if (password.isEmpty()) return Strength(1.0, 0, Weakness.Short)
    val (guesses, cut) = Estimate(dictionaries, year).cheapest(password.take(ANALYSED))
    val weakness = when {
        password.length < SHORTEST -> Weakness.Short
        cut.size == 1 && cut[0].common -> Weakness.Common
        else -> cut.filter { it.weakness != null }.maxByOrNull { it.j - it.i }?.weakness
    }
    return Strength(guesses, SCORES.count { guesses >= it }, weakness)
}

// five words of the list, each as likely as any other: about 64 bits with the 7776 words of EFF
suspend fun passphrase(dictionaries: Dictionaries): String = List(PHRASE_WORDS) { dictionaries.words[Sodium.uniform(dictionaries.words.size)] }.joinToString(" ")

private const val ANALYSED = 100
private val SCORES = listOf(1e3 + 5, 1e6 + 5, 1e8 + 5, 1e10 + 5)
private const val GROWING = 10_000.0

// weakness null: typed at random
private class Match(val i: Int, val j: Int, val guesses: Double, val weakness: Weakness?, val common: Boolean = false)

private class Step(val match: Match, val product: Double, val total: Double)

private class Estimate(private val dictionaries: Dictionaries, private val year: Int) {

    // the cut of the text with the fewest guesses: a cut of l parts takes l! times the product of their guesses, plus 10000^(l-1)
    fun cheapest(text: String): Pair<Double, List<Match>> {
        val n = text.length
        val ending = matches(text).groupBy { it.j }
        val steps = Array(n) { HashMap<Int, Step>() }
        fun update(match: Match, parts: Int) {
            val product = match.guesses * (if (parts > 1) steps[match.i - 1].getValue(parts - 1).product else 1.0)
            val total = factorial(parts) * product + power(GROWING, parts - 1)
            if (steps[match.j].any { (other, step) -> other <= parts && step.total <= total }) return
            steps[match.j][parts] = Step(match, product, total)
        }
        for (k in 0 until n) {
            for (match in ending[k].orEmpty().sortedBy { it.i }) {
                if (match.i == 0) update(match, 1) else steps[match.i - 1].keys.sorted().forEach { update(match, it + 1) }
            }
            update(random(text, 0, k), 1)
            for (i in 1..k) {
                val typed = random(text, i, k)
                steps[i - 1].entries.sortedBy { it.key }.filter { it.value.match.weakness != null }.forEach { update(typed, it.key + 1) }
            }
        }
        var parts = steps[n - 1].entries.minWith(compareBy({ it.value.total }, { it.key })).key
        val total = steps[n - 1].getValue(parts).total
        val cut = ArrayDeque<Match>()
        var k = n - 1
        while (k >= 0) {
            val match = steps[k].getValue(parts).match
            cut.addFirst(match)
            k = match.i - 1
            parts--
        }
        return total to cut
    }

    private fun matches(text: String) =
        words(text) + l33t(text) + keys(text, QWERTY) + keys(text, JCUKEN) + repeats(text) + sequences(text) + years(text) + dates(text)

    private fun match(text: String, i: Int, j: Int, guesses: Double, weakness: Weakness, common: Boolean = false): Match {
        val least = if (j - i + 1 == text.length) 1.0 else if (i == j) 10.0 else 50.0
        return Match(i, j, maxOf(guesses, least), weakness, common)
    }

    private fun random(text: String, i: Int, j: Int): Match {
        val length = j - i + 1
        return Match(i, j, maxOf(power(10.0, length), if (length == 1) 11.0 else 51.0), null)
    }

    private fun lookups(token: String) = listOfNotNull(dictionaries.common[token]?.let { it to true }, dictionaries.plain[token]?.let { it to false })

    private fun words(text: String): List<Match> {
        val lower = lower(text)
        val found = mutableListOf<Match>()
        for (i in text.indices) for (j in i until minOf(text.length, i + dictionaries.longest)) {
            val token = lower.substring(i, j + 1)
            val cases = uppercase(text.substring(i, j + 1))
            for ((rank, common) in lookups(token)) found += match(text, i, j, rank * cases, Weakness.Word, common)
            val reversed = token.reversed()
            if (reversed != token) for ((rank, common) in lookups(reversed)) found += match(text, i, j, rank * cases * 2, Weakness.Word, common)
        }
        return found
    }

    // every reading of the substituted characters, at most 64, and the words of the lists it gives
    private fun l33t(text: String): List<Match> {
        val lower = lower(text)
        val present = lower.toSet().filter { it in L33T }.sorted()
        if (present.isEmpty()) return emptyList()
        var readings = listOf(emptyMap<Char, Char>())
        for (char in present) readings = readings.flatMap { reading -> L33T.getValue(char).map { reading + (char to it) } }.take(64)
        val found = mutableListOf<Match>()
        for (reading in readings) {
            val read = lower.map { reading[it] ?: it }.joinToString("")
            for (i in text.indices) for (j in i + 1 until minOf(text.length, i + dictionaries.longest)) {
                val original = lower.substring(i, j + 1)
                if (original.none { it in reading }) continue
                val variations = reading.filterKeys { it in original }.entries.fold(1.0) { product, (subbed, letter) ->
                    val s = original.count { it == subbed }
                    val u = original.count { it == letter }
                    product * if (u == 0) 2.0 else (1..minOf(u, s)).sumOf { choose(u + s, it) }
                }
                for ((rank, common) in lookups(read.substring(i, j + 1))) found += match(text, i, j, rank * uppercase(text.substring(i, j + 1)) * variations, Weakness.Word, common)
            }
        }
        return found
    }

    private fun keys(text: String, keyboard: Keyboard): List<Match> {
        val found = mutableListOf<Match>()
        var i = 0
        while (i < text.length - 1) {
            var j = i + 1
            var direction = -1
            var turns = 0
            var shifted = if (text[i] in keyboard.shifted) 1 else 0
            while (j < text.length) {
                val step = keyboard.direction(text[j - 1], text[j]) ?: break
                if (step != direction) {
                    turns++
                    direction = step
                }
                if (text[j] in keyboard.shifted) shifted++
                j++
            }
            if (j - i > 2) found += match(text, i, j - 1, keyboard.guesses(j - i, turns, shifted), Weakness.Keyboard)
            i = j
        }
        return found
    }

    // the longest run of one base from each place; the base counts as a password of its own
    private fun repeats(text: String): List<Match> {
        val found = mutableListOf<Match>()
        var i = 0
        while (i < text.length) {
            var base = 0
            var count = 0
            for (b in 1..(text.length - i) / 2) {
                var c = 1
                while (i + (c + 1) * b <= text.length && text.regionMatches(i + c * b, text, i, b)) c++
                if (c > 1 && b * c > base * count) {
                    base = b
                    count = c
                }
            }
            if (count > 1) {
                found += match(text, i, i + base * count - 1, cheapest(text.substring(i, i + base)).first * count, Weakness.Repeat)
                i += base * count
            } else {
                i++
            }
        }
        return found
    }

    private fun sequences(text: String): List<Match> {
        val found = mutableListOf<Match>()
        if (text.length < 2) return found
        fun add(i: Int, j: Int, delta: Int) {
            if ((j - i > 1 || abs(delta) == 1) && abs(delta) in 1..5) {
                val first = text[i]
                val base = when {
                    first in "aAzZ019" -> 4.0
                    first in '0'..'9' -> 10.0
                    else -> 26.0
                }
                found += match(text, i, j, base * (if (delta < 0) 2 else 1) * (j - i + 1), Weakness.Sequence)
            }
        }
        var i = 0
        var last = text[1] - text[0]
        for (k in 2 until text.length) {
            val delta = text[k] - text[k - 1]
            if (delta == last) continue
            add(i, k - 1, last)
            i = k - 1
            last = delta
        }
        add(i, text.length - 1, last)
        return found
    }

    private fun years(text: String) =
        YEAR.findAll(text).map { match(text, it.range.first, it.range.last, maxOf(abs(it.value.toInt() - year), 20).toDouble(), Weakness.Date) }.toList()

    // dates of day, month and year in any order, four to eight digits without separators or six to ten with them; a date inside a
    // longer one is not a date of its own
    private fun dates(text: String): List<Match> {
        val found = mutableListOf<Match>()
        for (i in text.indices) for (j in i + 3..minOf(i + 7, text.length - 1)) {
            val token = text.substring(i, j + 1)
            if (!token.all { it in '0'..'9' }) continue
            val closest = SPLITS.getValue(token.length).mapNotNull { (k, l) -> dated(token.substring(0, k).toInt(), token.substring(k, l).toInt(), token.substring(l).toInt()) }
                .minByOrNull { abs(it - year) }
            closest?.let { found += match(text, i, j, 365.0 * maxOf(abs(it - year), 20), Weakness.Date) }
        }
        for (i in text.indices) for (j in i + 5..minOf(i + 9, text.length - 1)) {
            val parts = SEPARATED.matchEntire(text.substring(i, j + 1))?.groupValues ?: continue
            dated(parts[1].toInt(), parts[3].toInt(), parts[4].toInt())?.let { found += match(text, i, j, 365.0 * maxOf(abs(it - year), 20) * 4, Weakness.Date) }
        }
        return found.filterNot { inner -> found.any { outer -> outer !== inner && outer.i <= inner.i && outer.j >= inner.j && outer.j - outer.i > inner.j - inner.i } }
    }
}

// the year of three numbers read as a day, a month and a year in some order, as zxcvbn reads them, or null where they are no date
private fun dated(first: Int, second: Int, third: Int): Int? {
    val all = listOf(first, second, third)
    if (second !in 1..31 || all.any { it in 100 until 1000 || it > 2050 }) return null
    if (all.count { it > 31 } >= 2 || all.count { it > 12 } == 3 || all.count { it <= 0 } >= 2) return null
    val splits = listOf(third to (first to second), first to (second to third))
    splits.firstOrNull { (year, _) -> year in 1000..2050 }?.let { (year, rest) -> return if (dayAndMonth(rest)) year else null }
    return splits.firstOrNull { (_, rest) -> dayAndMonth(rest) }?.first?.let { if (it > 99) it else if (it > 50) it + 1900 else it + 2000 }
}

private fun dayAndMonth(pair: Pair<Int, Int>) = pair.first in 1..31 && pair.second in 1..12 || pair.second in 1..31 && pair.first in 1..12

// the guesses for the capitals of a word: none, the first, the last or all of them take two, others every way of placing them
private fun uppercase(word: String): Double {
    val upper = word.count { it in 'A'..'Z' }
    val lower = word.count { it in 'a'..'z' }
    if (upper == 0) return 1.0
    if (lower == 0 || upper == 1 && (word.first() in 'A'..'Z' || word.last() in 'A'..'Z')) return 2.0
    return (1..minOf(upper, lower)).sumOf { choose(upper + lower, it) }
}

// a letter at a time, so that the length stays that of the password on every platform
private fun lower(text: String) = CharArray(text.length) { text[it].lowercaseChar() }.concatToString()

private fun choose(n: Int, k: Int): Double {
    if (k > n) return 0.0
    var result = 1.0
    var top = n
    for (d in 1..k) {
        result *= top--
        result /= d
    }
    return result
}

private fun factorial(n: Int) = (2..n).fold(1.0) { product, it -> product * it }

private fun power(base: Double, exponent: Int) = (1..exponent).fold(1.0) { product, _ -> product * base }

private val YEAR = Regex("(?:19|20)\\d\\d")
private val SEPARATED = Regex("""(\d{1,4})([\s/\\_.-])(\d{1,2})\2(\d{1,4})""")
private val SPLITS = mapOf(
    4 to listOf(1 to 2, 2 to 3),
    5 to listOf(1 to 3, 2 to 3),
    6 to listOf(1 to 2, 2 to 4, 4 to 5),
    7 to listOf(1 to 3, 2 to 3, 4 to 5, 4 to 6),
    8 to listOf(2 to 4, 4 to 6),
)

private val L33T = mapOf(
    '4' to "a", '@' to "a", '8' to "b", '(' to "c", '{' to "c", '[' to "c", '<' to "c", '3' to "e", '6' to "g", '9' to "g",
    '1' to "il", '!' to "i", '|' to "il", '7' to "lt", '0' to "o", '$' to "s", '5' to "s", '+' to "t", '%' to "x", '2' to "z",
)

// a keyboard of rows set off by half a key, each key with its neighbours left, right and two above and below
private class Keyboard(rows: List<String>, shiftedRows: List<String>) {
    private val places = HashMap<Char, Pair<Int, Int>>()
    val shifted = HashSet<Char>()
    private val starts: Double
    private val degree: Double

    init {
        rows.forEachIndexed { row, keys -> keys.forEachIndexed { column, key -> places[key] = 2 * column + OFFSETS[row] to row } }
        shiftedRows.forEachIndexed { row, keys -> keys.forEachIndexed { column, key -> places[key] = 2 * column + OFFSETS[row] to row } }
        shiftedRows.forEach { shifted += it.toSet() }
        val unshifted = rows.flatMap { it.toList() }
        starts = places.size.toDouble()
        degree = unshifted.sumOf { key -> NEIGHBOURS.count { (dx, dy) -> places.getValue(key).let { (x, y) -> unshifted.any { places.getValue(it) == x + dx to y + dy } } } }.toDouble() / unshifted.size
    }

    fun direction(from: Char, to: Char): Int? {
        val (x, y) = places[from] ?: return null
        val (tx, ty) = places[to] ?: return null
        return NEIGHBOURS.indexOf(tx - x to ty - y).takeIf { it >= 0 }
    }

    fun guesses(length: Int, turns: Int, shifted: Int): Double {
        var guesses = 0.0
        for (i in 2..length) for (j in 1..minOf(turns, i - 1)) guesses += choose(i - 1, j - 1) * starts * power(degree, j)
        if (shifted == 0) return guesses
        val unshifted = length - shifted
        return guesses * if (unshifted == 0) 2.0 else (1..minOf(shifted, unshifted)).sumOf { choose(shifted + unshifted, it) }
    }

    private companion object {
        val OFFSETS = listOf(0, 3, 4, 5)
        val NEIGHBOURS = listOf(-2 to 0, -1 to -1, 1 to -1, 2 to 0, 1 to 1, -1 to 1)
    }
}

private val QWERTY = Keyboard(
    listOf("`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./"),
    listOf("~!@#$%^&*()_+", "QWERTYUIOP{}|", "ASDFGHJKL:\"", "ZXCVBNM<>?"),
)

private val JCUKEN = Keyboard(
    listOf("ё1234567890-=", "йцукенгшщзхъ\\", "фывапролджэ", "ячсмитьбю."),
    listOf("Ё!\"№;%:?*()_+", "ЙЦУКЕНГШЩЗХЪ/", "ФЫВАПРОЛДЖЭ", "ЯЧСМИТЬБЮ,"),
)
