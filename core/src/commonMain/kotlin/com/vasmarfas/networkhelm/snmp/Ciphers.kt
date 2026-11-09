package com.vasmarfas.networkhelm.snmp

fun sha1(message: ByteArray): ByteArray {
    val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
    val bits = message.size.toLong() * 8
    val padded = message.copyOf((message.size + 9 + 63) / 64 * 64)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
    val w = IntArray(80)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) w[i] = word(padded, chunk + 4 * i)
        for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        for (i in 0 until 80) {
            val f = when (i / 20) {
                0 -> (b and c) or (b.inv() and d)
                2 -> (b and c) or (b and d) or (c and d)
                else -> b xor c xor d
            }
            val t = a.rotateLeft(5) + f + e + ROUNDS[i / 20] + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = t
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
    }
    return ByteArray(20) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
}

private val ROUNDS = intArrayOf(0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xCA62C1D6.toInt())

private fun word(bytes: ByteArray, at: Int) =
    (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

fun hmac(hash: (ByteArray) -> ByteArray, key: ByteArray, message: ByteArray): ByteArray {
    val block = (if (key.size > 64) hash(key) else key).copyOf(64)
    val inner = hash(ByteArray(64) { (block[it].toInt() xor 0x36).toByte() } + message)
    return hash(ByteArray(64) { (block[it].toInt() xor 0x5C).toByte() } + inner)
}

internal class Des(key: ByteArray) {
    private val keys = subkeys(bits(key, 0))

    fun encrypt(block: ByteArray) = crypt(block, keys)

    fun decrypt(block: ByteArray) = crypt(block, keys.reversedArray())

    private fun crypt(block: ByteArray, order: LongArray): ByteArray {
        val permuted = permute(bits(block, 0), 64, IP)
        var left = permuted ushr 32
        var right = permuted and 0xFFFFFFFFL
        for (key in order) {
            val next = left xor feistel(right, key)
            left = right
            right = next
        }
        val out = permute((right shl 32) or left, 64, FP)
        return ByteArray(8) { (out ushr (56 - 8 * it)).toByte() }
    }

    private fun feistel(half: Long, key: Long): Long {
        val mixed = permute(half, 32, E) xor key
        var out = 0L
        for (box in 0 until 8) {
            val six = ((mixed ushr (42 - 6 * box)) and 0x3F).toInt()
            out = (out shl 4) or S[box][(six and 0x20) or ((six and 1) shl 4) or ((six ushr 1) and 0xF)].toLong()
        }
        return permute(out, 32, P)
    }

    private companion object {
        fun bits(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value
        }

        fun permute(input: Long, width: Int, table: IntArray): Long {
            var out = 0L
            for (position in table) out = (out shl 1) or ((input ushr (width - position)) and 1)
            return out
        }

        fun subkeys(key: Long): LongArray {
            val chosen = permute(key, 64, PC1)
            var c = chosen ushr 28
            var d = chosen and 0xFFFFFFF
            return LongArray(16) { round ->
                repeat(SHIFTS[round]) {
                    c = ((c shl 1) or (c ushr 27)) and 0xFFFFFFF
                    d = ((d shl 1) or (d ushr 27)) and 0xFFFFFFF
                }
                permute((c shl 28) or d, 56, PC2)
            }
        }

        val IP = intArrayOf(
            58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        )
        val FP = intArrayOf(
            40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
        )
        val E = intArrayOf(
            32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13, 12, 13, 14, 15, 16, 17,
            16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
        )
        val P = intArrayOf(16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25)
        val PC1 = intArrayOf(
            57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
            63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
        )
        val PC2 = intArrayOf(
            14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10, 23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
            41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48, 44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
        )
        val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val S = arrayOf(
            intArrayOf(
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
            ),
            intArrayOf(
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
            ),
            intArrayOf(
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
            ),
            intArrayOf(
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
            ),
            intArrayOf(
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
            ),
            intArrayOf(
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
            ),
            intArrayOf(
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
            ),
            intArrayOf(
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
            ),
        )
    }
}

internal fun desCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
    val des = Des(key)
    val out = ByteArray(data.size)
    var chain = iv.copyOf()
    for (at in data.indices step 8) {
        val block = data.copyOfRange(at, at + 8)
        if (encrypt) {
            des.encrypt(ByteArray(8) { (block[it].toInt() xor chain[it].toInt()).toByte() }).copyInto(out, at)
            chain = out.copyOfRange(at, at + 8)
        } else {
            des.decrypt(block).forEachIndexed { i, byte -> out[at + i] = (byte.toInt() xor chain[i].toInt()).toByte() }
            chain = block
        }
    }
    return out
}

