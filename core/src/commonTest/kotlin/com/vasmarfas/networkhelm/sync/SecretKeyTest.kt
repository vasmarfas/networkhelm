package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretKeyTest {

    @Test
    fun theKeyIsWrittenAsBase32InGroupsOfFour() {
        // RFC 4648, section 10, without the padding
        assertEquals("MZXW 6YTB OI", secretKeyText("foobar".encodeToByteArray()))
        assertEquals(26, secretKeyText(ByteArray(SECRET)).filterNot { it == ' ' }.length)
    }

    @Test
    fun aKeyCopiedByHandReadsBackAndAnythingElseDoesNot() = runTest {
        val secret = Sodium.random(SECRET)
        val text = secretKeyText(secret)
        assertContentEquals(secret, secretKeyOf(text))
        assertContentEquals(secret, secretKeyOf(text.lowercase().replace(' ', '-')))
        assertNull(secretKeyOf(text.dropLast(1)))
        assertNull(secretKeyOf(text.replaceFirst(text.first { it != ' ' }, '1')))
        // the two bits after the 128 are always zero in a key this app wrote
        val last = text.last()
        assertNull(secretKeyOf(text.dropLast(1) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".first { it != last && "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(it) and 3 != 0 }))
    }
}
