package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.md5

internal const val MAC_TELNET_PORT = 20561
internal const val MAC_TELNET_HEADER_LEN = 22
internal const val MAC_TELNET_CONTROL_HEADER_LEN = 9
internal val MAC_TELNET_CONTROL_MAGIC = byteArrayOf(0x56, 0x34, 0x12, 0xFF.toByte())
internal val MAC_TELNET_CLIENT_TYPE = byteArrayOf(0x00, 0x15)
internal val MAC_NATIVE_CLIENT_TYPE = byteArrayOf(0x0F, 0x90.toByte())

internal object MacTelnetPacketType {
    const val SESSION_START = 0
    const val DATA = 1
    const val ACK = 2
    const val PING = 4
    const val PONG = 5
    const val END = 255
}

internal object MacTelnetControlType {
    const val BEGIN_AUTH = 0
    const val PASSWORD_SALT = 1
    const val PASSWORD = 2
    const val USERNAME = 3
    const val TERMINAL_TYPE = 4
    const val TERMINAL_WIDTH = 5
    const val TERMINAL_HEIGHT = 6
    const val PACKET_ERROR = 7
    const val END_AUTH = 9
}

internal class MacTelnetHeader(
    val version: Int,
    val type: Int,
    val sourceMac: MacAddress,
    val destinationMac: MacAddress,
    val sessionKey: Int,
    val counter: Long,
)

internal fun encodeHeader(
    type: Int,
    sourceMac: MacAddress,
    destinationMac: MacAddress,
    sessionKey: Int,
    counter: Long,
    fromServer: Boolean = false,
    clientType: ByteArray = MAC_TELNET_CLIENT_TYPE,
): ByteArray {
    val data = ByteArray(MAC_TELNET_HEADER_LEN)
    data[0] = 1
    data[1] = (type and 0xFF).toByte()
    sourceMac.bytes.copyInto(data, 2)
    destinationMac.bytes.copyInto(data, 8)
    val sessionKeyOffset = if (fromServer) 16 else 14
    val clientTypeOffset = if (fromServer) 14 else 16
    data[sessionKeyOffset] = ((sessionKey ushr 8) and 0xFF).toByte()
    data[sessionKeyOffset + 1] = (sessionKey and 0xFF).toByte()
    clientType.copyInto(data, clientTypeOffset)
    data[18] = ((counter ushr 24) and 0xFF).toByte()
    data[19] = ((counter ushr 16) and 0xFF).toByte()
    data[20] = ((counter ushr 8) and 0xFF).toByte()
    data[21] = (counter and 0xFF).toByte()
    return data
}

internal fun decodeHeader(bytes: ByteArray, fromServer: Boolean = true): MacTelnetHeader {
    if (bytes.size < MAC_TELNET_HEADER_LEN) {
        throw DeviceException(
            ErrorCategory.General,
            "MAC-Telnet packet is too short: ${bytes.size} bytes (minimum $MAC_TELNET_HEADER_LEN)",
        )
    }
    val sessionKeyOffset = if (fromServer) 16 else 14
    val sessionKey = ((bytes[sessionKeyOffset].toInt() and 0xFF) shl 8) or
        (bytes[sessionKeyOffset + 1].toInt() and 0xFF)
    val counter = ((bytes[18].toLong() and 0xFF) shl 24) or
        ((bytes[19].toLong() and 0xFF) shl 16) or
        ((bytes[20].toLong() and 0xFF) shl 8) or
        (bytes[21].toLong() and 0xFF)
    val srcMac = bytes.copyOfRange(2, 8)
    val dstMac = bytes.copyOfRange(8, 14)
    return MacTelnetHeader(
        version = bytes[0].toInt() and 0xFF,
        type = bytes[1].toInt() and 0xFF,
        sourceMac = MacAddress(srcMac),
        destinationMac = MacAddress(dstMac),
        sessionKey = sessionKey,
        counter = counter,
    )
}

internal fun encodeControlBlock(type: Int, value: ByteArray = ByteArray(0)): ByteArray {
    val block = ByteArray(MAC_TELNET_CONTROL_HEADER_LEN + value.size)
    MAC_TELNET_CONTROL_MAGIC.copyInto(block, 0)
    block[4] = (type and 0xFF).toByte()
    val len = value.size
    block[5] = ((len ushr 24) and 0xFF).toByte()
    block[6] = ((len ushr 16) and 0xFF).toByte()
    block[7] = ((len ushr 8) and 0xFF).toByte()
    block[8] = (len and 0xFF).toByte()
    value.copyInto(block, MAC_TELNET_CONTROL_HEADER_LEN)
    return block
}

internal class ParsedControlBlock(
    val type: Int,
    val isPlainData: Boolean,
    val value: ByteArray,
)

internal fun parseControlBlocks(payload: ByteArray): List<ParsedControlBlock> {
    val blocks = mutableListOf<ParsedControlBlock>()
    var offset = 0
    while (offset < payload.size) {
        val remaining = payload.size - offset
        val hasMagic = remaining >= MAC_TELNET_CONTROL_HEADER_LEN &&
            payload[offset] == MAC_TELNET_CONTROL_MAGIC[0] &&
            payload[offset + 1] == MAC_TELNET_CONTROL_MAGIC[1] &&
            payload[offset + 2] == MAC_TELNET_CONTROL_MAGIC[2] &&
            payload[offset + 3] == MAC_TELNET_CONTROL_MAGIC[3]
        if (!hasMagic) {
            blocks.add(ParsedControlBlock(type = -1, isPlainData = true, value = payload.copyOfRange(offset, payload.size)))
            break
        }
        val type = payload[offset + 4].toInt() and 0xFF
        val length = ((payload[offset + 5].toInt() and 0xFF) shl 24) or
            ((payload[offset + 6].toInt() and 0xFF) shl 16) or
            ((payload[offset + 7].toInt() and 0xFF) shl 8) or
            (payload[offset + 8].toInt() and 0xFF)
        val valueStart = offset + MAC_TELNET_CONTROL_HEADER_LEN
        val valueEnd = valueStart + length
        if (valueEnd > payload.size) {
            throw DeviceException(
                ErrorCategory.General,
                "MAC-Telnet control block claims $length bytes but only ${payload.size - valueStart} remain",
            )
        }
        blocks.add(ParsedControlBlock(type = type, isPlainData = false, value = payload.copyOfRange(valueStart, valueEnd)))
        offset = valueEnd
    }
    return blocks
}

internal fun macTelnetMd5(password: String, salt: ByteArray): ByteArray = byteArrayOf(0) + md5(byteArrayOf(0) + password.encodeToByteArray() + salt)

internal fun encodeTerminalDimension(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
)
