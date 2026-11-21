package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.book.bookCiphers
import com.vasmarfas.networkhelm.book.gcmOpen
import com.vasmarfas.networkhelm.book.gcmSeal
import com.vasmarfas.networkhelm.book.pbkdf2
import com.vasmarfas.networkhelm.book.randomBytes
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

@Serializable
class SealedBook(val format: String, val rounds: Int, val salt: String, val iv: String, val data: String)

@Serializable
class Book(val routers: List<SavedRouter>, val passwords: Map<String, String>)

const val BOOK_FORMAT = "networkhelm-book-1"

// the OWASP figure for PBKDF2-HMAC-SHA256 as of 2023
private const val ROUNDS = 600_000
private const val MAX_ROUNDS = 1_200_000

class BookFormatException : Exception("invalid encrypted address book")

// PBKDF2-HMAC-SHA256 and AES-256-GCM where the platform has them, the same book on every platform
val canSeal: Boolean get() = bookCiphers

suspend fun seal(plain: ByteArray, password: String): SealedBook {
    val salt = randomBytes(16)
    val iv = randomBytes(12)
    return SealedBook(BOOK_FORMAT, ROUNDS, Base64.encode(salt), Base64.encode(iv), Base64.encode(gcmSeal(pbkdf2(password, salt, ROUNDS), iv, plain)))
}

suspend fun unseal(book: SealedBook, password: String): ByteArray? {
    if (book.format != BOOK_FORMAT || book.rounds !in ROUNDS..MAX_ROUNDS) throw BookFormatException()
    val salt: ByteArray
    val iv: ByteArray
    val data: ByteArray
    try {
        salt = Base64.decode(book.salt)
        iv = Base64.decode(book.iv)
        data = Base64.decode(book.data)
    } catch (e: IllegalArgumentException) {
        throw BookFormatException()
    }
    if (salt.size != 16 || iv.size != 12 || data.size < 16) throw BookFormatException()
    return gcmOpen(pbkdf2(password, salt, book.rounds), iv, data)
}
