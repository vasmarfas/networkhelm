package com.vasmarfas.networkhelm.routeros.api

internal const val MAX_WORD_LENGTH = 64 * 1024 * 1024
private const val NAMES_MOST = 4_096

internal fun encodeLength(length: Int): ByteArray {
    require(length >= 0) { "negative word length $length" }
    return when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x4000 -> bigEndian(length or 0x8000, 2)
        length < 0x200000 -> bigEndian(length or 0xC00000, 3)
        length < 0x10000000 -> bigEndian(length or 0xE0000000.toInt(), 4)
        else -> byteArrayOf(0xF0.toByte()) + bigEndian(length, 4)
    }
}

private fun bigEndian(value: Int, bytes: Int) = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }

internal fun encodeSentence(words: List<ByteArray>): ByteArray {
    var size = 1
    val lengths = words.map { word -> encodeLength(word.size).also { size += it.size + word.size } }
    val out = ByteArray(size)
    var at = 0
    words.forEachIndexed { i, word ->
        lengths[i].copyInto(out, at)
        at += lengths[i].size
        word.copyInto(out, at)
        at += word.size
    }
    return out
}

class ApiProtocolException(message: String) : Exception(message)

internal class SentenceReader(private val read: suspend (ByteArray, Int, Int) -> Int) {
    private val buffer = ByteArray(16 * 1024)
    private var position = 0
    private var limit = 0

    suspend fun next(): List<ByteArray>? {
        if (position == limit && !fill()) return null
        val words = mutableListOf<ByteArray>()
        while (true) {
            val length = readLength()
            if (length == 0) return words
            words += readBytes(length)
        }
    }

    private suspend fun fill(): Boolean {
        val count = read(buffer, 0, buffer.size)
        if (count <= 0) return false
        position = 0
        limit = count
        return true
    }

    private suspend fun byte(): Int {
        if (position == limit && !fill()) throw ApiProtocolException("connection closed inside a sentence")
        return buffer[position++].toInt() and 0xFF
    }

    private suspend fun readLength(): Int {
        val first = byte()
        val length = when {
            (first and 0x80) == 0 -> first
            (first and 0xC0) == 0x80 -> ((first and 0x3F) shl 8) or byte()
            (first and 0xE0) == 0xC0 -> ((first and 0x1F) shl 16) or (byte() shl 8) or byte()
            (first and 0xF0) == 0xE0 -> ((first and 0x0F) shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
            first == 0xF0 -> (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
            else -> throw ApiProtocolException("reserved control byte 0x${first.toString(16)}")
        }
        if (length < 0 || length > MAX_WORD_LENGTH) throw ApiProtocolException("word length $length out of range")
        return length
    }

    private suspend fun readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            if (position == limit && !fill()) throw ApiProtocolException("connection closed inside a word")
            val chunk = minOf(count - filled, limit - position)
            buffer.copyInto(out, filled, position, position + chunk)
            position += chunk
            filled += chunk
        }
        return out
    }
}

data class ApiReply(val type: String, val attributes: Map<String, String>, val tag: String?)

// the rows of a big table repeat the same few names, so a connection keeps one copy of each
internal fun parseReply(words: List<String>, names: MutableMap<String, String> = HashMap()): ApiReply {
    if (words.isEmpty()) throw ApiProtocolException("empty sentence")
    val attributes = LinkedHashMap<String, String>()
    var tag: String? = null
    for (word in words.subList(1, words.size)) {
        when {
            word.startsWith(".tag=") -> tag = word.substring(5)
            word.startsWith("=") -> {
                val split = word.indexOf('=', 1)
                val name = if (split < 0) word.substring(1) else word.substring(1, split)
                attributes[names[name] ?: name.also { if (names.size < NAMES_MOST) names[it] = it }] = if (split < 0) "" else word.substring(split + 1)
            }
            else -> attributes["message"] = word
        }
    }
    return ApiReply(words[0], attributes, tag)
}
