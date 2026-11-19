package com.vasmarfas.networkhelm.sync

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

// the secret key as a person copies it from paper or another screen: base32 of RFC 4648, 26 letters and digits for 128 bits, in
// groups of four
fun secretKeyText(secret: ByteArray): String =
    (0 until (secret.size * 8 + 4) / 5).map { group -> ALPHABET[(0 until 5).fold(0) { value, at -> value shl 1 or bit(secret, group * 5 + at) }] }
        .chunked(4).joinToString(" ") { it.joinToString("") }

// null for a text that is not the 26 letters and digits of a key; spaces, dashes and the case do not count
fun secretKeyOf(text: String): ByteArray? {
    val letters = text.uppercase().filterNot { it == ' ' || it == '-' }
    if (letters.length != (SECRET * 8 + 4) / 5 || letters.any { it !in ALPHABET }) return null
    val bits = letters.flatMap { letter -> ALPHABET.indexOf(letter).let { value -> (4 downTo 0).map { value shr it and 1 } } }
    if (bits.drop(SECRET * 8).any { it != 0 }) return null
    return ByteArray(SECRET) { byte -> (0 until 8).fold(0) { value, at -> value shl 1 or bits[byte * 8 + at] }.toByte() }
}

private fun bit(bytes: ByteArray, index: Int) = if (index >= bytes.size * 8) 0 else bytes[index / 8].toInt() shr (7 - index % 8) and 1
