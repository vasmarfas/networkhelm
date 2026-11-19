@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.js.JsException
import kotlin.js.Promise

// libsodium.js, the build the bindings take in js: the WebAssembly target has no bindings and calls it itself
private external interface SodiumJs : JsAny {
    val crypto_pwhash_ALG_ARGON2ID13: Int

    fun crypto_pwhash(keyLength: Int, password: String, salt: JsAny, opsLimit: Int, memLimit: Int, algorithm: Int): JsAny

    fun crypto_aead_xchacha20poly1305_ietf_encrypt(message: JsAny, additionalData: JsAny, secretNonce: JsAny?, publicNonce: JsAny, key: JsAny): JsAny

    fun crypto_aead_xchacha20poly1305_ietf_decrypt(secretNonce: JsAny?, ciphertext: JsAny, additionalData: JsAny, publicNonce: JsAny, key: JsAny): JsAny

    fun crypto_generichash(hashLength: Int, message: JsAny, key: JsAny): JsAny

    fun crypto_kdf_derive_from_key(subkeyLength: Int, subkeyId: Int, context: String, key: JsAny): JsAny

    fun randombytes_buf(length: Int): JsAny

    fun randombytes_uniform(upperBound: Int): Int
}

private var loaded: Libsodium? = null

// the page fetches libsodium.js with the first call, so a page that never synchronises does not load it
internal actual suspend fun libsodium(): Libsodium = loaded ?: Wrappers(load().await<SodiumJs>()).also { loaded = it }

private class Wrappers(private val sodium: SodiumJs) : Libsodium {
    override fun argon2id(password: String, salt: ByteArray, passes: Int, memory: Int, length: Int) =
        bytes(sodium.crypto_pwhash(length, password, array(salt), passes, memory, sodium.crypto_pwhash_ALG_ARGON2ID13))

    override fun encrypt(message: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray) =
        bytes(sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(array(message), array(data), null, array(nonce), array(key)))

    // libsodium.js throws where the tag does not match
    override fun decrypt(sealed: ByteArray, data: ByteArray, nonce: ByteArray, key: ByteArray) = try {
        bytes(sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, array(sealed), array(data), array(nonce), array(key)))
    } catch (e: JsException) {
        null
    }

    override fun blake2b(message: ByteArray, key: ByteArray, length: Int) = bytes(sodium.crypto_generichash(length, array(message), array(key)))

    override fun derive(key: ByteArray, id: Int, context: String, length: Int) = bytes(sodium.crypto_kdf_derive_from_key(length, id, context, array(key)))

    override fun random(size: Int) = bytes(sodium.randombytes_buf(size))

    override fun uniform(bound: Int) = sodium.randombytes_uniform(bound)
}

private fun load(): Promise<SodiumJs> = js("import('libsodium-wrappers-sumo').then(module => (module.default || module).ready.then(() => module.default || module))")

private fun array(bytes: ByteArray): JsAny = uint8(Base64.encode(bytes))

private fun bytes(array: JsAny): ByteArray = Base64.decode(base64(array))

private fun uint8(text: String): JsAny = js("Uint8Array.from(atob(text), c => c.charCodeAt(0))")

private fun base64(array: JsAny): String = js("(() => { let text = ''; for (let i = 0; i < array.length; i++) text += String.fromCharCode(array[i]); return btoa(text); })()")
