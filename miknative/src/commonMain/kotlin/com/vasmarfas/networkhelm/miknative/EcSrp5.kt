package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.keenetic.sha256 as sha256Of
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.snmp.hmac
import com.vasmarfas.networkhelm.snmp.sha1

internal class DirectionKeys(val aes: ByteArray, val mac: ByteArray)

internal class StreamKeys(val send: DirectionKeys, val receive: DirectionKeys)

private const val SEND_LABEL = "On the client side, this is the send key; on the server side, it is the receive key."
private const val RECEIVE_LABEL = "On the client side, this is the receive key; on the server side, it is the send key."
private const val CHALLENGE_SIZE = 49
private const val UNKNOWN_NAME_SIZE = 33
internal const val WRONG_LOGIN = "wrong user name or password"

internal fun sha256(vararg parts: ByteArray): ByteArray = sha256Of(parts.fold(ByteArray(0)) { all, part -> all + part })

internal fun hmacSha1(key: ByteArray, vararg parts: ByteArray): ByteArray =
    hmac(::sha1, key, parts.fold(ByteArray(0)) { all, part -> all + part })

// the router writes the x of the shared point as a number, so what it hashes lacks the zero bytes in front
internal fun ByteArray.withoutLeadingZeros(): ByteArray = dropWhile { it == 0.toByte() }.toByteArray()

// per direction 16 bytes of AES key and 20 of MAC key, from HKDF (zero salt of 64 bytes, no info)
// over a SHA-1 of the shared secret and the label of the direction
internal fun streamKeys(secret: ByteArray, server: Boolean): StreamKeys {
    val send = keyMaterial(secret, if (server) RECEIVE_LABEL else SEND_LABEL)
    val receive = keyMaterial(secret, if (server) SEND_LABEL else RECEIVE_LABEL)
    return StreamKeys(
        DirectionKeys(aes = send.copyOfRange(0, 16), mac = send.copyOfRange(16, 36)),
        DirectionKeys(aes = receive.copyOfRange(0, 16), mac = receive.copyOfRange(16, 36)),
    )
}

private fun keyMaterial(secret: ByteArray, label: String): ByteArray {
    val seed = sha1(secret + ByteArray(40) + label.encodeToByteArray() + ByteArray(40) { 0xF2.toByte() }).copyOf(16)
    val prk = hmacSha1(ByteArray(64), seed)
    val out = ByteSink()
    var block = ByteArray(0)
    for (counter in 1..2) {
        block = hmacSha1(prk, block, byteArrayOf(counter.toByte()))
        out.write(block)
    }
    return out.toByteArray().copyOf(36)
}

internal fun passwordMask(verifierX: ByteArray, odd: Boolean): WPoint {
    var seed = sha256(verifierX)
    while (true) {
        WCurve.lift(Fe.fromBytes(sha256(seed)), odd)?.let { return it }
        seed = incremented(seed)
    }
}

private fun incremented(bytes: ByteArray): ByteArray {
    val out = bytes.copyOf()
    for (i in out.indices.reversed()) {
        out[i]++
        if (out[i] != 0.toByte()) break
    }
    return out
}

internal fun passwordVerifier(user: String, password: String, salt: ByteArray): U256 =
    U256.fromBytes(sha256(salt, sha256("$user:$password".encodeToByteArray())))

internal class EcSrp5Client(
    private val user: String,
    private val password: String,
    private val ephemeral: U256 = U256.fromBytes(secureRandomBytes(32)),
) {
    // the router keys the verifier by the account, what follows a + only tunes the console
    private val account = user.substringBefore('+')
    private val publicPoint = checkNotNull(WCurve.multiply(WCurve.generator, ephemeral))
    private val publicX = WCurve.montgomeryX(publicPoint)
    private val publicKey = publicX + byteArrayOf(if (publicPoint.y.isOdd) 1 else 0)
    private var transcript = ByteArray(0)
    private var shared = ByteArray(0)
    private var clientConfirmation = ByteArray(0)
    private var secret = ByteArray(0)
    private var expected = ByteArray(0)

    fun hello(): ByteArray = user.encodeToByteArray() + byteArrayOf(0) + publicKey

    // a router answers a name it does not know with 33 bytes, a key without a salt; they get no proof,
    // which would let whoever sent them try passwords offline
    fun confirm(challenge: ByteArray): ByteArray {
        if (challenge.size == UNKNOWN_NAME_SIZE) throw DeviceException(ErrorCategory.PermissionDenied, WRONG_LOGIN)
        if (challenge.size != CHALLENGE_SIZE) throw DeviceException(ErrorCategory.Unsupported, "unexpected EC-SRP5 challenge of ${challenge.size} bytes")
        val serverX = challenge.copyOfRange(0, 32)
        val salt = challenge.copyOfRange(33, 49)
        val verifier = passwordVerifier(account, password, salt)
        val verifierX = WCurve.montgomeryX(checkNotNull(WCurve.multiply(WCurve.generator, verifier)))
        val serverKey = WCurve.lift(Fe.fromBytes(serverX), challenge[32].toInt() != 0)
            ?: throw unusable()
        val blinded = WCurve.add(serverKey, passwordMask(verifierX, odd = true)) ?: throw unusable()
        transcript = sha256(publicX, serverX)
        val scalar = mulAddMod(verifier, U256.fromBytes(transcript), ephemeral, WCurve.order)
        shared = WCurve.montgomeryX(WCurve.multiply(blinded, scalar) ?: throw unusable())
        val sharedNumber = shared.withoutLeadingZeros()
        clientConfirmation = sha256(transcript, sharedNumber)
        secret = sha256(sharedNumber)
        expected = sha256(transcript, clientConfirmation, sharedNumber)
        return clientConfirmation
    }

    fun verify(serverConfirmation: ByteArray): Boolean = sameBytes(serverConfirmation, expected)

    fun keys(): StreamKeys = streamKeys(secret, server = false)

    private fun unusable() = DeviceException(ErrorCategory.Unsupported, "the router sent a key that gives no shared secret")
}
