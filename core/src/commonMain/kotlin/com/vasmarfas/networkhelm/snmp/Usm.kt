package com.vasmarfas.networkhelm.snmp

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.md5
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.TimeSource

enum class AuthProtocol { Md5, Sha1 }

enum class PrivProtocol { Des, Aes }

@Serializable
class SnmpUser(val name: String, val auth: AuthProtocol? = null, val authPassword: String = "", val priv: PrivProtocol? = null, val privPassword: String = "") {
    init {
        require(priv == null || auth != null) { "privacy of SNMPv3 takes authentication" }
    }
}

private const val VERSION3 = 3
private const val USM_MODEL = 3
private const val MAX_SIZE = 65507
private const val AUTH = 1
private const val PRIV = 2
private const val REPORTABLE = 4

internal val notInTimeWindows = oid("1.3.6.1.6.3.15.1.1.2.0")
internal val unknownEngineIds = oid("1.3.6.1.6.3.15.1.1.4.0")
private val refusals = mapOf(
    oid("1.3.6.1.6.3.15.1.1.1.0") to "the SNMP agent does not take this level of security for the user",
    oid("1.3.6.1.6.3.15.1.1.3.0") to "the SNMP agent knows no such user",
    oid("1.3.6.1.6.3.15.1.1.5.0") to "the SNMP agent refused the authentication: a wrong password or protocol",
    oid("1.3.6.1.6.3.15.1.1.6.0") to "the SNMP agent could not decrypt the request: a wrong privacy password or protocol",
)

internal class Report(val msgId: Int, val counter: Oid?) : Exception("SNMP report $counter")

internal class Engine(val id: ByteArray, val boots: Int, val time: Int)

internal fun discovery(msgId: Int): ByteArray =
    message(msgId, REPORTABLE, parameters(ByteArray(0), 0, 0, "", ByteArray(0), ByteArray(0)), scoped(ByteArray(0), pdu(GET, msgId, 0, 0, emptyList())))

internal fun discovered(bytes: ByteArray, msgId: Int): Engine? {
    val message = Parsed(bytes)
    if (message.id != msgId || message.engine.isEmpty()) return null
    return Engine(message.engine, message.boots, message.time)
}

internal class Usm(private val user: SnmpUser, private val engine: Engine) {
    private val hash: ((ByteArray) -> ByteArray)? = when (user.auth) {
        AuthProtocol.Md5 -> ::md5
        AuthProtocol.Sha1 -> ::sha1
        null -> null
    }

    private val auth = hash?.let { it to localized(it, user.authPassword, engine.id) }
    private val privKey = hash?.takeIf { user.priv != null }?.let { localized(it, user.privPassword, engine.id) }
    private val level = (if (auth != null) AUTH else 0) or (if (privKey != null) PRIV else 0)
    private var boots = engine.boots
    private var time = engine.time
    private var since = TimeSource.Monotonic.markNow()
    private var salt = Random.nextLong()

    private fun now() = time + since.elapsedNow().inWholeSeconds.toInt()

    // the HMAC is taken over the message with twelve zero bytes in the authentication field and cut to 12 bytes
    fun request(pdu: ByteArray, msgId: Int): ByteArray {
        val flags = level or REPORTABLE
        val clock = now()
        val plain = scoped(engine.id, pdu)
        val (data, privacy) = if (privKey == null) plain to ByteArray(0) else seal(privKey, plain, clock)
        val draft = message(msgId, flags, parameters(engine.id, boots, clock, user.name, if (auth == null) ByteArray(0) else ByteArray(12), privacy), data)
        val (hash, key) = auth ?: return draft
        return message(msgId, flags, parameters(engine.id, boots, clock, user.name, hmac(hash, key, draft).copyOf(12), privacy), data)
    }

    fun open(bytes: ByteArray): Pair<Int, Tlv> {
        val message = Parsed(bytes)
        if (message.flags and AUTH != 0) {
            val (hash, key) = auth ?: throw malformed("an authenticated message to a user without authentication")
            val (from, until) = message.authentication
            val zeroed = bytes.copyOf().also { it.fill(0, from, until) }
            if (until - from != 12 || !hmac(hash, key, zeroed).copyOf(12).contentEquals(bytes.copyOfRange(from, until))) throw malformed("a message its authentication does not prove")
            boots = message.boots
            time = message.time
            since = TimeSource.Monotonic.markNow()
        }
        val scoped = if (message.flags and PRIV != 0) {
            val key = privKey ?: throw malformed("an encrypted message to a user without privacy")
            open(key, message.data, message.privacy, message.boots, message.time)
        } else {
            message.data
        }
        val pdu = tlv(scoped, 0, scoped.size).children().getOrNull(2) ?: throw malformed("a scoped PDU without its PDU")
        // a report tells why the agent refused, and the agent sends it as it can; an answer below the security of the user proves nothing
        if (pdu.tag != REPORT && message.flags and level != level) throw malformed("an answer below the security of the user")
        return message.id to pdu
    }

