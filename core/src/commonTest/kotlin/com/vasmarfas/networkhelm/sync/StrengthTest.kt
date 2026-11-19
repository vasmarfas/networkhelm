package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class StrengthTest {
    private val lists = Dictionaries(
        listOf("password", "123456", "qwerty", "iloveyou", "dragon", "monkey", "letmein", "football", "1q2w3e4r5t6y", "passw0rd"),
        listOf("correct", "horse", "battery", "staple", "abacus", "zoom", "dragon", "yo-yo"),
    )

    private fun check(password: String, guesses: Double, score: Int, weakness: Weakness?) {
        val strength = strength(password, lists, 2026)
        assertEquals(guesses, strength.guesses, password)
        assertEquals(score, strength.score, password)
        assertEquals(weakness, strength.weakness, password)
    }

    // only sums, products and quotients make the number, so it comes out alike to the last bit on every platform
    @Test
    fun everyPatternGivesTheSameNumberOfGuessesOnEveryPlatform() {
        check("password", 2.0, 0, Weakness.Short)
        check("drowssap", 3.0, 0, Weakness.Short)
        check("PASSWORD", 3.0, 0, Weakness.Short)
        check("Dragon", 11.0, 0, Weakness.Short)
        check("1q2w3e4r5t6y", 10.0, 0, Weakness.Common)
        check("Qwerty123!", 1010000.0, 2, Weakness.Short)
        check("qwertyuiopasdf", 10087696.000000004, 2, Weakness.Keyboard)
        check("йцукенгшщзхъ", 4753.000000000001, 1, Weakness.Keyboard)
        check("abcdefghijklmnop", 65.0, 0, Weakness.Sequence)
        check("aaaaaaaaaaaaaaaa", 193.0, 0, Weakness.Repeat)
        check("19041987", 14236.0, 1, Weakness.Short)
        check("born 1987-04-19!", 375904000000.0, 4, Weakness.Date)
        check("P@ssw0rd2024!!!!", 100750000.0, 3, Weakness.Word)
        check("x7#Kq9!vB2\$mZ", 10000000000001.0, 4, null)
        check("correct horse battery staple", 1.165e16, 4, Weakness.Word)
        check("zoom abacus horse staple yo-yo", 1.09e21, 4, Weakness.Word)
    }

    @Test
    fun theVaultTakesALongPasswordOfTheTopScoreThatIsNotACommonOne() {
        assertFalse(strength("Qwerty123!", lists, 2026).accepted)
        assertFalse(strength("1q2w3e4r5t6y", lists, 2026).accepted)
        assertFalse(strength("P@ssw0rd2024!!!!", lists, 2026).accepted)
        assertFalse(strength("qwertyuiopasdf", lists, 2026).accepted)
        assertTrue(strength("correct horse battery staple", lists, 2026).accepted)
        assertTrue(strength("x7#Kq9!vB2\$mZ", lists, 2026).accepted)
    }

    @Test
    fun aPhraseIsFiveWordsOfTheList() = runTest {
        val words = passphrase(lists).split(" ")
        assertEquals(PHRASE_WORDS, words.size)
        assertTrue(words.all { it in lists.words })
    }

    @Test
    fun theYearIsTakenFromTheClock() {
        val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_794_936_000_000)
        }
        assertEquals(2026, thisYear(clock))
    }
}
