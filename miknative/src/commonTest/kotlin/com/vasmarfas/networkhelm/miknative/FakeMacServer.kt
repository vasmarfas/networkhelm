package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import kotlin.concurrent.Volatile

enum class FakeMacAuthMode {
    MTWEI,
    MD5,
}

class FakeMacServer(
    val serverMac: MacAddress = MacAddress.parse("66:77:88:99:aa:bb"),
    val clientMac: MacAddress = MacAddress.parse("00:11:22:33:44:55"),
    private val expectedUser: String = "admin",
    private val expectedPassword: String = "secret",
    private val authMode: FakeMacAuthMode = FakeMacAuthMode.MTWEI,
    private val dropFirstSessionStart: Boolean = false,
) : MacTelnetTransport {

    var onClientPacket: ((ByteArray, String, Int) -> Unit)? = null
    var serverOutCounter = 0L
    var lastClientCounter = 0L
    var sessionKey = 0
    var sessionEstablished = false
    var dropSessionStartCount = if (dropFirstSessionStart) 1 else 0

    val receivedPayloads = mutableListOf<ByteArray>()
    val receivedTerminalStrings = mutableListOf<String>()
    var lastTerminalWidth: Int? = null
    var lastTerminalHeight: Int? = null
    var holdAcks = false
    private var heldAck: Long? = null

    @Volatile
    var acknowledgements = 0

    private var srpServer: EcSrp5Server? = null
    private val md5Salt = ByteArray(16) { (it + 7).toByte() }

    override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {
        onClientPacket = handler
    }

    override fun send(bytes: ByteArray, destination: String, port: Int) {
        val header = try {
            decodeHeader(bytes, fromServer = false)
        } catch (e: DeviceException) {
            return
        }

        if (header.version != 1) return
        sessionKey = header.sessionKey

        when (header.type) {
            MacTelnetPacketType.SESSION_START -> {
                if (dropSessionStartCount > 0) {
                    dropSessionStartCount--
                    return
                }
                replyAck(header.counter)
            }
            MacTelnetPacketType.ACK -> {
                acknowledgements++
            }
            MacTelnetPacketType.DATA -> {
                val payload = bytes.copyOfRange(MAC_TELNET_HEADER_LEN, bytes.size)
                if (header.counter < lastClientCounter) {
                    ack(lastClientCounter)
                    return
                }
                lastClientCounter = header.counter + payload.size
                ack(lastClientCounter)

                val blocks = parseControlBlocks(payload)
                for (block in blocks) {
                    if (block.isPlainData) {
                        receivedPayloads.add(block.value)
                        receivedTerminalStrings.add(block.value.decodeToString())
                    } else {
                        handleControlBlock(block)
                    }
                }
            }
            MacTelnetPacketType.END -> {
                sessionEstablished = false
            }
        }
    }

    private fun handleControlBlock(block: ParsedControlBlock) {
        when (block.type) {
            MacTelnetControlType.BEGIN_AUTH -> {
                if (authMode == FakeMacAuthMode.MTWEI) {
                    srpServer = EcSrp5Server(expectedUser, expectedPassword, ByteArray(16) { (it + 1).toByte() })
                }
            }
            MacTelnetControlType.PASSWORD_SALT -> {
                val srp = srpServer
                if (authMode == FakeMacAuthMode.MTWEI && srp != null) {
                    val challenge = srp.challenge(block.value)
                    sendServerData(encodeControlBlock(MacTelnetControlType.PASSWORD_SALT, challenge))
                } else if (authMode == FakeMacAuthMode.MD5) {
                    sendServerData(encodeControlBlock(MacTelnetControlType.PASSWORD_SALT, md5Salt))
                }
            }
            MacTelnetControlType.PASSWORD -> {
                var authOk = false
                val srp = srpServer
                if (authMode == FakeMacAuthMode.MTWEI && srp != null) {
                    val clientProof = block.value
                    authOk = srp.confirm(clientProof) != null
                } else if (authMode == FakeMacAuthMode.MD5) {
                    val expectedProof = macTelnetMd5(expectedPassword, md5Salt)
                    authOk = expectedProof.contentEquals(block.value)
                }

                if (authOk) {
                    sessionEstablished = true
                    sendServerData(encodeControlBlock(MacTelnetControlType.END_AUTH) + "Welcome to RouterOS\r\n[admin@Router] > ".encodeToByteArray())
                } else {
                    sendServerData(encodeControlBlock(MacTelnetControlType.END_AUTH) + "Login failed\r\n".encodeToByteArray())
                    replyEnd()
                }
            }
            MacTelnetControlType.TERMINAL_WIDTH -> {
                if (block.value.size >= 2) {
                    lastTerminalWidth = (block.value[0].toInt() and 0xFF) or ((block.value[1].toInt() and 0xFF) shl 8)
                }
            }
            MacTelnetControlType.TERMINAL_HEIGHT -> {
                if (block.value.size >= 2) {
                    lastTerminalHeight = (block.value[0].toInt() and 0xFF) or ((block.value[1].toInt() and 0xFF) shl 8)
                }
            }
        }
    }

    fun sendTerminalOutput(text: String) {
        sendServerData(text.encodeToByteArray())
    }

    private fun ack(counter: Long) {
        if (holdAcks) heldAck = counter else replyAck(counter)
    }

    fun releaseHeldAck() {
        holdAcks = false
        heldAck?.let(::replyAck)
        heldAck = null
    }

    private fun replyAck(counter: Long) {
        val ack = encodeHeader(
            type = MacTelnetPacketType.ACK,
            sourceMac = serverMac,
            destinationMac = clientMac,
            sessionKey = sessionKey,
            counter = counter,
            fromServer = true,
        )
        onClientPacket?.invoke(ack, "127.0.0.1", MAC_TELNET_PORT)
    }

    private fun sendServerData(payload: ByteArray) {
        val header = encodeHeader(
            type = MacTelnetPacketType.DATA,
            sourceMac = serverMac,
            destinationMac = clientMac,
            sessionKey = sessionKey,
            counter = serverOutCounter,
            fromServer = true,
        )
        serverOutCounter += payload.size
        val packet = ByteArray(header.size + payload.size)
        header.copyInto(packet, 0)
        payload.copyInto(packet, header.size)
        onClientPacket?.invoke(packet, "127.0.0.1", MAC_TELNET_PORT)
    }

    private fun replyEnd() {
        val end = encodeHeader(
            type = MacTelnetPacketType.END,
            sourceMac = serverMac,
            destinationMac = clientMac,
            sessionKey = sessionKey,
            counter = serverOutCounter,
            fromServer = true,
        )
        onClientPacket?.invoke(end, "127.0.0.1", MAC_TELNET_PORT)
    }

    override fun close() {
    }
}
