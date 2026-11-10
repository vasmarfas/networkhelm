package com.vasmarfas.networkhelm.miknative

private const val ADDRESS6_BYTES = 16
private const val MAX_MESSAGE_DEPTH = 64
private const val MAX_MESSAGE_ELEMENTS = 100_000

internal object M2Key {
    private const val SYSTEM = 0xFF0000
    private const val SESSION = 0xFE0000

    const val To = SYSTEM or 0x01
    const val From = SYSTEM or 0x02
    const val Request = SYSTEM or 0x05
    const val RequestId = SYSTEM or 0x06
    const val Command = SYSTEM or 0x07
    const val ErrorCode = SYSTEM or 0x08
    const val Error = SYSTEM or 0x09
    const val Unset = SYSTEM or 0x14
    const val SessionId = SESSION or 0x01
}

internal sealed interface M2Value {
    data class Bool(val value: Boolean) : M2Value

    data class U8(val value: Int) : M2Value

    data class U32(val value: Long) : M2Value

    data class U64(val value: Long) : M2Value

    data class Text(val value: String) : M2Value

    class Raw(val value: ByteArray) : M2Value

    class Address6(val value: ByteArray) : M2Value {
        init {
            require(value.size == ADDRESS6_BYTES)
        }
    }

    data class Message(val value: M2Message) : M2Value

    class U32s(val value: LongArray) : M2Value

    data class Texts(val value: List<String>) : M2Value

    data class Messages(val value: List<M2Message>) : M2Value

    class Bools(val value: BooleanArray) : M2Value

    class U64s(val value: LongArray) : M2Value

    class Address6s(val value: List<ByteArray>) : M2Value {
        init {
            require(value.all { it.size == ADDRESS6_BYTES })
        }
    }

    class Raws(val value: List<ByteArray>) : M2Value
}

internal class M2Field(val key: Int, val value: M2Value)

internal class M2FormatException(message: String) : Exception(message)

internal class M2Message(val fields: List<M2Field>) {
    operator fun get(key: Int): M2Value? = fields.firstOrNull { it.key == key }?.value

    fun number(key: Int): Long? = when (val value = get(key)) {
        is M2Value.U8 -> value.value.toLong()
        is M2Value.U32 -> value.value
        is M2Value.U64 -> value.value
        else -> null
    }

    fun text(key: Int): String? = (get(key) as? M2Value.Text)?.value

    fun raw(key: Int): ByteArray? = (get(key) as? M2Value.Raw)?.value

    fun messages(key: Int): List<M2Message>? = (get(key) as? M2Value.Messages)?.value

    fun numbers(key: Int): List<Long>? = (get(key) as? M2Value.U32s)?.value?.toList()

