package com.vasmarfas.networkhelm.keenetic

// MD4 of RFC 1320, for the NT hash of a password that the VPN servers of Keenetic check with MS-CHAP
internal fun md4(message: ByteArray): ByteArray {
    val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    val bits = message.size.toLong() * 8
    for (i in 0 until 8) padded[padded.size - 8 + i] = (bits ushr (8 * i)).toByte()

    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476
    val x = IntArray(16)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) {
            val at = chunk + i * 4
            x[i] = (padded[at].toInt() and 0xFF) or ((padded[at + 1].toInt() and 0xFF) shl 8) or
                ((padded[at + 2].toInt() and 0xFF) shl 16) or ((padded[at + 3].toInt() and 0xFF) shl 24)
        }
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (round in 0 until 3) {
            for (step in 0 until 16) {
                val mixed = when (round) {
                    0 -> (b and c) or (b.inv() and d)
                    1 -> ((b and c) or (b and d) or (c and d)) + 0x5A827999
                    else -> (b xor c xor d) + 0x6ED9EBA1
                }
                val turned = (a + mixed + x[MD4_ORDER[round][step]]).rotateLeft(MD4_SHIFTS[round][step % 4])
                a = d
                d = c
                c = b
                b = turned
            }
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }
    return intArrayOf(a0, b0, c0, d0).flatMap { word -> (0 until 4).map { (word ushr (8 * it)).toByte() } }.toByteArray()
}

// the NT hash: MD4 of the password in UTF-16LE
internal fun ntHash(password: String): String = hex(md4(password.flatMap { listOf(it.code.toByte(), (it.code shr 8).toByte()) }.toByteArray()))

private val MD4_ORDER = arrayOf(
    IntArray(16) { it },
    intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15),
    intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15),
)

private val MD4_SHIFTS = arrayOf(intArrayOf(3, 7, 11, 19), intArrayOf(3, 5, 9, 13), intArrayOf(3, 9, 11, 15))
