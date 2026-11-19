package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

// how the master key comes from the password: Argon2id with these parameters, then the secret key of 128 bits on top where the
// vault was made with one
@Serializable
data class Password(val salt: String, val passes: Int, val memory: Int, val secret: Boolean = false)

const val PASSES = 3
const val MEMORY = 256 * 1024 * 1024
const val SECRET = 16

// the bound against a vault, real or put in its place, that would make a device run Argon2id until it runs out of memory: no more
// memory than the app gives a vault itself, which a phone and a page in a browser hold
private const val MOST_PASSES = 10

// what a device keeps once it opened the vault: the master key from the password and the key of the data of the current epoch
class Keys(val vault: String, val epoch: Int, val master: ByteArray, val key: ByteArray)

@Serializable
private class Wrapped(val key: String)

suspend fun master(password: String, secret: ByteArray?, lock: Password): ByteArray {
    if (lock.passes !in 1..MOST_PASSES || lock.memory !in 8192..MEMORY) {
        throw DeviceException(ErrorCategory.Unsupported, "the vault asks for Argon2id with ${lock.passes} passes and ${lock.memory} bytes of memory, which this app does not run")
    }
    val salt = try {
        Base64.decode(lock.salt)
    } catch (e: IllegalArgumentException) {
        throw notAFile(VAULT)
    }
    if (salt.size != Sodium.SALT) throw notAFile(VAULT)
    // libsodium takes a password in ASCII only, so it goes in as the hex of its UTF-8, composed as NFC: one password typed on any system
    // gives one key
    val stretched = Sodium.argon2id(hex(nfc(password).encodeToByteArray()), salt, lock.passes, lock.memory, Sodium.KEY)
    if (!lock.secret) return stretched
    val key = secret ?: throw DeviceException(ErrorCategory.PermissionDenied, "the vault needs its secret key as well as the password")
    require(key.size == SECRET) { "the secret key is ${key.size} bytes, not $SECRET" }
    return Sodium.blake2b(stretched, key, Sodium.KEY)
}

internal expect fun nfc(text: String): String

// a new vault: the key of the data is random and lies on the service only under the master key
suspend fun createVault(password: String, secret: ByteArray?, device: String, passes: Int = PASSES, memory: Int = MEMORY): Pair<ByteArray, Keys> =
    wrap(hex(Sodium.random(16)), 1, Sodium.random(Sodium.KEY), password, secret, device, 1, passes, memory)

// the same key of the data under a new password or new parameters: the other devices go on with the key they keep
suspend fun rewrap(keys: Keys, vault: Header, password: String, secret: ByteArray?, device: String, passes: Int = PASSES, memory: Int = MEMORY): Pair<ByteArray, Keys> =
    wrap(keys.vault, keys.epoch, keys.key, password, secret, device, vault.version + 1, passes, memory)

// a new key of the data in a new epoch: a device that does not get the new password reads nothing written after it
suspend fun rekey(keys: Keys, vault: Header, password: String, secret: ByteArray?, device: String, passes: Int = PASSES, memory: Int = MEMORY): Pair<ByteArray, Keys> =
    wrap(keys.vault, keys.epoch + 1, Sodium.random(Sodium.KEY), password, secret, device, vault.version + 1, passes, memory)

private suspend fun wrap(
    vault: String,
    epoch: Int,
    key: ByteArray,
    password: String,
    secret: ByteArray?,
    device: String,
    version: Long,
    passes: Int,
    memory: Int,
): Pair<ByteArray, Keys> {
    val lock = Password(Base64.encode(Sodium.random(Sodium.SALT)), passes, memory, secret != null)
    val master = master(password, secret, lock)
    val header = Header(VAULT, vault, epoch, device, version, SCHEMA, password = lock, reader = SCHEMA.major)
    val content = fileJson.encodeToString(Wrapped.serializer(), Wrapped(Base64.encode(key))).encodeToByteArray()
    return seal(header, VAULT, content, vaultKey(master)) to Keys(vault, epoch, master, key)
}

// the header as the service gave it, read before the password is asked: whether this version can take part, what the password needs
fun vaultHeader(bytes: ByteArray): Header = vaultFile(bytes).header

suspend fun unlock(bytes: ByteArray, password: String, secret: ByteArray?): Keys {
    val file = vaultFile(bytes)
    val lock = file.header.password ?: throw notAFile(VAULT)
    return open(file, master(password, secret, lock)) ?: throw DeviceException(ErrorCategory.PermissionDenied, "the password does not open the vault")
}

// with the master key this device keeps or got from another one
suspend fun unlock(bytes: ByteArray, master: ByteArray): Keys =
    open(vaultFile(bytes), master) ?: throw DeviceException(ErrorCategory.PermissionDenied, "this key does not open the vault")

private fun vaultFile(bytes: ByteArray): Sealed {
    val file = sealed(bytes, VAULT)
    val reader = file.header.reader ?: throw notAFile(VAULT)
    if (file.header.kind != VAULT) throw notAFile(VAULT)
    if (reader > SCHEMA.major) throw newer(VAULT)
    return file
}

private suspend fun open(file: Sealed, master: ByteArray): Keys? {
    val content = file.open(VAULT, vaultKey(master)) ?: return null
    val key = try {
        Base64.decode(fileJson.decodeFromString(Wrapped.serializer(), content.decodeToString()).key)
    } catch (e: IllegalArgumentException) {
        throw notAFile(VAULT)
    }
    if (key.size != Sodium.KEY) throw notAFile(VAULT)
    return Keys(file.header.vault, file.header.epoch, master, key)
}

internal suspend fun vaultKey(master: ByteArray) = Sodium.derive(master, 0, "NHSvault", Sodium.KEY)