    fun encode(): ByteArray {
        val out = ByteSink()
        out.write(MAGIC)
        for (field in fields) {
            out.write(field.key and 0xFF)
            out.write((field.key shr 8) and 0xFF)
            out.write((field.key shr 16) and 0xFF)
            writeValue(out, field.value)
        }
        return out.toByteArray()
    }

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), '2'.code.toByte())

        fun of(vararg fields: Pair<Int, M2Value>) = M2Message(fields.map { M2Field(it.first, it.second) })

        fun decode(bytes: ByteArray): M2Message = decode(bytes, 0, DecodeBudget())

        // the type a field of this value goes under, as the high byte of a key names it in 0xFF0014: a u8 is the short u32, a bool the one of false
        fun typeOf(value: M2Value): Int = when (value) {
            is M2Value.Bool -> TYPE_FALSE
            is M2Value.U8, is M2Value.U32 -> TYPE_U32
            is M2Value.U64 -> TYPE_U64
            is M2Value.Address6 -> TYPE_ADDRESS6
            is M2Value.Text -> TYPE_TEXT
            is M2Value.Message -> TYPE_MESSAGE
            is M2Value.Raw -> TYPE_RAW
            is M2Value.Bools -> TYPE_BOOLS
            is M2Value.U32s -> TYPE_U32S
            is M2Value.U64s -> TYPE_U64S
            is M2Value.Address6s -> TYPE_ADDRESS6S
            is M2Value.Texts -> TYPE_TEXTS
            is M2Value.Messages -> TYPE_MESSAGES
            is M2Value.Raws -> TYPE_RAWS
        }

        private fun decode(bytes: ByteArray, depth: Int, budget: DecodeBudget): M2Message {
            if (depth > MAX_MESSAGE_DEPTH) throw M2FormatException("the message is nested too deeply")
            if (bytes.size < 2 || bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) throw M2FormatException("no M2 header")
            val reader = Reader(bytes, 2)
            val fields = ArrayList<M2Field>()
            while (reader.remaining > 0) {
                budget.take(1)
                val low = reader.byte()
                val high = reader.byte()
                val namespace = reader.byte()
                val type = reader.byte()
                fields += M2Field((namespace shl 16) or (high shl 8) or low, readValue(reader, type, depth, budget))
            }
            return M2Message(fields)
        }

        private fun writeValue(out: ByteSink, value: M2Value) {
            when (value) {
                is M2Value.Bool -> out.write(if (value.value) TYPE_TRUE else TYPE_FALSE)
                is M2Value.U8 -> {
                    out.write(TYPE_U8)
                    out.write(value.value)
                }
                is M2Value.U32 -> {
                    out.write(TYPE_U32)
                    out.writeLittle(value.value, 4)
                }
                is M2Value.U64 -> {
                    out.write(TYPE_U64)
                    out.writeLittle(value.value, 8)
                }
                is M2Value.Address6 -> {
                    out.write(TYPE_ADDRESS6)
                    out.write(value.value)
                }
                is M2Value.Text -> out.writeBlock(TYPE_TEXT, value.value.encodeToByteArray())
                is M2Value.Raw -> out.writeBlock(TYPE_RAW, value.value)
                is M2Value.Message -> out.writeBlock(TYPE_MESSAGE, if (value.value.fields.isEmpty()) ByteArray(0) else value.value.encode())
                is M2Value.U32s -> {
                    out.write(TYPE_U32S)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.writeLittle(it, 4) }
                }
                is M2Value.Texts -> {
                    out.write(TYPE_TEXTS)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.writeSized(it.encodeToByteArray()) }
                }
                is M2Value.Messages -> {
                    out.write(TYPE_MESSAGES)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.writeSized(it.encode()) }
                }
                is M2Value.Bools -> {
                    out.write(TYPE_BOOLS)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.write(if (it) 1 else 0) }
                }
                is M2Value.U64s -> {
                    out.write(TYPE_U64S)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.writeLittle(it, 8) }
                }
                is M2Value.Address6s -> {
                    out.write(TYPE_ADDRESS6S)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.write(it) }
                }
                is M2Value.Raws -> {
                    out.write(TYPE_RAWS)
                    out.writeLittle(value.value.size.toLong(), 2)
                    value.value.forEach { out.writeSized(it) }
                }
            }
        }

        private fun readValue(reader: Reader, type: Int, depth: Int, budget: DecodeBudget): M2Value = when (type) {
            TYPE_FALSE -> M2Value.Bool(false)
            TYPE_TRUE -> M2Value.Bool(true)
            TYPE_U8 -> M2Value.U8(reader.byte())
            TYPE_U32 -> M2Value.U32(reader.little(4))
            TYPE_U64 -> M2Value.U64(reader.little(8))
            TYPE_ADDRESS6 -> M2Value.Address6(reader.take(ADDRESS6_BYTES))
            TYPE_TEXT, TYPE_TEXT_SHORT -> M2Value.Text(reader.block(type == TYPE_TEXT_SHORT).decodeToString())
            TYPE_RAW, TYPE_RAW_SHORT -> M2Value.Raw(reader.block(type == TYPE_RAW_SHORT))
            TYPE_MESSAGE, TYPE_MESSAGE_SHORT -> M2Value.Message(reader.block(type == TYPE_MESSAGE_SHORT).let { if (it.isEmpty()) M2Message(emptyList()) else decode(it, depth + 1, budget) })
            TYPE_U32S -> M2Value.U32s(LongArray(reader.elements(budget)) { reader.little(4) })
            TYPE_TEXTS -> M2Value.Texts(List(reader.elements(budget)) { reader.block(false).decodeToString() })
            TYPE_MESSAGES -> M2Value.Messages(List(reader.elements(budget)) { decode(reader.block(false), depth + 1, budget) })
            TYPE_BOOLS -> M2Value.Bools(BooleanArray(reader.elements(budget)) { reader.byte() != 0 })
            TYPE_U64S -> M2Value.U64s(LongArray(reader.elements(budget)) { reader.little(8) })
            TYPE_ADDRESS6S -> M2Value.Address6s(List(reader.elements(budget)) { reader.take(ADDRESS6_BYTES) })
            TYPE_RAWS -> M2Value.Raws(List(reader.elements(budget)) { reader.block(false) })
            else -> throw M2FormatException("unknown value type 0x${type.toString(16)}")
        }

        private class DecodeBudget {
            private var remaining = MAX_MESSAGE_ELEMENTS

            fun take(count: Int) {
                if (count > remaining) throw M2FormatException("the message has too many elements")
                remaining -= count
            }
        }

        private fun ByteSink.writeLittle(value: Long, size: Int) {
            for (index in 0 until size) write(((value shr (8 * index)) and 0xFF).toInt())
        }

        private fun ByteSink.writeSized(bytes: ByteArray) {
            writeLittle(bytes.size.toLong(), 2)
            write(bytes)
        }

        private fun ByteSink.writeBlock(longType: Int, bytes: ByteArray) {
            if (bytes.size <= 0xFF) {
                write(longType or 1)
                write(bytes.size)
            } else {
                write(longType)
                writeLittle(bytes.size.toLong(), 2)
            }
            write(bytes)
        }

        private class Reader(private val bytes: ByteArray, private var position: Int) {
            val remaining get() = bytes.size - position

            fun byte(): Int {
                if (remaining < 1) throw M2FormatException("message ends inside a field")
                return bytes[position++].toInt() and 0xFF
            }

            fun little(size: Int): Long {
                if (remaining < size) throw M2FormatException("message ends inside a number")
                var value = 0L
                for (index in 0 until size) value = value or ((bytes[position++].toLong() and 0xFF) shl (8 * index))
                return value
            }

            fun block(short: Boolean): ByteArray = take(if (short) byte() else little(2).toInt())

            fun elements(budget: DecodeBudget): Int = little(2).toInt().also { budget.take(it) }

            fun take(length: Int): ByteArray {
                if (remaining < length) throw M2FormatException("message ends inside a value")
                return bytes.copyOfRange(position, position + length).also { position += length }
            }
        }

        private const val TYPE_FALSE = 0x00
        private const val TYPE_TRUE = 0x01
        private const val TYPE_U32 = 0x08
        private const val TYPE_U8 = 0x09
        private const val TYPE_U64 = 0x10
        private const val TYPE_ADDRESS6 = 0x18
        private const val TYPE_TEXT = 0x20
        private const val TYPE_TEXT_SHORT = 0x21
        private const val TYPE_MESSAGE = 0x28
        private const val TYPE_MESSAGE_SHORT = 0x29
        private const val TYPE_RAW = 0x30
        private const val TYPE_RAW_SHORT = 0x31
        private const val TYPE_BOOLS = 0x80
        private const val TYPE_U32S = 0x88
        private const val TYPE_U64S = 0x90
        private const val TYPE_ADDRESS6S = 0x98
        private const val TYPE_TEXTS = 0xA0
        private const val TYPE_MESSAGES = 0xA8
        private const val TYPE_RAWS = 0xB0
    }
}