class Aes128(key: ByteArray) {
    private val encryption = expand(key)
    private val decryption = inverted(encryption)

    fun encrypt(block: ByteArray): ByteArray = ByteArray(16).also { encrypt(block, 0, it, 0) }

    fun decrypt(block: ByteArray): ByteArray = ByteArray(16).also { decrypt(block, 0, it, 0) }

    // a round of the four columns is sixteen lookups in the tables of SubBytes with MixColumns, and nothing is allocated
    fun encrypt(input: ByteArray, from: Int, output: ByteArray, to: Int) {
        val keys = encryption
        var s0 = word(input, from) xor keys[0]
        var s1 = word(input, from + 4) xor keys[1]
        var s2 = word(input, from + 8) xor keys[2]
        var s3 = word(input, from + 12) xor keys[3]
        for (round in 1 until 10) {
            val at = 4 * round
            val t0 = TE0[s0 ushr 24] xor TE1[s1 ushr 16 and 0xFF] xor TE2[s2 ushr 8 and 0xFF] xor TE3[s3 and 0xFF] xor keys[at]
            val t1 = TE0[s1 ushr 24] xor TE1[s2 ushr 16 and 0xFF] xor TE2[s3 ushr 8 and 0xFF] xor TE3[s0 and 0xFF] xor keys[at + 1]
            val t2 = TE0[s2 ushr 24] xor TE1[s3 ushr 16 and 0xFF] xor TE2[s0 ushr 8 and 0xFF] xor TE3[s1 and 0xFF] xor keys[at + 2]
            val t3 = TE0[s3 ushr 24] xor TE1[s0 ushr 16 and 0xFF] xor TE2[s1 ushr 8 and 0xFF] xor TE3[s2 and 0xFF] xor keys[at + 3]
            s0 = t0
            s1 = t1
            s2 = t2
            s3 = t3
        }
        put(output, to, last(SBOX, s0, s1, s2, s3) xor keys[40])
        put(output, to + 4, last(SBOX, s1, s2, s3, s0) xor keys[41])
        put(output, to + 8, last(SBOX, s2, s3, s0, s1) xor keys[42])
        put(output, to + 12, last(SBOX, s3, s0, s1, s2) xor keys[43])
    }

    fun decrypt(input: ByteArray, from: Int, output: ByteArray, to: Int) {
        val keys = decryption
        var s0 = word(input, from) xor keys[0]
        var s1 = word(input, from + 4) xor keys[1]
        var s2 = word(input, from + 8) xor keys[2]
        var s3 = word(input, from + 12) xor keys[3]
        for (round in 1 until 10) {
            val at = 4 * round
            val t0 = TD0[s0 ushr 24] xor TD1[s3 ushr 16 and 0xFF] xor TD2[s2 ushr 8 and 0xFF] xor TD3[s1 and 0xFF] xor keys[at]
            val t1 = TD0[s1 ushr 24] xor TD1[s0 ushr 16 and 0xFF] xor TD2[s3 ushr 8 and 0xFF] xor TD3[s2 and 0xFF] xor keys[at + 1]
            val t2 = TD0[s2 ushr 24] xor TD1[s1 ushr 16 and 0xFF] xor TD2[s0 ushr 8 and 0xFF] xor TD3[s3 and 0xFF] xor keys[at + 2]
            val t3 = TD0[s3 ushr 24] xor TD1[s2 ushr 16 and 0xFF] xor TD2[s1 ushr 8 and 0xFF] xor TD3[s0 and 0xFF] xor keys[at + 3]
            s0 = t0
            s1 = t1
            s2 = t2
            s3 = t3
        }
        put(output, to, last(INVERSE_SBOX, s0, s3, s2, s1) xor keys[40])
        put(output, to + 4, last(INVERSE_SBOX, s1, s0, s3, s2) xor keys[41])
        put(output, to + 8, last(INVERSE_SBOX, s2, s1, s0, s3) xor keys[42])
        put(output, to + 12, last(INVERSE_SBOX, s3, s2, s1, s0) xor keys[43])
    }

