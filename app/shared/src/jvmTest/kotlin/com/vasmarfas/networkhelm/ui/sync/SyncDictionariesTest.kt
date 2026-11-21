package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.Weakness
import com.vasmarfas.networkhelm.sync.passphrase
import com.vasmarfas.networkhelm.sync.strength
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncDictionariesTest {
    private val folder = File("src/commonMain/composeResources/files/sync")

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun lines(name: String) = File(folder, name).readText().split('\n').dropLast(1)

    // words.txt: the words of eff_large_wordlist.txt of 2016 without the numbers of the dice; passwords.txt: the 10000 most used
    // passwords of the xato list of SecLists 39657bcc, then those of 12 characters and more of its 100000
    @Test
    fun theListsAreTheOnesTakenFromEffAndSecLists() {
        assertEquals("6d557f0693958fb5e650b68b5bee585eb82cf4da32965505c789e924743bc522", sha256(File(folder, "words.txt")))
        assertEquals("b62947b4b55369874c6286787f36fe815e30179894cda544f411f832b9396722", sha256(File(folder, "passwords.txt")))
        val words = lines("words.txt")
        assertEquals(7776, words.toSet().size)
        assertEquals(words.size, words.toSet().size)
        val passwords = lines("passwords.txt")
        assertEquals(10465, passwords.toSet().size)
        assertEquals(489, passwords.count { it.length >= 12 })
    }

    @Test
    fun theVaultRefusesCommonAndPatternedPasswordsAndTakesAPhraseOfTheGenerator() = runTest {
        val lists = syncDictionaries()
        for (password in listOf("Qwerty123!", "qwertyuiop123456", "iloveyou12345678", "password password", "P@ssw0rd2024!!!!")) {
            assertFalse(strength(password, lists, 2026).accepted, password)
        }
        for (password in listOf("123qweasdzxc", "unbelievable", "Rfnthbyf1988")) {
            assertEquals(Weakness.Common, strength(password, lists, 2026).weakness, password)
        }
        assertEquals(4.697143484032e21, strength("correct horse battery staple", lists, 2026).guesses)
        assertTrue(strength("abacus zoom gravity numbing tycoon", lists, 2026).accepted)
        val phrase = passphrase(lists)
        assertTrue(strength(phrase, lists, 2026).accepted, phrase)
    }
}
