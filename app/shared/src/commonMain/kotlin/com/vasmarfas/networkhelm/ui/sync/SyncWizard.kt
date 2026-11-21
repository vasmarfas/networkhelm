package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.sync.Header
import com.vasmarfas.networkhelm.sync.Cloud
import com.vasmarfas.networkhelm.sync.KeyChangedException
import com.vasmarfas.networkhelm.sync.Keys
import com.vasmarfas.networkhelm.sync.MEMORY
import com.vasmarfas.networkhelm.sync.PASSES
import com.vasmarfas.networkhelm.sync.SECRET
import com.vasmarfas.networkhelm.sync.SYNC_MEMORY
import com.vasmarfas.networkhelm.sync.Sodium
import com.vasmarfas.networkhelm.sync.Stored
import com.vasmarfas.networkhelm.sync.SyncHttp
import com.vasmarfas.networkhelm.sync.SyncService
import com.vasmarfas.networkhelm.sync.Transfer
import com.vasmarfas.networkhelm.sync.VAULT
import com.vasmarfas.networkhelm.sync.createVault
import com.vasmarfas.networkhelm.sync.devicePath
import com.vasmarfas.networkhelm.sync.gatewayToken
import com.vasmarfas.networkhelm.sync.newDevice
import com.vasmarfas.networkhelm.sync.readDevice
import com.vasmarfas.networkhelm.sync.rekey
import com.vasmarfas.networkhelm.sync.rewrap
import com.vasmarfas.networkhelm.sync.signIn
import com.vasmarfas.networkhelm.sync.signOut
import com.vasmarfas.networkhelm.sync.secretKeyText
import com.vasmarfas.networkhelm.sync.seenEpoch
import com.vasmarfas.networkhelm.sync.statePath
import com.vasmarfas.networkhelm.sync.transferCode
import com.vasmarfas.networkhelm.sync.unlock
import com.vasmarfas.networkhelm.sync.vaultGivenBack
import com.vasmarfas.networkhelm.sync.vaultHeader
import eu.anifantakis.lib.ksafe.KSafeProtectionInfo
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

// a service the user chose, reached: how to reach it again, the vault it holds, if any, and whether the vault asks for its secret key
// as well as the password
internal class Reached(val settings: ServiceSettings, val secret: String, val service: SyncService, val vault: Stored?, val secretKey: Boolean)

// what this device keeps of the vault besides the keys: its name, the kinds that stay on it and whether passwords and keys go
internal class Choice(val name: String, val leftOut: Set<String>, val secrets: Boolean)

// the vault and this device's place in it, at the time the user asks: the setup, the service, the vault as read and the keys
private class Opened(val setup: SyncSetup, val reached: Reached, val vault: Stored, val header: Header, val keys: Keys, val secretKey: ByteArray?)

