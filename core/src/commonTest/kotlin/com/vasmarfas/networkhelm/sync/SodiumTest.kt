package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SodiumTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun series(from: Int, size: Int) = ByteArray(size) { (from + it).toByte() }

    @Test
    fun argon2idOfOneLaneGivesTheTagsOfTheAlgorithmOfRfc9106() = runTest {
        // the vectors of RFC 9106 have four lanes, a secret and associated data, and libsodium runs one lane without either; these
        // one-lane tags come from an implementation of the RFC that gives its vectors, and libsodium gives the same
        assertEquals("1a98fba8e6394425c9bdc333a9c750aaf811df070f5fb4a5788e1cf4db5793a1", Sodium.argon2id("password", "somesaltsomesalt".encodeToByteArray(), 2, 65536, 32).hex())
        assertEquals("d17ea6341ca93da6079ea2f64dc4aa31dd1aaf9caa67fb42ac4afd0714706f26", Sodium.argon2id("correct horse battery staple", series(0, 16), 1, 8192, 32).hex())
        assertEquals("04c627ea548081af65f3832d4ffb9e9eb09f12195f4ceb469b2bb9ad97774f78", Sodium.argon2id("NetworkHelm", ByteArray(16) { 0x5a }, 3, 1048576, 32).hex())
        assertEquals(
            "3129e97c4e48114f59c1562e65960222c2ab5a5dd0bf043c6420d712a5f6048f2a5a84c85790e50c54bcf1ffe47f962a5eae434b498dd9ac03483ee0f1e3c070",
            Sodium.argon2id("password", "somesaltsomesalt".encodeToByteArray(), 2, 65536, 64).hex(),
        )
    }

    @Test
    fun xchacha20Poly1305GivesTheExampleOfTheDraft() = runTest {
        // the example of AEAD_XChaCha20_Poly1305 in appendix A of draft-irtf-cfrg-xchacha-03
        val plain = bytes(
            "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
                "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
                "637265656e20776f756c642062652069742e",
        )
        val data = bytes("50515253c0c1c2c3c4c5c6c7")
        val key = series(0x80, 32)
        val nonce = series(0x40, 24)
        val sealed = Sodium.encrypt(plain, data, nonce, key)
        assertEquals(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452" +
                "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52e" +
                "c0875924c1c7987947deafd8780acf49",
            sealed.hex(),
        )
        assertContentEquals(plain, Sodium.decrypt(sealed, data, nonce, key))
    }

    @Test
    fun aChangedByteDataNonceOrKeyIsNotDecrypted() = runTest {
        val key = series(1, 32)
        val nonce = series(2, 24)
        val data = "header".encodeToByteArray()
        val sealed = Sodium.encrypt("state".encodeToByteArray(), data, nonce, key)
        for (index in sealed.indices) {
            val changed = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(Sodium.decrypt(changed, data, nonce, key), "byte $index")
        }
        assertNull(Sodium.decrypt(sealed, "Header".encodeToByteArray(), nonce, key))
        assertNull(Sodium.decrypt(sealed, data, series(3, 24), key))
        assertNull(Sodium.decrypt(sealed, data, nonce, series(4, 32)))
        assertNull(Sodium.decrypt(sealed.copyOf(sealed.size - 1), data, nonce, key))
        assertNull(Sodium.decrypt(sealed.copyOf(Sodium.TAG - 1), data, nonce, key))
    }

    @Test
    fun blake2bWithAKeyGivesTheAnswersOfTheBlake2Package() = runTest {
        // blake2b-kat.txt: the key 00..3f, the input 00 up to its length
        val key = series(0, 64)
        assertEquals(
            "961f6dd1e4dd30f63901690c512e78e4b45e4742ed197c3c5e45c549fd25f2e4187b0bc9fe30492b16b0d0bc4ef9b0f34c7003fac09a5ef1532e69430234cebd",
            Sodium.blake2b(series(0, 1), key, 64).hex(),
        )
        assertEquals(
            "33d0825dddf7ada99b0e7e307104ad07ca9cfd9692214f1561356315e784f3e5a17e364ae9dbb14cb2036df932b77f4b292761365fb328de7afdc6d8998f5fc1",
            Sodium.blake2b(series(0, 3), key, 64).hex(),
        )
        assertEquals(
            "142709d62e28fcccd0af97fad0f8465b971e82201dc51070faa0372aa43e92484be1c1e73ba10906d5d1853db6a4106e0a7bf9800d373d6dee2d46d62ef2a461",
            Sodium.blake2b(series(0, 255), key, 64).hex(),
        )
        assertEquals("07de31fc92a4e40cc6fe0e94a99901ec4071811691cbcd9f7f529c9ff51a1329", Sodium.blake2b("NetworkHelm".encodeToByteArray(), ByteArray(32), 32).hex())
    }

    @Test
    fun aSubkeyIsTheOneLibsodiumDerives() = runTest {
        // the expected output of test/default/kdf.c of libsodium: the key 00..1f, the context KDF test
        val key = series(0, 32)
        assertEquals(
            "a0c724404728c8bb95e5433eb6a9716171144d61efb23e74b873fcbeda51d8071b5d70aae12066dfc94ce943f145aa176c055040c3dd73b0a15e36254d450614",
            Sodium.derive(key, 0, "KDF test", 64).hex(),
        )
        assertEquals(
            "02507f144fa9bf19010bf7c70b235b4c2663cc00e074f929602a5e2c10a780757d2a3993d06debc378a90efdac196dd841817b977d67b786804f6d3cd585bab5",
            Sodium.derive(key, 1, "KDF test", 64).hex(),
        )
        assertEquals("13fea52bb8cba063f3ed93de27ed07e06d8c6367474e6ae4c9282913ac3c3a03", Sodium.derive(key, 1, "KDF test", 32).hex())
    }

    @Test
    fun aTextSealedOnTheJvmIsReadAndWrittenAlikeOnEveryPlatform() = runTest {
        val fixture = "EW2F4vroERLSGCLc1D8Ad0vqEDsePwqukX2728gykrGWOCuGwM3VBJj3rgh+pwod1weZ66/0trrFPUe60g=="
        val master = Sodium.argon2id("correct horse battery staple", series(0x10, 16), 2, 65536, 32)
        val key = Sodium.derive(master, 1, "NHSstate", 32)
        val nonce = series(0x20, 24)
        val data = "NHS state 0f8c".encodeToByteArray()
        val plain = checkNotNull(Sodium.decrypt(Base64.decode(fixture), data, nonce, key))
        assertEquals("{\"schema\":{\"major\":1,\"minor\":0},\"records\":[]}", plain.decodeToString())
        assertEquals(fixture, Base64.encode(Sodium.encrypt(plain, data, nonce, key)))
    }

    @Test
    fun randomBytesDifferFromCallToCall() = runTest {
        val first = Sodium.random(32)
        assertEquals(32, first.size)
        assertNotEquals(first.hex(), Sodium.random(32).hex())
    }

    @Test
    fun aUniformNumberStaysBelowItsBoundAndReachesEveryValue() = runTest {
        val drawn = List(300) { Sodium.uniform(3) }
        assertTrue(drawn.all { it in 0..2 })
        assertEquals(setOf(0, 1, 2), drawn.toSet())
        assertFailsWith<IllegalArgumentException> { Sodium.uniform(0) }
    }

    @Test
    fun sizesTheLibraryWouldReadPastAreRefusedBeforeItIsCalled() = runTest {
        val key = ByteArray(32)
        assertFailsWith<IllegalArgumentException> { Sodium.encrypt(ByteArray(1), ByteArray(1), ByteArray(12), key) }
        assertFailsWith<IllegalArgumentException> { Sodium.decrypt(ByteArray(17), ByteArray(1), ByteArray(24), ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { Sodium.argon2id("пароль", ByteArray(16), 1, 8192, 32) }
        assertFailsWith<IllegalArgumentException> { Sodium.argon2id("password", ByteArray(8), 1, 8192, 32) }
        assertFailsWith<IllegalArgumentException> { Sodium.derive(key, 0, "short", 32) }
        assertFailsWith<IllegalArgumentException> { Sodium.derive(ByteArray(16), 0, "KDF test", 32) }
        assertFailsWith<IllegalArgumentException> { Sodium.blake2b(ByteArray(1), ByteArray(8), 32) }
    }
}
