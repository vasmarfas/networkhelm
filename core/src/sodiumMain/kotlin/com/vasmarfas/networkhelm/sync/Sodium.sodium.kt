@file:OptIn(ExperimentalUnsignedTypes::class)

package com.vasmarfas.networkhelm.sync

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

internal actual suspend fun libsodium(): Libsodium {
    if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
    return Bindings
}

private object Bindings : Libsodium {
    override fun argon2id(password: String, salt: ByteArray, passes: Int, memory: Int, length: Int) =
        PasswordHash.pwhash(length, password, salt.asUByteArray(), passes.toULong(), memory, crypto_pwhash_argon2id_ALG_ARGON2ID13).asByteArray()

    override fun encrypt(message: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray) =
        AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(message.asUByteArray(), data.asUByteArray(), nonce.asUByteArray(), key.asUByteArray()).asByteArray()

    override fun decrypt(sealed: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray) = try {
        AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(sealed.asUByteArray(), data.asUByteArray(), nonce.asUByteArray(), key.asUByteArray()).asByteArray()
    } catch (e: AeadCorrupedOrTamperedDataException) {
        null
    }

    override fun blake2b(message: ByteArray, key: ByteArray, length: Int) = GenericHash.genericHash(message.asUByteArray(), length, key.asUByteArray()).asByteArray()

    override fun derive(key: ByteArray, id: Int, context: String, length: Int) = Kdf.deriveFromKey(id.toUInt(), length, context, key.asUByteArray()).asByteArray()

    override fun random(size: Int) = LibsodiumRandom.buf(size).asByteArray()

    override fun uniform(bound: Int) = LibsodiumRandom.uniform(bound.toUInt()).toInt()
}