// what the user does with the vault: the service is reached, then a new vault goes onto it, or the device joins the one it holds with the
// password or the code another device shows; later a new password, a device turned off with a new key of the data, a move to another
// service, the end of synchronisation. Nothing here runs before the user asks for it
internal class SyncWizard(
    private val services: Services,
    private val http: () -> SyncHttp,
    private val started: () -> Unit,
    private val passes: Int = PASSES,
    private val memory: Int = MEMORY,
) {
    // where the keys of the vault will lie: the protected store of the platform, or encryption of the app itself where there is none
    val protection: KSafeProtectionInfo? get() = services.secrets.protection

    suspend fun reach(settings: ServiceSettings, secret: String): Reached {
        val service = syncService(settings, secret, http, services.syncFolder) ?: throw folderGone(settings)
        val vault = service.read(VAULT)
        return Reached(settings, secret, service, vault, vault?.let { vaultHeader(it.bytes).password?.secret } == true)
    }

    // the gateway gives a device a token of its own for the login of its page
    suspend fun gatewayLogin(address: String, user: String, password: String, code: String): String = gatewayToken(httpsFor(address, http), address, user, password, code)

    // a cloud gives a device tokens of its own for the sign-in in the browser
    suspend fun cloudLogin(cloud: Cloud, open: (String) -> Unit, page: String): String = signIn(cloud, http(), wayBack(open, page))

    suspend fun create(reached: Reached, password: String, secretKey: ByteArray?, choice: Choice) {
        val device = newDevice()
        val (bytes, keys) = createVault(password, secretKey, device, passes, memory)
        if (!reached.service.writeIf(VAULT, bytes, null)) throw DeviceException(ErrorCategory.General, "another device put a vault on the service meanwhile: join it")
        keepSyncSetup(services.storage, services.secrets, setup(reached, keys.vault, keys.epoch, device, choice), keys, reached.secret, secretKey)
        started()
    }

    suspend fun join(reached: Reached, password: String, secretKey: ByteArray?, choice: Choice) {
        val keys = unlock(vault(reached).bytes, password, secretKey)
        val device = newDevice()
        keepSyncSetup(services.storage, services.secrets, setup(reached, keys.vault, keys.epoch, device, choice), keys, reached.secret, secretKey)
        started()
    }

    suspend fun join(reached: Reached, transfer: Transfer, choice: Choice) {
        val keys = unlock(vault(reached).bytes, Base64.decode(transfer.master))
        val device = newDevice()
        keepSyncSetup(services.storage, services.secrets, setup(reached, keys.vault, keys.epoch, device, choice), keys, reached.secret, transfer.secret?.let { Base64.decode(it) })
        started()
    }

    // the devices of the vault a code opens, by the names they gave themselves, for the user to see whose vault it is before joining
    suspend fun devices(reached: Reached, transfer: Transfer): List<String> {
        val keys = unlock(vault(reached).bytes, Base64.decode(transfer.master))
        return reached.service.list().map { it.path }.filter { it.startsWith("devices/") }.mapNotNull { path ->
            val stored = reached.service.read(path) ?: return@mapNotNull null
            // a file of an earlier key or a damaged one names no device
            try {
                readDevice(keys, path.substringAfter('/'), stored.bytes).content.name
            } catch (e: DeviceException) {
                null
            }
        }.sorted()
    }

    // the code another device takes, shown once the password was typed again here: the master key, the secret key where the vault has
    // one, and the service with its login unless it is a folder
    suspend fun shownCode(password: String): String {
        val opened = open()
        val keys = unlock(opened.vault.bytes, password, opened.secretKey)
        return transferCode(keys.vault, keys.master, opened.secretKey, serviceJson(opened.setup.service, opened.reached.secret))
    }

    // the current password proves the user knows it. The same key of the data under the new password lets the other devices go on; a new
    // key as well, for a password someone else may know, leaves an earlier copy of the vault, as the history of a service keeps, opening
    // nothing written after. Gives the new secret key to show where the vault has one
    suspend fun changePassword(current: String, password: String, newKey: Boolean): String? {
        val opened = open()
        unlock(opened.vault.bytes, current, opened.secretKey)
        if (newKey) return renew(opened, password)
        val (bytes, keys) = rewrap(opened.keys, opened.header, password, opened.secretKey, opened.setup.device, passes, memory)
        keep(opened, bytes, keys, opened.setup, opened.secretKey)
        return null
    }

    // a new password without the current one, which whoever uses this device can set: the key of the data changes with it, so every other
    // device stops and asks for the new password, and a vault the service gave back from before is put right
    suspend fun forgotPassword(password: String): String? = renew(open(earlier = true), password)

    // the device turned off reads nothing written after it, and its files leave every place first: a place that does not answer leaves the
    // key as it was, for the user to try again or to remove that place. The other devices ask once for the new password
    suspend fun disable(device: String, current: String, password: String): String? {
        val opened = open()
        unlock(opened.vault.bytes, current, opened.secretKey)
        for (place in opened.setup.places) {
            val service = service(place)
            for (path in listOf(statePath(device), devicePath(device))) service.delete(path)
        }
        return renew(opened, password)
    }

    // another device changed the key of the data: its new password, and the new secret key where it made one, open the vault here again.
    // That device may have written the vault to one of the places only, so the vault of the newest key among them is taken. A vault with
    // no newer key than this device holds and saw was given back from before
    suspend fun newKey(password: String, secretKey: ByteArray?) {
        val setup = setup()
        val key = secretKey ?: syncSecretKey(services.secrets)
        val keys = unlock(newest(setup).bytes, password, key)
        if (keys.vault != setup.vault) throw DeviceException(ErrorCategory.General, "$VAULT belongs to another vault")
        if (keys.epoch <= setup.epoch || keys.epoch < seen(setup)) throw vaultGivenBack()
        keepSyncSetup(services.storage, services.secrets, setup.copy(epoch = keys.epoch), keys, serviceSecret(services.secrets), key)
        started()
    }

    // a vault with a secret key gets a new one with each new key of the data, which a device that comes back needs along with the password
    suspend fun asksSecretKey(): Boolean = syncSecretKey(services.secrets) != null

    suspend fun rename(name: String) = change { it.copy(name = name) }

    suspend fun choose(leftOut: Set<String>, secrets: Boolean) = change { it.copy(leftOut = leftOut, secrets = secrets) }

    // the vault moves whole, with the files of every device and the same keys; a service that holds it already, moved there by another
    // device, is only taken. The other devices follow when they are moved too or take the code of this one
    suspend fun move(target: Reached) {
        val opened = open()
        if (opened.setup.more.any { it.service == target.settings }) throw DeviceException(ErrorCategory.ArgumentValue, "the vault is kept there already")
        val there = target.vault
        if (there != null) {
            if (vaultHeader(there.bytes).vault != opened.setup.vault) throw DeviceException(ErrorCategory.General, "$VAULT belongs to another vault")
        } else {
            val files = opened.reached.service.list().sortedBy { it.path == VAULT }
            for (listed in files) {
                val stored = opened.reached.service.read(listed.path) ?: continue
                if (listed.path == VAULT) target.service.writeIf(VAULT, stored.bytes, null) else target.service.write(listed.path, stored.bytes)
            }
        }
        keepSyncSetup(services.storage, services.secrets, opened.setup.copy(service = target.settings), opened.keys, target.secret, opened.secretKey)
        started()
    }

    // another place keeps a whole copy of the vault as well, and the rounds of this device carry the records of each place into the
    // others. A service that holds this vault already, as one another device added, is only taken; an empty one gets the vault alone,
    // since the files of the other devices are written where those devices write them
    suspend fun addPlace(target: Reached) {
        val opened = open()
        if (opened.setup.places.any { it.service == target.settings }) throw DeviceException(ErrorCategory.ArgumentValue, "the vault is kept there already")
        val there = target.vault
        if (there == null) {
            if (!target.service.writeIf(VAULT, opened.vault.bytes, null)) throw DeviceException(ErrorCategory.General, "$VAULT was changed on the service while it was written")
        } else if (vaultHeader(there.bytes).vault != opened.setup.vault) {
            throw DeviceException(ErrorCategory.General, "$VAULT belongs to another vault")
        }
        val id = newDevice()
        keepServiceSecret(services.secrets, target.secret, id)
        keepSetup(services.storage, opened.setup.copy(more = opened.setup.more + SyncPlace(id, target.settings)))
        started()
    }

    // a place this device leaves, whether its service answers or not; the vault and the files of the other devices stay there for the
    // devices that keep using it, and the files of this device go, so that its state does not stay behind theirs. The first place left
    // takes the one of the setup, and the last place is not left: turning synchronisation off is for that
    suspend fun removePlace(id: String) {
        val setup = setup()
        val place = setup.places.firstOrNull { it.id == id } ?: return
        val rest = setup.places - place
        val first = rest.firstOrNull() ?: return
        val secret = serviceSecret(services.secrets, id)
        if (id.isEmpty()) {
            keepServiceSecret(services.secrets, serviceSecret(services.secrets, first.id))
            services.storage.write(SYNC_MEMORY, services.storage.read(placeMemory(first.id)).orEmpty())
        }
        keepSetup(services.storage, setup.copy(service = first.service, more = rest.drop(1)))
        val freed = if (id.isEmpty()) first.id else id
        forgetServiceSecret(services.secrets, freed)
        services.storage.write(placeMemory(freed), "")
        started()
        val stayed = try {
            val service = syncService(place.service, secret, http, services.syncFolder) ?: throw folderGone(place.service)
            for (path in listOf(statePath(setup.device), devicePath(setup.device))) service.delete(path)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        (place.service as? ServiceSettings.Account)?.let { signOut(it.cloud, http(), secret) }
        if (stayed != null) throw DeviceException(ErrorCategory.General, "the place was left, but the files of this device stay there: ${stayed.message.orEmpty()}")
    }

    // the data stay on the device and the keys leave it; with the vault removed from every place every device stops, the vault last, so
    // that a device that looks meanwhile finds it gone rather than half of it
    suspend fun forget(removeVault: Boolean) {
        val setup = syncSetup(services.storage)
        val signed = setup?.places.orEmpty().mapNotNull { place -> (place.service as? ServiceSettings.Account)?.let { it.cloud to serviceSecret(services.secrets, place.id) } }
        if (removeVault) {
            for (place in setup().places) {
                val service = service(place)
                for (listed in service.list().sortedBy { it.path == VAULT }) service.delete(listed.path)
            }
        }
        forgetSyncSetup(services.storage, services.secrets)
        started()
        // the sign-in to a cloud ends on the cloud as well where it lets an app end it; synchronisation is off by then, whatever it answers
        for ((cloud, tokens) in signed) signOut(cloud, http(), tokens)
    }

    private suspend fun setup() = syncSetup(services.storage) ?: throw DeviceException(ErrorCategory.MissingItem, "the vault is no longer on the service")

    private suspend fun service(place: SyncPlace) =
        syncService(place.service, serviceSecret(services.secrets, place.id), http, services.syncFolder) ?: throw folderGone(place.service)

    private suspend fun seen(setup: SyncSetup) = setup.places.maxOf { seenEpoch(services.storage, placeMemory(it.id)) }

    // the vault of the newest key among the places that answer; when none answers, the first tells why
    private suspend fun newest(setup: SyncSetup): Stored {
        var failure: Exception? = null
        val vaults = setup.places.mapNotNull { place ->
            try {
                service(place).read(VAULT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
                null
            }
        }
        return vaults.maxByOrNull { vaultHeader(it.bytes).epoch } ?: throw failure ?: DeviceException(ErrorCategory.MissingItem, "the vault is no longer on the service")
    }

    private suspend fun change(edit: (SyncSetup) -> SyncSetup) {
        keepSetup(services.storage, edit(setup()))
        started()
    }

    // a key of the data that changed on another device, in the vault or in any file this device saw, is not wrapped again under a password
    // here: that would put the earlier one back. A vault with an earlier key than this device holds was given back from before, and only a
    // new key puts it right
    private suspend fun open(earlier: Boolean = false): Opened {
        val setup = setup()
        val reached = reach(setup.service, serviceSecret(services.secrets))
        val vault = vault(reached)
        val header = vaultHeader(vault.bytes)
        val keys = syncKeys(setup, services.secrets) ?: throw keysGone()
        if (header.vault != setup.vault) throw DeviceException(ErrorCategory.General, "$VAULT belongs to another vault")
        val newest = maxOf(header.epoch, seen(setup))
        if (newest > keys.epoch) throw KeyChangedException(newest)
        if (header.epoch < keys.epoch && !earlier) throw vaultGivenBack()
        return Opened(setup, reached, vault, header, keys, syncSecretKey(services.secrets))
    }

    // a new key of the data under the new password, and a new secret key where the vault has one: the earlier one stays on a device turned
    // off and in each code shown before. Gives the new secret key to show once
    private suspend fun renew(opened: Opened, password: String): String? {
        val secretKey = if (opened.header.password?.secret == true) Sodium.random(SECRET) else null
        val (bytes, keys) = rekey(opened.keys, opened.header, password, secretKey, opened.setup.device, passes, memory)
        keep(opened, bytes, keys, opened.setup.copy(epoch = keys.epoch), secretKey)
        return secretKey?.let(::secretKeyText)
    }

    // the vault goes back only over the one read: a device that changed it meanwhile is not written over
    private suspend fun keep(opened: Opened, bytes: ByteArray, keys: Keys, setup: SyncSetup, secretKey: ByteArray?) {
        if (!opened.reached.service.writeIf(VAULT, bytes, opened.vault)) throw DeviceException(ErrorCategory.General, "$VAULT was changed on the service while it was written")
        keepSyncSetup(services.storage, services.secrets, setup, keys, opened.reached.secret, secretKey)
        started()
    }

    private fun vault(reached: Reached) = reached.vault ?: throw DeviceException(ErrorCategory.MissingItem, "the vault is no longer on the service")

    private fun setup(reached: Reached, vault: String, epoch: Int, device: String, choice: Choice) =
        SyncSetup(vault, epoch, device, choice.name, reached.settings, choice.leftOut, choice.secrets)
}
