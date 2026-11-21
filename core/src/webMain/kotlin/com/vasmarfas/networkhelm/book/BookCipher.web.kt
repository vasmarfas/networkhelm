@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.book

import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.js.Promise

// WebCrypto, which a browser gives only to a page served over HTTPS or from localhost; bytes cross into JavaScript as base64
actual val bookCiphers: Boolean get() = subtle()

actual suspend fun randomBytes(size: Int): ByteArray = Base64.decode(random(size))

actual suspend fun pbkdf2(password: String, salt: ByteArray, rounds: Int): ByteArray = Base64.decode(text(derive(password, Base64.encode(salt), rounds).await<JsAny>()))

actual suspend fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray =
    Base64.decode(text(gcm("encrypt", Base64.encode(key), Base64.encode(iv), Base64.encode(plain)).await<JsAny?>() ?: error("AES-GCM of WebCrypto gave nothing")))

actual suspend fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray? =
    gcm("decrypt", Base64.encode(key), Base64.encode(iv), Base64.encode(sealed)).await<JsAny?>()?.let { Base64.decode(text(it)) }

private fun subtle(): Boolean = js("!!(globalThis.crypto && globalThis.crypto.subtle)")

private fun text(value: JsAny): String = js("value")

private fun random(size: Int): String = js(
    """(() => {
        const bytes = crypto.getRandomValues(new Uint8Array(size));
        let text = '';
        for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
        return btoa(text);
    })()""",
)

private fun derive(password: String, salt: String, rounds: Int): Promise<JsAny> = js(
    """crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits'])
        .then((key) => crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: Uint8Array.from(atob(salt), (c) => c.charCodeAt(0)), iterations: rounds }, key, 256))
        .then((bits) => {
            const bytes = new Uint8Array(bits);
            let text = '';
            for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
            return btoa(text);
        })""",
)

// null where WebCrypto refuses the tag of a text to decrypt
private fun gcm(mode: String, key: String, iv: String, data: String): Promise<JsAny?> = js(
    """(() => {
        const bytes = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
        return crypto.subtle.importKey('raw', bytes(key), 'AES-GCM', false, [mode])
            .then((imported) => crypto.subtle[mode]({ name: 'AES-GCM', iv: bytes(iv) }, imported, bytes(data)))
            .then((result) => {
                const out = new Uint8Array(result);
                let text = '';
                for (let i = 0; i < out.length; i++) text += String.fromCharCode(out[i]);
                return btoa(text);
            }, (error) => {
                if (mode === 'decrypt' && error.name === 'OperationError') return null;
                throw error;
            });
    })()""",
)
