package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.connect.ConnectState
import com.vasmarfas.networkhelm.ui.connect.Problem
import com.vasmarfas.networkhelm.ui.connect.TrustQuestions
import com.vasmarfas.networkhelm.ui.files.LocalFile
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BookTest {
    private fun book(rounds: Int = 600_000, salt: String = Base64.encode(ByteArray(16)), iv: String = Base64.encode(ByteArray(12)), data: String = Base64.encode(ByteArray(16))) =
        SealedBook(BOOK_FORMAT, rounds, salt, iv, data)

    @Test
    fun zeroRoundsAreRejectedAsAFormatErrorBeforeTheKeyIsDerived() = runTest {
        assertFailsWith<BookFormatException> { unseal(book(rounds = 0), "password") }
    }

    @Test
    fun excessiveRoundsAreRejectedBeforeTheKeyIsDerived() = runTest {
        assertFailsWith<BookFormatException> { unseal(book(rounds = Int.MAX_VALUE), "password") }
    }

    @Test
    fun malformedBase64IsAFormatError() = runTest {
        for (field in listOf(book(salt = "?"), book(iv = "?"), book(data = "?"))) {
            assertFailsWith<BookFormatException> { unseal(field, "password") }
        }
    }

    @Test
    fun shortSaltIvAndCiphertextAreFormatErrors() = runTest {
        val short = Base64.encode(ByteArray(1))
        for (field in listOf(book(salt = short), book(iv = short), book(data = short))) {
            assertFailsWith<BookFormatException> { unseal(field, "password") }
        }
    }

    @Test
    fun aWrongPasswordRemainsDistinctFromAnInvalidFile() = runTest {
        val plain = "address book".encodeToByteArray()
        val sealed = seal(plain, "password")
        assertNull(unseal(sealed, "wrong"))
        assertContentEquals(plain, unseal(sealed, "password"))
    }

    @Test
    fun anInvalidBookIsReportedWithoutImportingAnyRouters() = runTest {
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val state = ConnectState(Services(storage, MemoryTrustStore(), null), saved, backgroundScope, TrustQuestions())
        state.open(book(rounds = 0), "password")
        assertIs<Problem.BookInvalid>(state.problem)
        assertEquals(emptyList(), saved.routers.value)
    }

    @Test
    fun aTruncatedEncryptedBookIsReportedInsteadOfBeingReadAsCsv() = runTest {
        val storage = MemoryStorage()
        val state = ConnectState(Services(storage, MemoryTrustStore(), null), SavedRouters(storage), backgroundScope, TrustQuestions())
        state.import(listOf(LocalFile("book.json") { "{\"format\":\"networkhelm-book-1\"".encodeToByteArray() }))
        assertIs<Problem.BookInvalid>(state.problem)
    }
}
