package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

const val VAULT = "vault"

fun devicePath(device: String) = "devices/$device"

fun statePath(device: String) = "state/$device"

@Serializable
data class Schema(val major: Int, val minor: Int)

// the schema this version writes: minor grows with what older versions read and keep, major with what they cannot read. 1.1: the fields
// of a record carry stamps of their own
val SCHEMA = Schema(1, 1)

// read before the file is opened and taken whole into its associated data, the unknown fields of a newer version with it;
// password and reader belong to the vault
@Serializable
data class Header(
    val kind: String,
    val vault: String,
    val epoch: Int,
    val author: String,
    val version: Long,
    val schema: Schema,
    val salt: String = "",
    val nonce: String = "",
    val commitment: String = "",
    val password: Password? = null,
    val reader: Int? = null,
)

internal val fileJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

private val MAGIC = "NHS".encodeToByteArray()
private const val ENVELOPE = 1
private const val FILE_SALT = 32
private const val COMMITTED = 32
private const val BLOCK = 4096
private val COMMITMENT = "NetworkHelm key commitment".encodeToByteArray()

internal suspend fun seal(header: Header, path: String, content: ByteArray, kindKey: ByteArray) =
    seal(header, path, content, kindKey, Sodium.random(FILE_SALT), Sodium.random(Sodium.NONCE))

// NHS, the version of the envelope, the length of the header, the header and the encrypted text; everything before the text and
// the path are its associated data, so no field can be changed and no file moved to another place unseen. The salt gives the file a
// key of its own, so no nonce repeats under one key, and the text is padded with spaces after its JSON to a multiple of 4 KiB
internal suspend fun seal(header: Header, path: String, content: ByteArray, kindKey: ByteArray, salt: ByteArray, nonce: ByteArray): ByteArray {
    val key = Sodium.blake2b(salt, kindKey, Sodium.KEY)
    val committed = header.copy(salt = Base64.encode(salt), nonce = Base64.encode(nonce), commitment = Base64.encode(Sodium.blake2b(COMMITMENT, key, COMMITTED)))
    val text = fileJson.encodeToString(Header.serializer(), committed).encodeToByteArray()
    val prefix = MAGIC + byteArrayOf(0, ENVELOPE.toByte(), (text.size shr 8).toByte(), text.size.toByte()) + text
    val spare = (BLOCK - (prefix.size + content.size + Sodium.TAG) % BLOCK) % BLOCK
    return prefix + Sodium.encrypt(content + ByteArray(spare) { ' '.code.toByte() }, prefix + path.encodeToByteArray(), nonce, key)
}

// a file as the service gave it, its form checked before any key is tried
internal class Sealed(
    val header: Header,
    private val prefix: ByteArray,
    private val text: ByteArray,
    private val salt: ByteArray,
    private val nonce: ByteArray,
    private val commitment: ByteArray,
) {
    // null when the file was not written under this key of its kind: the commitment says so before anything is decrypted, so one text
    // cannot open under two keys
    suspend fun open(path: String, kindKey: ByteArray): ByteArray? {
        val key = Sodium.blake2b(salt, kindKey, Sodium.KEY)
        if (!Sodium.blake2b(COMMITMENT, key, COMMITTED).contentEquals(commitment)) return null
        return Sodium.decrypt(text, prefix + path.encodeToByteArray(), nonce, key) ?: throw DeviceException(ErrorCategory.General, "$path was changed after it was written")
    }
}

internal fun sealed(bytes: ByteArray, path: String): Sealed {
    if (bytes.size < 7 || !bytes.copyOf(3).contentEquals(MAGIC)) throw notAFile(path)
    val envelope = (bytes[3].toInt() and 0xFF) shl 8 or (bytes[4].toInt() and 0xFF)
    if (envelope > ENVELOPE) throw newer(path)
    val end = 7 + ((bytes[5].toInt() and 0xFF) shl 8 or (bytes[6].toInt() and 0xFF))
    if (envelope < ENVELOPE || bytes.size < end + Sodium.TAG) throw notAFile(path)
    return try {
        val header = fileJson.decodeFromString(Header.serializer(), bytes.decodeToString(7, end))
        val (salt, nonce, commitment) = listOf(header.salt, header.nonce, header.commitment).map { Base64.decode(it) }
        Sealed(header, bytes.copyOf(end), bytes.copyOfRange(end, bytes.size), salt, nonce, commitment).takeIf {
            salt.size == FILE_SALT && nonce.size == Sodium.NONCE && commitment.size == COMMITTED
        }
    } catch (e: IllegalArgumentException) {
        null
    } ?: throw notAFile(path)
}

internal fun notAFile(path: String) = DeviceException(ErrorCategory.General, "$path is not a file of a NetworkHelm vault")

internal fun newer(path: String) = DeviceException(ErrorCategory.Unsupported, "$path was written by a newer version of the app: update it to synchronise")
