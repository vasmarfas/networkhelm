@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.book

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import libsodium.crypto_aead_aes256gcm_decrypt
import libsodium.crypto_aead_aes256gcm_encrypt
import libsodium.crypto_aead_aes256gcm_is_available
import libsodium.randombytes_buf
import libsodium.sodium_init
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess

// CryptoKit, the AES-GCM of Apple, is Swift alone; libsodium, which the app carries for synchronisation, has it on processors with AES
actual val bookCiphers: Boolean get() = sodium_init() >= 0 && crypto_aead_aes256gcm_is_available() == 1

actual suspend fun randomBytes(size: Int): ByteArray {
    sodium_init()
    return ByteArray(size).also { bytes -> bytes.pinned { randombytes_buf(it, size.convert()) } }
}

actual suspend fun pbkdf2(password: String, salt: ByteArray, rounds: Int): ByteArray {
    val key = ByteArray(32)
    val status = salt.pinned { saltBytes ->
        key.pinned { keyBytes ->
            CCKeyDerivationPBKDF(kCCPBKDF2, password, password.encodeToByteArray().size.convert(), saltBytes, salt.size.convert(), kCCPRFHmacAlgSHA256, rounds.convert(), keyBytes, key.size.convert())
        }
    }
    check(status == kCCSuccess) { "PBKDF2 of CommonCrypto failed: $status" }
    return key
}

actual suspend fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
    check(bookCiphers) { "this device has no AES-GCM" }
    val sealed = ByteArray(plain.size + 16)
    val status = sealed.pinned { out ->
        plain.pinned { message ->
            iv.pinned { nonce -> key.pinned { secret -> crypto_aead_aes256gcm_encrypt(out, null, message, plain.size.convert(), null, 0u, null, nonce, secret) } }
        }
    }
    check(status == 0) { "AES-GCM of libsodium failed" }
    return sealed
}

actual suspend fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray? {
    check(bookCiphers) { "this device has no AES-GCM" }
    if (sealed.size < 16) return null
    val plain = ByteArray(sealed.size - 16)
    val status = plain.pinned { out ->
        sealed.pinned { text ->
            iv.pinned { nonce -> key.pinned { secret -> crypto_aead_aes256gcm_decrypt(out, null, null, text, sealed.size.convert(), null, 0u, nonce, secret) } }
        }
    }
    return plain.takeIf { status == 0 }
}

// an empty array has no first byte to point at: C gets null with a length of 0
private inline fun <T> ByteArray.pinned(block: (CPointer<UByteVar>?) -> T): T = if (isEmpty()) block(null) else usePinned { block(it.addressOf(0).reinterpret()) }
