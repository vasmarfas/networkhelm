package com.vasmarfas.networkhelm.nettools

class SubOption(val code: Int, val value: List<Int>)

// a value of a DHCP option as RouterOS takes it: 0x and the bytes in hex
object OptionHex {
    private val separators = Regex("[\\s,;]+")

    fun addresses(text: String): List<Long>? =
        text.split(separators).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.map { Ipv4.parse(it) ?: return null }

    fun bytesOf(addresses: List<Long>): List<Int> = addresses.flatMap { address -> (24 downTo 0 step 8).map { ((address shr it) and 0xFF).toInt() } }

    fun bytes(text: String): List<Int>? {
        val trimmed = text.trim()
        val digits = (if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.substring(2) else trimmed).filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (digits.isEmpty() || digits.length % 2 != 0) return null
        return digits.chunked(2).map { it.toIntOrNull(16) ?: return null }
    }

    fun format(bytes: List<Int>): String = "0x" + bytes.joinToString("") { it.toString(16).padStart(2, '0').uppercase() }

    fun addressesIn(bytes: List<Int>): List<String>? = bytes.takeIf { it.isNotEmpty() && it.size % 4 == 0 }?.chunked(4)?.map { it.joinToString(".") }

    fun text(bytes: List<Int>): String? = bytes.takeIf { it.isNotEmpty() && it.all { byte -> byte in 0x20..0x7E } }?.joinToString("") { it.toChar().toString() }

    fun wrapped(code: Int, value: List<Int>): List<Int>? = if (code in 0..255 && value.size <= 255) listOf(code, value.size) + value else null

    // code, length and value one after another up to the last byte, as the sub-options of option 43 are written
    fun subOptions(bytes: List<Int>): List<SubOption>? {
        val options = ArrayList<SubOption>()
        var at = 0
        while (at < bytes.size) {
            val length = bytes.getOrNull(at + 1) ?: return null
            if (at + 2 + length > bytes.size) return null
            options += SubOption(bytes[at], bytes.subList(at + 2, at + 2 + length))
            at += 2 + length
        }
        return options.takeIf { it.isNotEmpty() }
    }
}
