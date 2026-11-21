package com.vasmarfas.networkhelm.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class BookFixtureTest {
    // a book as 1.3.0 wrote it on a desktop, sealed apart from this code with Python: every platform opens it
    @Test
    fun aBookOfTheDesktopOpensOnEveryPlatform() = runTest {
        val book = SealedBook(BOOK_FORMAT, 600_000, "AAECAwQFBgcICQoLDA0ODw==", "ZGVmZ2hpamtsbW5v", "8cL97cl/nRfy+ye+Lj+eqKXlxvJiTlA50o4qlAwpzPA9aRblfts7OOa9/wxs")
        assertContentEquals("{\"routers\":[],\"passwords\":{}}".encodeToByteArray(), unseal(book, "correct horse battery staple"))
        assertNull(unseal(book, "correct horse battery stapler"))
        val plain = "routers of this platform".encodeToByteArray()
        assertContentEquals(plain, unseal(seal(plain, "a password of the book"), "a password of the book"))
    }
}
