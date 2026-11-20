package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlin.io.encoding.Base64

// the public key of Reality for the link of a client out of the private key of the server: X25519 of RFC 7748 with the base point 9, both
// keys in the base64url without padding Xray and sing-box write, so no openssl of the host is needed
internal fun realityPublicKey(privateKey: String): String {
    val secret = try {
        Base64.UrlSafe.decode(privateKey + "=".repeat((4 - privateKey.length % 4) % 4))
    } catch (e: IllegalArgumentException) {
        null
    }
    if (secret?.size != 32) throw DeviceException(ErrorCategory.ArgumentValue, "the private key of Reality is no 32 bytes of base64url")
    return Base64.UrlSafe.encode(x25519(secret)).trimEnd('=')
}

// the Montgomery ladder over the field of 2^255 - 19, an element as 16 limbs of 16 bits
private fun x25519(secret: ByteArray): ByteArray {
    val k = secret.copyOf()
    k[0] = (k[0].toInt() and 248).toByte()
    k[31] = (k[31].toInt() and 127 or 64).toByte()
    val x1 = LongArray(16).also { it[0] = 9 }
    var x2 = LongArray(16).also { it[0] = 1 }
    var z2 = LongArray(16)
    var x3 = x1.copyOf()
    var z3 = LongArray(16).also { it[0] = 1 }
    var swap = 0
    for (t in 254 downTo 0) {
        val bit = (k[t shr 3].toInt() and 0xff) shr (t and 7) and 1
        if (swap xor bit == 1) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        swap = bit
        val a = add(x2, z2)
        val aa = mul(a, a)
        val b = sub(x2, z2)
        val bb = mul(b, b)
        val e = sub(aa, bb)
        val c = add(x3, z3)
        val d = sub(x3, z3)
        val da = mul(d, a)
        val cb = mul(c, b)
        val sum = add(da, cb)
        x3 = mul(sum, sum)
        val difference = sub(da, cb)
        z3 = mul(x1, mul(difference, difference))
        x2 = mul(aa, bb)
        z2 = mul(e, add(aa, mul(A24, e)))
    }
    if (swap == 1) {
        x2 = x3.also { x3 = x2 }
        z2 = z3.also { z3 = z2 }
    }
    return pack(mul(x2, inverse(z2)))
}

private fun add(a: LongArray, b: LongArray) = LongArray(16) { a[it] + b[it] }

private fun sub(a: LongArray, b: LongArray) = LongArray(16) { a[it] - b[it] }

private fun mul(a: LongArray, b: LongArray): LongArray {
    val t = LongArray(31)
    for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    return t.copyOf(16).also {
        carry(it)
        carry(it)
    }
}

// each limb back to 16 bits; what goes past 2^256 comes round as 38 times itself
private fun carry(o: LongArray) {
    for (i in 0 until 16) {
        val c = o[i] shr 16
        o[i] -= c shl 16
        if (i < 15) o[i + 1] += c else o[0] += 38 * c
    }
}

// a to the power p - 2
private fun inverse(a: LongArray): LongArray {
    var c = a.copyOf()
    for (i in 253 downTo 0) {
        c = mul(c, c)
        if (i != 2 && i != 4) c = mul(c, a)
    }
    return c
}

// the element below p, little-endian
private fun pack(n: LongArray): ByteArray {
    var t = n.copyOf()
    repeat(3) { carry(t) }
    repeat(2) {
        val m = LongArray(16)
        m[0] = t[0] - 0xffed
        for (i in 1 until 15) {
            m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
            m[i - 1] = m[i - 1] and 0xffff
        }
        m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
        val borrow = (m[15] shr 16) and 1
        m[14] = m[14] and 0xffff
        if (borrow == 0L) t = m
    }
    return ByteArray(32) { (t[it / 2] shr (8 * (it % 2))).toByte() }
}

private val A24 = LongArray(16).also {
    it[0] = 0xdb41
    it[1] = 1
}
