package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory

class MacAddress(val bytes: ByteArray) {
    init {
        if (bytes.size != 6) {
            throw DeviceException(ErrorCategory.ArgumentValue, "MAC address must be 6 bytes, got ${bytes.size}")
        }
    }

    fun format(): String = bytes.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    override fun equals(other: Any?): Boolean = other is MacAddress && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = format()

    companion object {
        fun parse(text: String): MacAddress {
            val parts = text.trim().split(':', '-', '.')
            if (parts.size != 6) {
                throw DeviceException(ErrorCategory.ArgumentValue, "\"$text\" is not a 6-octet MAC address")
            }
            val octets = ByteArray(6)
            for (i in 0 until 6) {
                val value = parts[i].toIntOrNull(16)
                    ?: throw DeviceException(ErrorCategory.ArgumentValue, "invalid MAC octet \"${parts[i]}\" in \"$text\"")
                if (value !in 0..0xFF) {
                    throw DeviceException(ErrorCategory.ArgumentValue, "MAC octet out of range: \"${parts[i]}\"")
                }
                octets[i] = value.toByte()
            }
            return MacAddress(octets)
        }
    }
}