    private companion object {
        fun times2(value: Int) = ((value shl 1) xor if (value and 0x80 != 0) 0x1B else 0) and 0xFF

        fun times(value: Int, factor: Int): Int {
            var product = 0
            var addend = value
            for (bit in 0 until 4) {
                if (factor and (1 shl bit) != 0) product = product xor addend
                addend = times2(addend)
            }
            return product
        }

        fun last(box: IntArray, a: Int, b: Int, c: Int, d: Int) =
            (box[a ushr 24] shl 24) or (box[b ushr 16 and 0xFF] shl 16) or (box[c ushr 8 and 0xFF] shl 8) or box[d and 0xFF]

        fun put(bytes: ByteArray, at: Int, value: Int) {
            for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        fun expand(key: ByteArray): IntArray {
            val words = IntArray(44)
            for (i in 0 until 4) words[i] = word(key, 4 * i)
            var rcon = 1
            for (i in 4 until 44) {
                var t = words[i - 1]
                if (i % 4 == 0) {
                    t = ((SBOX[t ushr 16 and 0xFF] shl 24) or (SBOX[t ushr 8 and 0xFF] shl 16) or (SBOX[t and 0xFF] shl 8) or SBOX[t ushr 24]) xor (rcon shl 24)
                    rcon = times2(rcon)
                }
                words[i] = words[i - 4] xor t
            }
            return words
        }

        // the keys of the rounds in reverse, the inner ones through InvMixColumns, so decryption has the shape of encryption
        fun inverted(encryption: IntArray): IntArray {
            val words = IntArray(44)
            for (round in 0..10) encryption.copyInto(words, 4 * round, 4 * (10 - round), 4 * (10 - round) + 4)
            for (i in 4 until 40) {
                val w = words[i]
                words[i] = TD0[SBOX[w ushr 24]] xor TD1[SBOX[w ushr 16 and 0xFF]] xor TD2[SBOX[w ushr 8 and 0xFF]] xor TD3[SBOX[w and 0xFF]]
            }
            return words
        }

        val SBOX = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        )

        val INVERSE_SBOX = IntArray(256).also { inverse -> SBOX.forEachIndexed { index, value -> inverse[value] = index } }

        val TE0 = IntArray(256) { (times2(SBOX[it]) shl 24) or (SBOX[it] shl 16) or (SBOX[it] shl 8) or (times2(SBOX[it]) xor SBOX[it]) }
        val TE1 = IntArray(256) { TE0[it].rotateRight(8) }
        val TE2 = IntArray(256) { TE0[it].rotateRight(16) }
        val TE3 = IntArray(256) { TE0[it].rotateRight(24) }
        val TD0 = IntArray(256) { (times(INVERSE_SBOX[it], 14) shl 24) or (times(INVERSE_SBOX[it], 9) shl 16) or (times(INVERSE_SBOX[it], 13) shl 8) or times(INVERSE_SBOX[it], 11) }
        val TD1 = IntArray(256) { TD0[it].rotateRight(8) }
        val TD2 = IntArray(256) { TD0[it].rotateRight(16) }
        val TD3 = IntArray(256) { TD0[it].rotateRight(24) }
    }
}

internal fun aesCfb(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
    val aes = Aes128(key)
    val out = ByteArray(data.size)
    var feedback = iv.copyOf()
    for (at in data.indices step 16) {
        val stream = aes.encrypt(feedback)
        val size = minOf(16, data.size - at)
        for (i in 0 until size) out[at + i] = (data[at + i].toInt() xor stream[i].toInt()).toByte()
        if (size == 16) feedback = (if (encrypt) out else data).copyOfRange(at, at + 16)
    }
    return out
}