    fun response(bytes: ByteArray): Pair<Int, Response> {
        val (msgId, pdu) = open(bytes)
        if (pdu.tag == REPORT) {
            val counter = pdu.children().getOrNull(3)?.children()?.firstOrNull()?.children()?.firstOrNull()?.oid()
            refusals[counter]?.let { throw DeviceException(ErrorCategory.PermissionDenied, "$it (${user.name})") }
            throw Report(msgId, counter)
        }
        return msgId to response(pdu)
    }

    private fun seal(key: ByteArray, plain: ByteArray, clock: Int): Pair<ByteArray, ByteArray> {
        salt++
        return when (user.priv) {
            PrivProtocol.Aes -> {
                val privacy = long(salt)
                encoded(OCTETS, aesCfb(key.copyOf(16), int(boots) + int(clock) + privacy, plain, encrypt = true)) to privacy
            }
            else -> {
                val privacy = int(boots) + int(salt.toInt())
                val iv = ByteArray(8) { (key[8 + it].toInt() xor privacy[it].toInt()).toByte() }
                encoded(OCTETS, desCbc(key.copyOf(8), iv, plain.copyOf((plain.size + 7) / 8 * 8), encrypt = true)) to privacy
            }
        }
    }

    private fun open(key: ByteArray, data: ByteArray, privacy: ByteArray, boots: Int, time: Int): ByteArray {
        if (privacy.size != 8) throw malformed("privacy parameters of ${privacy.size} bytes")
        return when (user.priv) {
            PrivProtocol.Aes -> aesCfb(key.copyOf(16), int(boots) + int(time) + privacy, data, encrypt = false)
            else -> {
                if (data.size % 8 != 0) throw malformed("a DES message of ${data.size} bytes")
                desCbc(key.copyOf(8), ByteArray(8) { (key[8 + it].toInt() xor privacy[it].toInt()).toByte() }, data, encrypt = false)
            }
        }
    }
}

internal fun localized(hash: (ByteArray) -> ByteArray, password: String, engine: ByteArray): ByteArray {
    val bytes = password.encodeToByteArray()
    if (bytes.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a password of SNMPv3 cannot be empty")
    val key = hash(ByteArray(1_048_576) { bytes[it % bytes.size] })
    return hash(key + engine + key)
}

private fun int(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

private fun long(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

private fun message(msgId: Int, flags: Int, parameters: ByteArray, data: ByteArray) = encoded(
    SEQUENCE,
    integer(VERSION3.toLong()) +
        encoded(SEQUENCE, integer(msgId.toLong()) + integer(MAX_SIZE.toLong()) + encoded(OCTETS, byteArrayOf(flags.toByte())) + integer(USM_MODEL.toLong())) +
        encoded(OCTETS, parameters) + data,
)

private fun parameters(engine: ByteArray, boots: Int, time: Int, user: String, authentication: ByteArray, privacy: ByteArray) = encoded(
    SEQUENCE,
    encoded(OCTETS, engine) + integer(boots.toLong()) + integer(time.toLong()) + encoded(OCTETS, user.encodeToByteArray()) +
        encoded(OCTETS, authentication) + encoded(OCTETS, privacy),
)

private fun scoped(engine: ByteArray, pdu: ByteArray) = encoded(SEQUENCE, encoded(OCTETS, engine) + encoded(OCTETS, ByteArray(0)) + pdu)

private class Parsed(bytes: ByteArray) {
    val id: Int
    val flags: Int
    val engine: ByteArray
    val boots: Int
    val time: Int
    val authentication: Pair<Int, Int>
    val privacy: ByteArray
    val data: ByteArray

    init {
        val parts = tlv(bytes, 0, bytes.size).takeIf { it.tag == SEQUENCE }?.children() ?: throw malformed("no message")
        if (parts.size != 4 || parts[0].signed() != VERSION3.toLong()) throw malformed("a message that is not of SNMPv3")
        val header = parts[1].children()
        if (header.size != 4 || header[3].signed() != USM_MODEL.toLong()) throw malformed("a message of another security model")
        id = header[0].signed().toInt()
        flags = header[2].content().firstOrNull()?.toInt() ?: 0
        val usm = tlv(bytes, parts[2].start, parts[2].end).children()
        if (usm.size != 6) throw malformed("security parameters of ${usm.size} fields")
        engine = usm[0].content()
        boots = usm[1].signed().toInt()
        time = usm[2].signed().toInt()
        authentication = usm[4].start to usm[4].end
        privacy = usm[5].content()
        data = if (parts[3].tag == OCTETS) parts[3].content() else parts[3].whole()
    }
}
