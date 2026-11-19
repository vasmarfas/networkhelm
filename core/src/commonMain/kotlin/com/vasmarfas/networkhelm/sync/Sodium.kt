package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// libsodium, one library on every platform, so that what one device writes any other one reads
object Sodium {
    const val KEY = 32
    const val NONCE = 24
    const val TAG = 16
    const val SALT = 16

    // the bindings of the JVM and iOS hand a password to libsodium in the encoding of the platform and count it in characters,
    // so only an ASCII one gives every platform the same key
    suspend fun argon2id(password: String, salt: ByteArray, passes: Int, memory: Int, length: Int): ByteArray {
        require(password.all { it.code < 0x80 }) { "the password of Argon2id is not ASCII" }
        require(salt.size == SALT) { "the salt of Argon2id is ${salt.size} bytes, not $SALT" }
        require(passes >= 1 && memory >= 8192 && length >= 16) { "Argon2id takes one pass, 8 KiB of memory and 16 bytes of output at least" }
        val sodium = libsodium()
        return withContext(Dispatchers.Default) { sodium.argon2id(password, salt, passes, memory, length) }
    }

    // XChaCha20-Poly1305 of the IETF: the encrypted text and the tag of TAG bytes after it
    suspend fun encrypt(message: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        sizes(nonce, key)
        return libsodium().encrypt(message, data, nonce, key)
    }

    // null when the text or the associated data were changed, or the nonce or the key are not the ones it was encrypted with
    suspend fun decrypt(sealed: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        sizes(nonce, key)
        if (sealed.size < TAG) return null
        return libsodium().decrypt(sealed, data, nonce, key)
    }

    suspend fun blake2b(message: ByteArray, key: ByteArray, length: Int): ByteArray {
        require(key.size in 16..64 && length in 16..64) { "BLAKE2b with a key takes a key of 16 to 64 bytes and gives 16 to 64" }
        return libsodium().blake2b(message, key, length)
    }

    // crypto_kdf: the context names what the subkey is for, the id tells the subkeys of one context apart
    suspend fun derive(key: ByteArray, id: Int, context: String, length: Int): ByteArray {
        require(key.size == KEY) { "the key to derive from is ${key.size} bytes, not $KEY" }
        require(context.length == 8 && context.all { it.code in 0x20..0x7E }) { "the context of a subkey is eight ASCII characters" }
        require(id >= 0 && length in 16..64) { "a subkey has an id from 0 and 16 to 64 bytes" }
        return libsodium().derive(key, id, context, length)
    }

    suspend fun random(size: Int): ByteArray = libsodium().random(size)

    // from 0 to bound - 1, each as likely as the others
    suspend fun uniform(bound: Int): Int {
        require(bound > 0) { "a random number needs a bound above zero" }
        return libsodium().uniform(bound)
    }

    // the C library reads as many bytes as the algorithm takes, whatever the array holds
    private fun sizes(nonce: ByteArray, key: ByteArray) {
        require(nonce.size == NONCE) { "the nonce is ${nonce.size} bytes, not $NONCE" }
        require(key.size == KEY) { "the key is ${key.size} bytes, not $KEY" }
    }
}

internal interface Libsodium {
    fun argon2id(password: String, salt: ByteArray, passes: Int, memory: Int, length: Int): ByteArray

    fun encrypt(message: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    fun blake2b(message: ByteArray, key: ByteArray, length: Int): ByteArray

    fun derive(key: ByteArray, id: Int, context: String, length: Int): ByteArray

    fun random(size: Int): ByteArray

    fun uniform(bound: Int): Int
}

// every platform starts libsodium asynchronously, once, before its first use
internal expect suspend fun libsodium(): Libsodium
