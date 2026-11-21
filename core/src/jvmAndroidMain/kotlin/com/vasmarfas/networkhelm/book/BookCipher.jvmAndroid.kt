package com.vasmarfas.networkhelm.book

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val KDF = "PBKDF2WithHmacSHA256"
private const val CIPHER = "AES/GCM/NoPadding"

actual val bookCiphers: Boolean = try {
    SecretKeyFactory.getInstance(KDF)
    true
} catch (e: NoSuchAlgorithmException) {
    false
}

actual suspend fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

actual suspend fun pbkdf2(password: String, salt: ByteArray, rounds: Int): ByteArray =
    SecretKeyFactory.getInstance(KDF).generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded

actual suspend fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain)

actual suspend fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray? = try {
    cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(sealed)
} catch (e: AEADBadTagException) {
    null
}

private fun cipher(mode: Int, key: ByteArray, iv: ByteArray) = Cipher.getInstance(CIPHER).apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
