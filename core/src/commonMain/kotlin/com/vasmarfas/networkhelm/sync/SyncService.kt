package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.serialization.Serializable

// a file as the service lists it, with a tag that changes when the file does; a tag of the list is compared only with tags of the list
@Serializable
class Listed(val path: String, val tag: String)

// a file as read, with the tag writeIf takes to write over it
class Stored(val bytes: ByteArray, val tag: String)

// where the files of a vault lie: the folder networkhelm-sync of a service, its paths vault, devices/<id> and state/<id>. A device
// writes only its own devices/<id> and state/<id>, so only the vault, which any device may rewrite, is written on a condition
interface SyncService {
    // the files of the vault, without the copies of a conflict that clients of cloud folders leave and without files of others
    suspend fun list(): List<Listed>

    suspend fun read(path: String): Stored?

    suspend fun write(path: String, bytes: ByteArray)

    // writes where no file is (over null) or over the file as it was read; false when another device wrote there first. A service
    // that cannot write on a condition checks the file right before it writes
    suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean

    // a file that is not there is deleted as well
    suspend fun delete(path: String)
}

const val SYNC_FOLDER = "networkhelm-sync"

// the id of a device is 32 hex digits: a copy of a conflict, a file half written and a file of someone else never match
private val SYNC_FILE = Regex("$VAULT|(devices|state)/[0-9a-f]{32}")

fun syncFile(path: String) = SYNC_FILE.matches(path)

internal fun loginRefused(host: String) = DeviceException(ErrorCategory.PermissionDenied, "$host did not accept the login")

internal fun answered(host: String, status: String) = DeviceException(ErrorCategory.Api, "$host answered HTTP $status")
