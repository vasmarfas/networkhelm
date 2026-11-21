package com.vasmarfas.networkhelm.book

// PBKDF2-HMAC-SHA256 and AES-256-GCM, the ciphers of the encrypted address book, where the platform has them: Android before 8.0 has no
// PBKDF2 with SHA-256, a page served without HTTPS has no WebCrypto, and libsodium gives AES-GCM on iOS only with the AES instructions
// of ARMv8
expect val bookCiphers: Boolean

expect suspend fun randomBytes(size: Int): ByteArray

// the key of 32 bytes from the password taken as UTF-8, as javax.crypto takes it
expect suspend fun pbkdf2(password: String, salt: ByteArray, rounds: Int): ByteArray

// the text encrypted with the tag of 16 bytes after it, as javax.crypto gives them
expect suspend fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray

// null where the tag does not match
expect suspend fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray?
