package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// a show is kept this long: the windows opened together and the description of a window with its first read cost the router
// one answer, and no window polls more often than this
private val FRESH = 1500.milliseconds
// the windows read together on a tick of the session, and what they ask within this moment is one request, as the web
// interface sends the reads of a page
private val GATHER = 40.milliseconds
private val SAVE_AFTER = 2.seconds
internal val SAVE = JsonObject(mapOf("system" to JsonObject(mapOf("configuration" to JsonObject(mapOf("save" to JsonObject(emptyMap())))))))

// what the router answered, kept: a show for a moment, asked once for all who ask at the same time; help for good, as only a
// new firmware or component changes it, in the store of the app under the build of the router. A command that changes
// something forgets every show
internal class KeeneticCache(private val api: KeeneticCall) : KeeneticCall by api {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val shows = HashMap<String, Shown>()
    private var gathering: Gathering? = null
    private val helps = HashMap<String, JsonElement>()
    private var store: TextStore? = null
    private var book: String? = null
    private var saving: Job? = null

    override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> = when {
        commands.isEmpty() -> api.post(commands, raising)
        commands.all { it.keys == setOf("help") } -> helped(commands)
        commands.all { it.keys == setOf("show") } -> shown(commands, raising)
        // a change is saved in the same request, as the web interface saves it: what only runs is lost with a reboot
        else -> try {
            api.post(commands + SAVE, raising).dropLast(1)
        } finally {
            lock.withLock { shows.clear() }
        }
    }

    // the help of this build, read from the store and written back to it
    suspend fun keep(store: TextStore, build: String) {
        val name = "keenetic-help-${build.filter { it.isLetterOrDigit() || it in "._-" }}.json"
        val kept = if (lock.withLock { book == name }) null else store.read(name)
        val answers = kept?.let {
            try {
                Json.parseToJsonElement(it) as? JsonObject
            } catch (e: SerializationException) {
                // a file that does not read is made again from the answers of this session
                null
            }
        }
        lock.withLock {
            this.store = store
            book = name
            answers?.forEach { (line, answer) -> helps.getOrPut(line) { answer } }
        }
    }

    fun close() = scope.cancel()

    // what is live goes in the request of the moment too, and is not kept
    private suspend fun shown(commands: List<JsonObject>, raising: Boolean): List<JsonElement> {
        val keys = commands.map(JsonObject::toString)
        val lives = commands.filter(::live).map(JsonObject::toString).toSet()
        val waits = lock.withLock {
            shows.entries.removeAll { it.value.stale() }
            val asked = HashMap<String, Deferred<JsonElement>>()
            for (key in keys.distinct().filter { it in lives || it !in shows }) {
                val batch = gathering ?: Gathering().also { started ->
                    gathering = started
                    started.answers = scope.async {
                        delay(GATHER)
                        val sent = lock.withLock {
                            gathering = null
                            started.commands.toList()
                        }
                        api.post(sent.map { Json.parseToJsonElement(it) as JsonObject }, raising = false)
                    }
                }
                val index = batch.commands.size
                batch.commands += key
                val shown = Shown()
                shown.answer = scope.async { (batch.answers.await().getOrNull(index) ?: JsonObject(emptyMap())).also { shown.done = TimeSource.Monotonic.markNow() } }
                if (key in lives) asked[key] = shown.answer else shows[key] = shown
            }
            keys.map { asked[it] ?: shows.getValue(it).answer }
        }
        val answers = try {
            waits.awaitAll()
        } catch (e: DeviceException) {
            lock.withLock { keys.forEach { key -> if (shows[key]?.answer?.isCompleted == true) shows.remove(key) } }
            throw e
        }
        if (raising) failure(JsonArray(answers))?.let { throw DeviceException(ErrorCategory.Api, it) }
        return answers
    }

    private suspend fun helped(commands: List<JsonObject>): List<JsonElement> {
        val keys = commands.map(JsonObject::toString)
        val missing = lock.withLock { keys.distinct().filter { it !in helps } }
        val fresh = if (missing.isEmpty()) emptyMap() else missing.zip(api.post(missing.map { Json.parseToJsonElement(it) as JsonObject }, raising = false)).toMap()
        val learned = fresh.filterValues { failure(it) == null }
        if (learned.isNotEmpty()) {
            lock.withLock { helps.putAll(learned) }
            saveLater()
        }
        return lock.withLock { keys.map { fresh[it] ?: helps[it] ?: JsonObject(emptyMap()) } }
    }

    private suspend fun saveLater() {
        lock.withLock {
            if (store == null || saving?.isActive == true) return
            saving = scope.launch {
                delay(SAVE_AFTER)
                val (to, name, text) = lock.withLock { Triple(store, book, JsonObject(helps.toMap()).toString()) }
                if (to != null && name != null) to.keep(name, text)
            }
        }
    }

    // the shows asked for in one moment, which go to the router in one request
    private class Gathering {
        val commands = ArrayList<String>()
        lateinit var answers: Deferred<List<JsonElement>>
    }

    // a show waited for stays until it is answered, however long the router takes, and is kept from then on
    private class Shown {
        lateinit var answer: Deferred<JsonElement>

        @Volatile
        var done: TimeMark? = null

        fun stale() = done?.let { it.elapsedNow() >= FRESH } == true
    }

    // the rates of interfaces and the log are read for what is new in them
    private fun live(command: JsonObject): Boolean {
        val show = command["show"] as? JsonObject ?: return false
        return "log" in show || (show["interface"] as? JsonObject)?.containsKey("stat") == true
    }
}
