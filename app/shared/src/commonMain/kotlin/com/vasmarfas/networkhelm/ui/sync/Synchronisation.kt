package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.snapshotFlow
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.sync.DeviceContent
import com.vasmarfas.networkhelm.sync.DropboxService
import com.vasmarfas.networkhelm.sync.GatewayService
import com.vasmarfas.networkhelm.sync.MEMORY
import com.vasmarfas.networkhelm.sync.PASSES
import com.vasmarfas.networkhelm.sync.Place
import com.vasmarfas.networkhelm.sync.SyncHttp
import com.vasmarfas.networkhelm.sync.SyncLoop
import com.vasmarfas.networkhelm.sync.SyncPlaces
import com.vasmarfas.networkhelm.sync.Synchronizer
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import com.vasmarfas.networkhelm.ui.main.DockerTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

// synchronisation of this copy of the app: until the user sets it up nothing of it runs and nothing that could reach a network is made.
// Set up, it runs rounds over the stores of the app, in the one copy that may where two share a data folder
internal class Synchronisation(
    private val services: Services,
    private val routers: SavedRouters,
    private val snippets: Snippets,
    private val settings: AppSettings,
    private val templates: DockerTemplates,
    private val files: RouterFiles,
    private val ask: suspend (TrustRequest) -> Boolean,
    private val http: (TrustPolicy) -> SyncHttp = ::SyncHttp,
) {
    private val starts = MutableStateFlow(0)
    private val loop = MutableStateFlow<SyncLoop?>(null)
    private val failure = MutableStateFlow<Exception?>(null)
    private val current = MutableStateFlow<SyncSetup?>(null)
    private val holding = MutableStateFlow(true)
    private var vault: SyncPlaces? = null
    private val trusted = TrustRecords(services.trust, services.storage)
    private var inFront = true

    // the setup the rounds started from, null while synchronisation is off on this device
    val setup: StateFlow<SyncSetup?> = current.asStateFlow()

    // false in the copy of the app that left the data folder to another copy, which synchronises it
    val held: StateFlow<Boolean> = holding.asStateFlow()

    // the rounds while they run, for the window and the icon of synchronisation
    val running: StateFlow<SyncLoop?> = loop.asStateFlow()

    // what kept synchronisation from starting: a damaged setup, a protected store that did not open
    val trouble: StateFlow<Exception?> = failure.asStateFlow()

    // fingerprints other devices hold where this one trusts others, for the window of synchronisation to ask which stays
    val disputes: StateFlow<List<Dispute>> = trusted.disputes

    // for as long as the app runs; a new setup starts it again
    suspend fun run() {
        starts.collectLatest {
            failure.value = null
            try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure.value = e
            } finally {
                loop.value = null
                vault = null
            }
        }
    }

    fun restart() {
        starts.value += 1
    }

    fun now() {
        loop.value?.now()
    }

    // the user sets synchronisation up; the passes and memory of Argon2id are the vault's own, lower only in tests
    fun wizard(passes: Int = PASSES, memory: Int = MEMORY) = SyncWizard(services, { http(TrustPolicy(services.trust, ask)) }, ::restart, passes, memory)

    // the other devices of the vault as each named itself last
    suspend fun devices(): Map<String, DeviceContent> = vault?.devices().orEmpty()

    // a minute between rounds while the app is in front, a quarter of an hour behind it, and a round at once as it comes back
    fun front(front: Boolean) {
        val back = front && !inFront
        inFront = front
        val rounds = loop.value ?: return
        rounds.period = period()
        if (back) rounds.now()
    }

    // the fingerprint of the other device goes into the trust store, or that of this device goes to the others over it
    suspend fun settle(dispute: Dispute, take: Boolean) {
        trusted.settle(dispute, take)
        loop.value?.now()
    }

    private fun period() = if (inFront) 1.minutes else 15.minutes

    private suspend fun start() = coroutineScope {
        val lock = services.syncLock ?: return@coroutineScope
        val setup = syncSetup(services.storage)
        current.value = setup
        if (setup == null) return@coroutineScope
        val keys = syncKeys(setup, services.secrets) ?: throw keysGone()
        holding.value = lock()
        if (!holding.value) return@coroutineScope
        // a store that keeps no fingerprints of its own, as in a browser, passes those of the others on untouched
        val trust = trusted.takeIf { services.trust.all() != null }
        val stores = SyncStores(routers, snippets, settings, templates, trust, files, services.secrets, services.storage, setup.leftOut, setup.secrets)
        val about = DeviceContent(setup.name, syncPlatform, appVersion, 0)
        val missing = LinkedHashMap<String, Exception>()
        val places = setup.places.mapNotNull { place ->
            // the tokens a cloud renews are kept while the setup still holds that place: a move or removal may have come meanwhile
            val service = syncService(place.service, serviceSecret(services.secrets, place.id), { http(TrustPolicy(services.trust, ask)) }, services.syncFolder) { tokens ->
                if (syncSetup(services.storage)?.places?.contains(place) == true) keepServiceSecret(services.secrets, tokens, place.id)
            }
            if (service == null) missing[place.id] = folderGone(place.service)
            val besides = setup.places.filter { it != place }.map { placeMemory(it.id) }
            service?.let { Place(place.id, it, Synchronizer(it, keys, setup.device, about, stores, services.storage, memoryFile = placeMemory(place.id), besides = besides)) }
        }
        if (places.isEmpty()) throw missing.values.first()
        val rounds = SyncPlaces(keys, places, missing).let {
            vault = it
            SyncLoop(it::round)
        }
        rounds.period = period()
        loop.value = rounds
        // an edit of a store, a password, key or fingerprint, a workspace or a tunnel, whether the user made it or a round put in what came:
        // the round after it finds what is new or nothing
        val edits = merge(routers.routers, snippets.all, templates.all, snapshotFlow { settings.current to settings.themes.toList() }, services.edits, files.edits)
        launch { edits.collect { rounds.edited() } }
        val gateway = places.firstNotNullOfOrNull { it.service as? GatewayService }
        var known = -1L
        val dropbox = places.firstNotNullOfOrNull { it.service as? DropboxService }
        rounds.run(gateway?.let { suspend { known = it.changes(known) } } ?: dropbox?.let { suspend { it.changes() } })
    }
}
