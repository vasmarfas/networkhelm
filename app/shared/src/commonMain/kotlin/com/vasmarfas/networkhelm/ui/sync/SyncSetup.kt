package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.Secrets
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.sync.Cloud
import com.vasmarfas.networkhelm.sync.Folder
import com.vasmarfas.networkhelm.sync.FolderService
import com.vasmarfas.networkhelm.sync.GatewayService
import com.vasmarfas.networkhelm.sync.GitHost
import com.vasmarfas.networkhelm.sync.GitService
import com.vasmarfas.networkhelm.sync.Keys
import com.vasmarfas.networkhelm.sync.S3Service
import com.vasmarfas.networkhelm.sync.SYNC_MEMORY
import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.SyncHttp
import com.vasmarfas.networkhelm.sync.SyncService
import com.vasmarfas.networkhelm.sync.WebDavService
import com.vasmarfas.networkhelm.sync.cloudService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

// how this device reaches the service of its vault; the password, token or key of the service lies in the protected store
@Serializable
sealed interface ServiceSettings {
    // a folder by the bookmark the platform keeps access to it with, and its name to show
    @Serializable
    @SerialName("folder")
    data class Place(val place: String, val name: String = "") : ServiceSettings

    @Serializable
    @SerialName("webdav")
    data class WebDav(val base: String, val user: String) : ServiceSettings

    @Serializable
    @SerialName("s3")
    data class S3(val endpoint: String, val bucket: String, val region: String, val access: String) : ServiceSettings

    @Serializable
    @SerialName("git")
    data class Git(val host: GitHost, val api: String, val owner: String, val repository: String, val branch: String) : ServiceSettings

    @Serializable
    @SerialName("gateway")
    data class Gateway(val address: String) : ServiceSettings

    // a cloud this device signed in to in the browser; its tokens stay on this device
    @Serializable
    @SerialName("cloud")
    data class Account(val cloud: Cloud) : ServiceSettings
}

// another place that keeps a whole copy of the vault; the secret of its service and the memory of the vault there lie under its id
@Serializable
data class SyncPlace(val id: String, val service: ServiceSettings)

// synchronisation as this device has it: the vault and the epoch of its key, the device and its name, the service and the other places of
// the vault, the kinds of data that stay on the device and whether passwords and keys go; the keys of the vault and the secrets of the
// services lie in the protected store
@Serializable
data class SyncSetup(
    val vault: String,
    val epoch: Int,
    val device: String,
    val name: String,
    val service: ServiceSettings,
    val leftOut: Set<String> = emptySet(),
    val secrets: Boolean = false,
    val more: List<SyncPlace> = emptyList(),
)

// every place of the vault, the service of the setup first under the id ""
val SyncSetup.places get() = listOf(SyncPlace("", service)) + more

const val SYNC_SETUP = "sync.json"
private const val MASTER = "sync/master"
private const val KEY = "sync/key"
private const val SERVICE_SECRET = "sync/service"
private const val SECRET_KEY = "sync/secret"

internal fun placeMemory(id: String) = if (id.isEmpty()) SYNC_MEMORY else "sync-memory-$id.json"

private fun placeSecret(id: String) = if (id.isEmpty()) SERVICE_SECRET else "$SERVICE_SECRET-$id"

private val setupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

// null where synchronisation was never set up on this device or was turned off
suspend fun syncSetup(storage: Storage): SyncSetup? = storage.read(SYNC_SETUP)?.takeIf { it.isNotBlank() }?.let { setupJson.decodeFromString(SyncSetup.serializer(), it) }

// the key of the data stays and the master key does not: the code for another device and a new password make it again from the password.
// The secret key of a vault that has one stays too, as both need it
suspend fun keepSyncSetup(storage: Storage, secrets: Secrets, setup: SyncSetup, keys: Keys, serviceSecret: String, secretKey: ByteArray? = null) {
    secrets.put(KEY, Base64.encode(keys.key))
    secrets.put(SERVICE_SECRET, serviceSecret)
    if (secretKey != null) secrets.put(SECRET_KEY, Base64.encode(secretKey)) else secrets.delete(SECRET_KEY)
    keepSetup(storage, setup)
}

suspend fun keepSetup(storage: Storage, setup: SyncSetup) = storage.write(SYNC_SETUP, setupJson.encodeToString(SyncSetup.serializer(), setup))

