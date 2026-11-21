package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Secrets
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.app.demoRouters
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.app.snmpPrivacyKey
import com.vasmarfas.networkhelm.app.sshKeyKey
import com.vasmarfas.networkhelm.app.sshPassphraseKey
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.Sodium
import com.vasmarfas.networkhelm.sync.SyncLocal
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.ui.main.DockerTemplates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

// the passwords and keys of the saved routers, each kind with the key of the protected store that holds it
private val SECRETS: Map<String, (String, String) -> String> = mapOf(
    "secret.password" to ::passwordKey,
    "secret.ssh-key" to ::sshKeyKey,
    "secret.ssh-passphrase" to ::sshPassphraseKey,
    "secret.snmp-privacy" to ::snmpPrivacyKey,
)

private const val HASH_KEY = "sync/secret-hash"

// the stores of the app as synchronisation sees them. Passwords and keys go only when the user lets them: the records file of each kind
// holds a hash of the value under a key of the protected store, while the value goes from the protected store into the state and from a
// state into the protected store, never into a file in the clear. Fingerprints go where the trust store keeps them from one run to the next
internal class SyncStores(
    private val routers: SavedRouters,
    private val snippets: Snippets,
    private val settings: AppSettings,
    private val templates: DockerTemplates,
    private val trust: TrustRecords?,
    files: RouterFiles,
    private val secrets: Secrets,
    storage: Storage,
    private val out: Set<String>,
    private val withSecrets: Boolean,
    clock: HlcClock = HlcClock(),
) : SyncLocal {
    private val secretRecords = SECRETS.keys.associateWith { FileRecords(storage, it.replace('.', '-') + ".json", it, clock) }
    private val routerRecords = RouterRecords(files, storage, clock)

    // the demonstration routers the page in a browser starts with stay on it
    private val demo = demoRouters().map { recordId(it.address, it.user) }.toSet()

    // the records this device gave last, by kind: a store takes the merged records only where they differ from these
    private var given = emptyMap<String, Set<Record>>()

    override val kinds = setOf("router", "snippet", "setting", "theme", "docker-template", "workspace", "tunnel") + SECRETS.keys + setOfNotNull("fingerprint".takeIf { trust != null })

    override fun leftOut(kind: String) = kind in out || kind in SECRETS && !withSecrets

    override suspend fun records(): List<Record> {
        routers.load()
        snippets.load()
        templates.load()
        val shared = settings.records().filterNot { it.kind == "setting" && it.id in AppSettings.DEVICE_SETTINGS }
        val windows = routerRecords.workspaces() + routerRecords.tunnels()
        val fingerprints = trust?.records().orEmpty()
        val values = if (withSecrets) secretValues() else emptyList()
        val stored = routers.records.take().filterNot { it.id in demo } + snippets.records.take() + shared + templates.records.take() + windows + fingerprints + values
        return stored.filterNot { leftOut(it.kind) }.also { records -> given = records.groupBy { it.kind }.mapValues { it.value.toSet() } }
    }

    // the routers first, so that the passwords that come with them find them on the next round
    override suspend fun apply(records: List<Record>) {
        val merged = records.groupBy { it.kind }
        fun changed(kind: String) = !leftOut(kind) && merged[kind].orEmpty().toSet() != given[kind].orEmpty()
        if (changed("router")) routers.apply(merged["router"].orEmpty() + routers.records.all.filter { it.id in demo })
        if (changed("snippet")) snippets.apply(merged["snippet"].orEmpty())
        if (changed("setting")) settings.applySettings(merged["setting"].orEmpty())
        if (changed("theme")) settings.applyThemes(merged["theme"].orEmpty())
        if (changed("docker-template")) templates.apply(merged["docker-template"].orEmpty())
        if (changed("workspace")) routerRecords.applyWorkspaces(merged["workspace"].orEmpty())
        if (changed("tunnel")) routerRecords.applyTunnels(merged["tunnel"].orEmpty())
        if (trust != null && changed("fingerprint")) trust.apply(merged["fingerprint"].orEmpty())
        for ((kind, keyOf) in SECRETS) if (changed(kind)) applySecrets(kind, merged[kind].orEmpty(), keyOf)
    }

    // the records of the secrets the protected store holds, each with its value for the state
    private suspend fun secretValues(): List<Record> {
        val hashKey = hashKey()
        return SECRETS.flatMap { (kind, keyOf) ->
            val values = matchSecrets(kind, keyOf, hashKey)
            secretRecords.getValue(kind).take().map { record ->
                val secret = values[record.id]?.takeUnless { record.deleted } ?: return@map record
                record.copy(data = entry(secret.first, secret.second, "value", secret.third))
            }
        }
    }

    // the secrets of a kind matched with their records: those of the saved routers and those that came before, as a password may come
    // for a router that stays on another device
    private suspend fun matchSecrets(kind: String, keyOf: (String, String) -> String, hashKey: ByteArray): Map<String, Triple<String, String, String>> {
        val file = secretRecords.getValue(kind)
        val came = file.stored().filterNot { it.deleted }.mapNotNull { record ->
            record.data["address"]?.jsonPrimitive?.content?.let { address -> record.data["user"]?.jsonPrimitive?.content?.let { address to it } }
        }
        val values = (routers.routers.value.map { it.address to it.user } + came).distinct().mapNotNull { (address, user) ->
            secrets.get(keyOf(address, user))?.let { recordId(address, user) to Triple(address, user, it) }
        }.toMap()
        file.match { values.mapValues { (_, secret) -> entry(secret.first, secret.second, "hash", hash(secret.third, hashKey)) } }
        return values
    }

    private suspend fun applySecrets(kind: String, merged: List<Record>, keyOf: (String, String) -> String) {
        val hashKey = hashKey()
        matchSecrets(kind, keyOf, hashKey)
        val file = secretRecords.getValue(kind)
        // a secret changed since the round read them waits with the merged ones for the next round
        if (file.edited) return
        val known = file.all.associateBy { it.id }
        val kept = merged.map { record ->
            val own = known[record.id]
            // a tombstone holds no data: the router it was for is the one this device knew it by
            val data = if (record.deleted) own?.data else record.data
            val address = data?.get("address")?.jsonPrimitive?.content
            val user = data?.get("user")?.jsonPrimitive?.content
            val value = record.data["value"]?.jsonPrimitive?.content
            when {
                own != null && own.hlc == record.hlc && own.deleted == record.deleted -> own
                // a secret this device cannot place keeps its stamp in the file, and its value stays in the state alone
                address == null || user == null -> record.copy(data = JsonObject(record.data - "value"))
                record.deleted -> record.also { secrets.delete(keyOf(address, user)) }
                value == null -> record
                else -> {
                    secrets.put(keyOf(address, user), value)
                    record.copy(data = entry(address, user, "hash", hash(value, hashKey)))
                }
            }
        }
        file.keep(kept)
    }

    private suspend fun hashKey(): ByteArray = secrets.get(HASH_KEY)?.let { Base64.decode(it) } ?: Sodium.random(Sodium.KEY).also { secrets.put(HASH_KEY, Base64.encode(it)) }

    private suspend fun hash(value: String, key: ByteArray) = Base64.encode(Sodium.blake2b(value.encodeToByteArray(), key, Sodium.KEY))

    private fun entry(address: String, user: String, name: String, value: String) =
        JsonObject(mapOf("address" to JsonPrimitive(address), "user" to JsonPrimitive(user), name to JsonPrimitive(value)))
}
