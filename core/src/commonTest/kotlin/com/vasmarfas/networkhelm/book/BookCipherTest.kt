package com.vasmarfas.networkhelm.book

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// the published vectors of PBKDF2-HMAC-SHA256 and the test cases of McGrew and Viega for GCM, checked with hashlib and cryptography of
// Python; the same on the JVM, in the browser and on iOS
class BookCipherTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun pbkdf2GivesTheKeysOfTheKnownVectors() = runTest {
        assertContentEquals(hex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"), pbkdf2("password", "salt".encodeToByteArray(), 1))
        assertContentEquals(hex("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"), pbkdf2("password", "salt".encodeToByteArray(), 4096))
        // a password beyond ASCII is taken as UTF-8 everywhere, as javax.crypto takes it
        assertContentEquals(hex("f2fd4004ddf574099aab9ac95e6654c9ed0408d19ccb90778484d64e572a9a8e"), pbkdf2("пароль ё 🔑", "NetworkHelm salt".encodeToByteArray(), 1000))
    }

    @Test
    fun aesGcmGivesTheTextsAndTagsOfTheKnownVectors() = runTest {
        assertTrue(bookCiphers)
        val zero = ByteArray(32)
        assertContentEquals(hex("530f8afbc74536b9a963b4f1c4cb738b"), gcmSeal(zero, ByteArray(12), ByteArray(0)))
        assertContentEquals(hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"), gcmSeal(zero, ByteArray(12), ByteArray(16)))
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
        val sealed = gcmSeal(key, iv, plain)
        assertContentEquals(
            hex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015adb094dac5d93471bdec1a502270e3cc6c"),
            sealed,
        )
        assertContentEquals(plain, gcmOpen(key, iv, sealed))
        assertNull(gcmOpen(key, iv, sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertNull(gcmOpen(hex("00").copyOf(32), iv, sealed))
    }

    @Test
    fun randomBytesComeInTheSizeAskedForAndDiffer() = runTest {
        val first = randomBytes(16)
        assertEquals(16, first.size)
        assertTrue(!first.contentEquals(randomBytes(16)))
    }
}
