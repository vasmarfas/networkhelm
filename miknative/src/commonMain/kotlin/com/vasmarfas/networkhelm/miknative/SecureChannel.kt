package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.snmp.Aes128

private const val BLOCK = 16
private const val MAC_SIZE = 20
private const val HEADER = 2 + BLOCK

// MAC first, then AES-CBC: message and HMAC-SHA1 of it are padded and encrypted under a fresh IV
// the padding is not PKCS#7: the last byte is the count of padding bytes less one, so there is at least one
internal class SecureChannel(private val keys: StreamKeys) {
    private val sending = Aes128(keys.send.aes)
    private val receiving = Aes128(keys.receive.aes)

    fun seal(message: ByteArray): ByteArray = encodeFrame(TAG_ENCRYPTED, record(message))

    fun record(message: ByteArray): ByteArray {
        val body = message + hmacSha1(keys.send.mac, message)
        val padding = BLOCK - 1 - body.size % BLOCK
        val iv = secureRandomBytes(BLOCK)
        val encrypted = encryptCbc(sending, iv, body + ByteArray(padding + 1) { padding.toByte() })
        if (encrypted.size > 0xFFFF) throw DeviceException(ErrorCategory.General, "a message of ${message.size} bytes does not fit one frame")
        return byteArrayOf((encrypted.size shr 8).toByte(), encrypted.size.toByte()) + iv + encrypted
    }

    // a frame carries one record, or several in a row when the router sends the messages that waited
    fun open(payload: ByteArray): List<ByteArray> {
        val messages = ArrayList<ByteArray>()
        var offset = 0
        do {
            if (payload.size - offset < HEADER + BLOCK) throw broken("malformed encrypted frame")
            val length = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            val end = offset + HEADER + length
            if (length < BLOCK || length % BLOCK != 0 || end > payload.size) throw broken("malformed encrypted frame")
            messages += openRecord(payload.copyOfRange(offset + 2, offset + HEADER), payload.copyOfRange(offset + HEADER, end))
            offset = end
        } while (offset < payload.size)
        return messages
    }

    private fun openRecord(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val plain = decryptCbc(receiving, iv, ciphertext)
        val padding = (plain.last().toInt() and 0xFF) + 1
        if (padding + MAC_SIZE > plain.size) throw broken("malformed padding")
        val message = plain.copyOf(plain.size - padding - MAC_SIZE)
        val mac = plain.copyOfRange(message.size, message.size + MAC_SIZE)
        if (!sameBytes(mac, hmacSha1(keys.receive.mac, message))) throw broken("the MAC of a frame does not match")
        return message
    }

    private fun encryptCbc(aes: Aes128, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val block = iv.copyOf()
        for (at in data.indices step BLOCK) {
            for (i in 0 until BLOCK) block[i] = (block[i].toInt() xor data[at + i].toInt()).toByte()
            aes.encrypt(block, 0, out, at)
            out.copyInto(block, 0, at, at + BLOCK)
        }
        return out
    }

    private fun decryptCbc(aes: Aes128, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (at in data.indices step BLOCK) {
            aes.decrypt(data, at, out, at)
            for (i in 0 until BLOCK) out[at + i] = (out[at + i].toInt() xor (if (at == 0) iv[i] else data[at - BLOCK + i]).toInt()).toByte()
        }
        return out
    }

    private fun broken(reason: String) = DeviceException(ErrorCategory.ConnectionLost, reason)
}