// the data stay on the device; the keys, the secrets of the services and the memory of the vault leave it, so a vault joined later starts
// from nothing it knew of this one
suspend fun forgetSyncSetup(storage: Storage, secrets: Secrets) {
    val ids = syncSetup(storage)?.places.orEmpty().map { it.id }.ifEmpty { listOf("") }
    storage.write(SYNC_SETUP, "")
    for (id in ids) {
        storage.write(placeMemory(id), "")
        secrets.delete(placeSecret(id))
    }
    for (key in listOf(MASTER, KEY, SECRET_KEY)) secrets.delete(key)
}

internal suspend fun syncKeys(setup: SyncSetup, secrets: Secrets): Keys? {
    val key = secrets.get(KEY) ?: return null
    return Keys(setup.vault, setup.epoch, ByteArray(0), Base64.decode(key))
}

internal suspend fun serviceSecret(secrets: Secrets, place: String = "") = secrets.get(placeSecret(place)).orEmpty()

// a cloud renews its tokens as it goes, and the device keeps the new ones
internal suspend fun keepServiceSecret(secrets: Secrets, secret: String, place: String = "") = secrets.put(placeSecret(place), secret)

internal suspend fun forgetServiceSecret(secrets: Secrets, place: String) = secrets.delete(placeSecret(place))

internal suspend fun syncSecretKey(secrets: Secrets): ByteArray? = secrets.get(SECRET_KEY)?.let { Base64.decode(it) }

// how a new device reaches the service, for the code another device shows: not a folder, which each device picks for itself, and
// without the tokens of a cloud, which each device gets with a sign-in of its own
internal fun serviceJson(settings: ServiceSettings, secret: String): JsonObject? = if (settings is ServiceSettings.Place) {
    null
} else {
    val shown = if (settings is ServiceSettings.Account) "" else secret
    JsonObject(mapOf("settings" to setupJson.encodeToJsonElement(ServiceSettings.serializer(), settings), "secret" to JsonPrimitive(shown)))
}

internal fun serviceOf(json: JsonObject): Pair<ServiceSettings, String> = try {
    setupJson.decodeFromJsonElement(ServiceSettings.serializer(), json.getValue("settings")) to json.getValue("secret").jsonPrimitive.content
} catch (e: IllegalArgumentException) {
    throw DeviceException(ErrorCategory.ArgumentValue, "the code is not one of NetworkHelm synchronisation")
} catch (e: NoSuchElementException) {
    throw DeviceException(ErrorCategory.ArgumentValue, "the code is not one of NetworkHelm synchronisation")
}

internal fun keysGone() =
    DeviceException(ErrorCategory.MissingItem, "the keys of synchronisation are not in the protected store of this device: turn synchronisation off and join the vault again")

internal fun folderGone(settings: ServiceSettings) =
    DeviceException(ErrorCategory.MissingItem, "the folder of synchronisation is not available: ${(settings as? ServiceSettings.Place)?.name.orEmpty()}")

// the HTTP is made only for a service on a network, and only here; keep takes the tokens a cloud renews
internal fun syncService(settings: ServiceSettings, secret: String, http: () -> SyncHttp, folder: (String) -> Folder?, keep: suspend (String) -> Unit = {}): SyncService? = when (settings) {
    is ServiceSettings.Place -> folder(settings.place)?.let(::FolderService)
    is ServiceSettings.WebDav -> WebDavService(httpsFor(settings.base, http), settings.base, settings.user, secret)
    is ServiceSettings.S3 -> S3Service(httpsFor(settings.endpoint, http), settings.endpoint, settings.bucket, settings.region, settings.access, secret)
    is ServiceSettings.Git -> GitService(httpsFor(settings.api, http), settings.host, settings.api, settings.owner, settings.repository, settings.branch, secret)
    is ServiceSettings.Gateway -> GatewayService(httpsFor(settings.address, http), settings.address, secret)
    is ServiceSettings.Account -> cloudService(settings.cloud, signInWay ?: SignInWay.Loopback, http(), secret, keep)
}

// a password, token or key of a service goes over HTTPS only
internal fun httpsFor(address: String, http: () -> SyncHttp): SyncHttp {
    if (!address.startsWith("https://", ignoreCase = true)) throw DeviceException(ErrorCategory.ArgumentValue, "the address of the service must start with https://")
    return http()
}
