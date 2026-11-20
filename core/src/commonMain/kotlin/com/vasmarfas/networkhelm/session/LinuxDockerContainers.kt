package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

// the containers of the engine: the list with what docker ps and docker stats show, every setting of one for its form, and the
// changes, where what docker update takes goes there, a new name alone is a rename and anything else takes the container made anew
internal class DockerContainers(private val api: DockerApi) {
    private val files = DockerFiles(api)

    // the CPU time of each running container at the last read of the list, for its share until the next one
    private val readings = HashMap<String, Pair<Long, Instant>>()
    private val reading = Mutex()

    fun table(present: suspend () -> Boolean) = HostTable(
        CONTAINER_COLUMNS, ::list, arguments = ::arguments, add = ::create, set = ::change, remove = { remove(it, volumes = false) },
        commands = COMMANDS, run = ::command, streams = mapOf("log" to ::log, "stats" to ::stats), item = ::item, present = present,
    )

    private suspend fun list(): List<Item> {
        val containers = objects(api.get("/containers/json?all=1"), "/containers/json")
        val running = containers.filter { it.text("State") == "running" }.map { it.text("Id") }
        val samples = coroutineScope { running.map { id -> async { id to sample(id) } }.awaitAll() }
        val stats = reading.withLock {
            readings.keys.retainAll(running.toSet())
            samples.associate { (id, sample) ->
                val values = sample?.let { statsOf(it, readings[id]) }.orEmpty()
                sample?.let(::cpuReading)?.let { readings[id] = it }
                id to values
            }
        }
        val zone = api.offset()
        return containers.map { containerRow(it, zone, stats[it.text("Id")].orEmpty()) }
    }

    // a container that stops between the list and its stats has none, and its row goes without them
    private suspend fun sample(id: String): JsonObject? = try {
        api.get("/containers/$id/stats?stream=false&one-shot=true") as? JsonObject
    } catch (e: DeviceException) {
        if (e.category == ErrorCategory.ConnectionLost) throw e
        null
    }

    private suspend fun item(id: String): Item? {
        val inspected = try {
            api.get("/containers/${urlEncoded(id)}/json") as? JsonObject
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.MissingItem) return null
            throw e
        }
        return inspected?.let { containerItem(it, api.offset()) }
    }

    private suspend fun inspect(id: String): JsonObject =
        api.get("/containers/${urlEncoded(id)}/json") as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "/containers/$id/json gave no JSON")

    // the image and the network are chosen from those the engine has, or typed
    private suspend fun arguments(): List<Argument> {
        val images = objects(api.get("/images/json"), "/images/json").flatMap { it.texts("RepoTags") }.filter { it != "<none>:<none>" }.sorted()
        val networks = objects(api.get("/networks"), "/networks").map { it.text("Name") }
        return CONTAINER_ARGUMENTS.map { argument ->
            when (argument.name) {
                "image" -> argument.copy(choices = images)
                "network" -> argument.copy(choices = (listOf("bridge", "host", "none") + networks.sorted()).distinct())
                else -> argument
            }
        }
    }

    suspend fun create(values: Map<String, String>): String {
        if (values["image"].isNullOrEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the image of the container is required")
        val spec = Spec(emptyMap(), emptyMap(), "", emptyMap(), mutableMapOf())
        for (field in FIELDS) values[field.argument.name]?.let { field.write(spec, it) }
        val id = made(values["name"].orEmpty(), spec, values["registries"])
        if (values["start"] !in setOf("false", "no")) api.send("POST", "/containers/$id/start")
        return id
    }

    // an image the engine lacks is pulled first, as docker run does, with the login the app keeps for its registry
    private suspend fun made(name: String, spec: Spec, logins: String? = null): String {
        val path = "/containers/create" + if (name.isEmpty()) "" else "?name=${urlEncoded(name)}"
        val answer = try {
            api.created(path, spec.json())
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.MissingItem || !e.message.orEmpty().contains("image", ignoreCase = true)) throw e
            pull(spec.image, logins)
            api.created(path, spec.json())
        }
        return answer.text("Id")
    }

    private suspend fun pull(image: String, logins: String?) =
        api.lines("POST", "/images/create?" + pulled(image), headers = savedLogin(logins, image)).collect { dockerProgress(it) }

    private suspend fun change(id: String?, changes: Map<String, String?>) {
        val container = id ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the id of a container")
        val touched = changes.keys - PSEUDO
        val name = changes["name"]?.ifEmpty { null }
        when {
            touched.all { it == "name" } -> name?.let { api.send("POST", "/containers/${urlEncoded(container)}/rename?name=${urlEncoded(it)}") }
            touched.all { it == "name" || it in UPDATABLE } -> {
                val spec = Spec.of(inspect(container), keepAnonymous = false)
                for (field in FIELDS) if (field.argument.name in changes) field.write(spec, changes[field.argument.name].orEmpty())
                val keys = touched.mapNotNull { UPDATABLE[it] }.toSet()
                api.send("POST", "/containers/${urlEncoded(container)}/update", JsonObject(spec.host.filterKeys { it in keys }))
                name?.let { api.send("POST", "/containers/${urlEncoded(container)}/rename?name=${urlEncoded(it)}") }
            }
            changes["recreate"] !in ValueType.Flag.on -> throw DeviceException(ErrorCategory.ArgumentValue, NEEDS_RECREATE)
            else -> recreate(container, changes)
        }
    }

    // the old container steps aside under another name until the new one runs, and comes back if it does not
    private suspend fun recreate(id: String, changes: Map<String, String?>, pull: Boolean = false): String {
        val inspected = inspect(id)
        val spec = Spec.of(inspected, keepAnonymous = true)
        for (field in FIELDS) if (field.argument.name in changes) field.write(spec, changes[field.argument.name].orEmpty())
        if (pull) pull(spec.image, changes["registries"])
        val old = inspected.text("Name").removePrefix("/")
        val name = changes["name"]?.ifEmpty { null } ?: old
        val running = inspected.inner("State")?.flag("Running") == true
        val current = "/containers/${urlEncoded(inspected.text("Id"))}"
        api.send("POST", "$current/rename?name=${urlEncoded("$old-replaced")}")
        val replacement = try {
            made(name, spec, changes["registries"])
        } catch (e: DeviceException) {
            undo(e) { api.send("POST", "$current/rename?name=${urlEncoded(old)}") }
            throw e
        }
        try {
            for ((network, endpoint) in spec.extra) {
                api.send("POST", "/networks/${urlEncoded(network)}/connect", buildJsonObject {
                    put("Container", replacement)
                    put("EndpointConfig", endpoint)
                })
            }
            if (running) {
                api.send("POST", "$current/stop")
                api.send("POST", "/containers/$replacement/start")
            }
        } catch (e: DeviceException) {
            undo(e) { api.send("DELETE", "/containers/$replacement?force=1") }
            undo(e) { api.send("POST", "$current/rename?name=${urlEncoded(old)}") }
            if (running) undo(e) { api.send("POST", "$current/start") }
            throw e
        }
        api.send("DELETE", "$current?force=1")
        return replacement
    }

    // the copy starts stopped: the ports of the host it takes are still the original's
    private suspend fun duplicate(id: String): String {
        val inspected = inspect(id)
        return made(inspected.text("Name").removePrefix("/") + "-copy", Spec.of(inspected, keepAnonymous = false))
    }

    private suspend fun commit(id: String): String {
        val repository = inspect(id).text("Name").removePrefix("/").lowercase()
        val tag = "snapshot-${Clock.System.now().epochSeconds}"
        api.created("/commit?container=${urlEncoded(id)}&repo=${urlEncoded(repository)}&tag=$tag&pause=true")
        return "$repository:$tag"
    }

    private suspend fun remove(ids: List<String>, volumes: Boolean) {
        for (id in ids) api.send("DELETE", "/containers/${urlEncoded(id)}?force=1" + if (volumes) "&v=1" else "")
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val ids = arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }
        val id by lazy { arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the id of a container") }
        return when (command) {
            "start", "stop", "restart", "pause", "unpause" -> {
                for (container in ids) api.send("POST", "/containers/${urlEncoded(container)}/$command")
                emptyList()
            }
            in SIGNALS -> {
                for (container in ids) api.send("POST", "/containers/${urlEncoded(container)}/kill?signal=${SIGNALS[command]}")
                emptyList()
            }
            "remove-with-volumes" -> {
                remove(ids, volumes = true)
                emptyList()
            }
            "recreate" -> {
                for (container in ids) recreate(container, mapOf("registries" to arguments["registries"]), pull = true)
                emptyList()
            }
            "duplicate" -> ids.map { Item(duplicate(it), emptyMap()) }
            "commit" -> ids.map { Item(it, mapOf("image" to commit(it))) }
            "top" -> processes(objectOf(api.get("/containers/${urlEncoded(id)}/top")))
            "changes" -> differences(api.get("/containers/${urlEncoded(id)}/changes"))
            "inspect" -> listOf(Item(id, mapOf("json" to laidOut(masked(inspect(id))))))
            // what docker container prune takes: the containers that do not run
            "unused" -> {
                val offset = api.offset()
                objects(api.get("/containers/json?all=1"), "/containers/json").filter { it.text("State") !in ALIVE }.map { containerRow(it, offset, emptyMap()) }
            }
            "terminal" -> listOf(Item(id, mapOf("command" to terminal(api.cli(), id, arguments["shell"].orEmpty(), arguments["user"].orEmpty()))))
            "ls" -> files.list(id, arguments["path"].orEmpty())
            "download" -> listOf(files.download(id, arguments["path"].orEmpty()))
            "upload" -> {
                files.upload(id, arguments["path"].orEmpty(), arguments["name"].orEmpty(), arguments["content"].orEmpty())
                emptyList()
            }
            else -> throw DeviceException(ErrorCategory.MissingItem, "container/$command is not on this host")
        }
    }

    private fun log(arguments: Map<String, String>): Flow<Item> = flow {
        val id = arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the id of a container")
        val stamped = arguments["timestamps"] !in setOf("false", "no")
        val query = listOfNotNull(
            "stdout=1", "stderr=1",
            "follow=" + if (arguments["follow"] in setOf("false", "no")) "0" else "1",
            "timestamps=" + if (stamped) "1" else "0",
            "tail=" + urlEncoded(arguments["tail"]?.ifEmpty { null } ?: "500"),
            arguments["since"]?.ifEmpty { null }?.let { "since=${urlEncoded(it)}" },
            arguments["until"]?.ifEmpty { null }?.let { "until=${urlEncoded(it)}" },
        )
        val offset = api.offset()
        api.log("/containers/${urlEncoded(id)}/logs?" + query.joinToString("&")).collect { (stream, line) ->
            val values = if (stamped) {
                mapOf("time" to logTime(line.substringBefore(' '), offset), "stream" to stream, "message" to line.substringAfter(' ', ""))
            } else {
                mapOf("stream" to stream, "message" to line)
            }
            emit(Item("", values))
        }
    }

    private fun stats(arguments: Map<String, String>): Flow<Item> = flow {
        val id = arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the id of a container")
        api.lines("GET", "/containers/${urlEncoded(id)}/stats?stream=1").collect { emit(Item("", dockerStats(it))) }
    }
}

// what a create request takes: Config at the top, HostConfig beside it, the endpoint on the network the container starts on and the
// networks it joins after; the variables are kept in two parts, by whether their names say they are secret
private class Spec(config: Map<String, JsonElement>, host: Map<String, JsonElement>, var network: String, endpoint: Map<String, JsonElement>, val extra: MutableMap<String, JsonObject>) {
    val config = config.toMutableMap()
    val host = host.toMutableMap()
    val endpoint = endpoint.toMutableMap()
    var visible = config.texts("Env").filterNot { secret(it) }
    var hidden = config.texts("Env").filter { secret(it) }

    val image get() = config.text("Image")

    fun json(): JsonObject = JsonObject(buildMap {
        putAll(config)
        val env = visible + hidden
        if (env.isNotEmpty() || "Env" in config) put("Env", JsonArray(env.map(::JsonPrimitive)))
        put("HostConfig", JsonObject(host))
        if (joins(network)) put("NetworkingConfig", JsonObject(mapOf("EndpointsConfig" to JsonObject(mapOf(network to JsonObject(endpoint))))))
    })

    companion object {
        // the settings of a container as a new one takes them, without what the engine gave the old one by itself; an anonymous
        // volume of the old one is named for the new one, so that what it holds is not left behind
        fun of(inspected: JsonObject, keepAnonymous: Boolean): Spec {
            val id = inspected.text("Id")
            val config = inspected.config().orEmpty().filterKeys { it != "Hostname" || inspected.config()?.text("Hostname") != id.take(12) }
            val primary = endpointKey(inspected)
            val host = inspected.hostConfig().orEmpty().let { if (primary != networkOf(inspected)) it + ("NetworkMode" to JsonPrimitive(primary)) else it }
            val endpoints = inspected.inner("NetworkSettings")?.inner("Networks").orEmpty().mapValues { (_, value) -> JsonObject(endpointOf(value as? JsonObject, id)) }
            val extra = if (joins(primary)) endpoints.filterKeys { it != primary }.toMutableMap() else mutableMapOf()
            val spec = Spec(config, host, primary, endpoints[primary].orEmpty(), extra)
            if (keepAnonymous) {
                val named = spec.host.texts("Binds").map { it.substringBefore(':') } + spec.host.objects("Mounts").map { it.text("Source") }
                val anonymous = inspected.objects("Mounts").filter { it.text("Type") == "volume" && it.text("Name") !in named }.map { mount ->
                    buildJsonObject {
                        put("Type", "volume")
                        put("Source", mount.text("Name"))
                        put("Target", mount.text("Destination"))
                        if (!mount.flag("RW")) put("ReadOnly", true)
                    }
                }
                if (anonymous.isNotEmpty()) spec.host["Mounts"] = JsonArray(spec.host.objects("Mounts") + anonymous)
            }
            return spec
        }
    }
}

private class Field(val argument: Argument, val read: (JsonObject) -> String, val write: (Spec, String) -> Unit)

private fun joins(network: String) = network.isNotEmpty() && network != "host" && network != "none" && !network.startsWith("container:")

private fun networkOf(inspected: JsonObject) = inspected.hostConfig()?.text("NetworkMode").orEmpty().let { if (it == "default") "bridge" else it }

// Podman says bridge in NetworkMode for any network of its own and lists the endpoint under the name of the network, podman for
// its default one; a create request names the network so, and Podman finds no network bridge
private fun endpointKey(inspected: JsonObject): String {
    val network = networkOf(inspected)
    val networks = inspected.inner("NetworkSettings")?.inner("Networks").orEmpty()
    return if (network == "bridge" && network !in networks && networks.isNotEmpty()) networks.keys.first() else network
}

private fun endpointIn(inspected: JsonObject) = inspected.inner("NetworkSettings")?.inner("Networks")?.inner(endpointKey(inspected))

// what the engine keeps of an endpoint the container asked for; its address, MAC and gateway of the moment are not asked for.
// Podman lists the short ID among the aliases by itself
private fun endpointOf(network: JsonObject?, id: String): Map<String, JsonElement> {
    val kept = network.orEmpty().filter { (key, value) -> key in ENDPOINT_KEPT && value !is JsonNull }
    val aliases = network?.texts("Aliases").orEmpty().filter { it != id.take(12) }
    return if (aliases.isEmpty()) kept else kept + ("Aliases" to JsonArray(aliases.map(::JsonPrimitive)))
}

// the row of docker ps with the load of a running container from docker stats
internal fun containerRow(container: JsonObject, offset: String, stats: Map<String, String>): Item {
    val labels = container.inner("Labels")
    val networks = container.inner("NetworkSettings")?.inner("Networks").orEmpty()
    val status = container.text("Status")
    val state = container.text("State")
    val health = health(container.inner("Health")?.text("Status"), status)
    return Item(container.text("Id"), linkedMapOf(
        "name" to container.texts("Names").firstOrNull().orEmpty().removePrefix("/"),
        "image" to container.text("Image"),
        "state" to state,
        "health" to health,
        "status" to status,
        "ports" to published(container.objects("Ports")),
        "created" to moment(container.text("Created"), offset),
        "project" to labels?.text(COMPOSE_PROJECT).orEmpty(),
        "service" to labels?.text(COMPOSE_SERVICE).orEmpty(),
        "networks" to networks.keys.joinToString(","),
        "ip-address" to networks.values.firstNotNullOfOrNull { (it as? JsonObject)?.text("IPAddress")?.ifEmpty { null } }.orEmpty(),
        "command" to container.text("Command"),
    ) + flagsOf(state, status, health) + stats)
}

// what the letters at the head of a row show: running, paused, restarting, stopped, stopped with an error code or dead, and the
// health; docker ps says the code only in the status, Exited (137) 2 hours ago
private fun flagsOf(state: String, status: String, health: String): Map<String, String> {
    val code = EXIT_CODE.find(status)?.groupValues?.get(1)?.toIntOrNull()
    val failed = state == "dead" || state == "exited" && code != null && code != 0
    return linkedMapOf(
        "running" to "${state == "running"}",
        "paused" to "${state == "paused"}",
        "restarting" to "${state == "restarting"}",
        "stopped" to "${(state == "exited" || state == "created") && !failed}",
        "failed" to "$failed",
        "healthy" to "${health == "healthy"}",
        "unhealthy" to "${health == "unhealthy"}",
    )
}

private val EXIT_CODE = Regex("""^Exited \((\d+)\)""")

// every setting of a container as its form shows it, and its state
internal fun containerItem(inspected: JsonObject, offset: String): Item {
    val state = inspected.inner("State")
    return Item(
        inspected.text("Id"),
        linkedMapOf("name" to inspected.text("Name").removePrefix("/")) + FIELDS.associate { it.argument.name to it.read(inspected) } + linkedMapOf(
            "state" to state?.text("Status").orEmpty(),
            "health" to state?.inner("Health")?.text("Status").orEmpty(),
            "started" to moment(state?.text("StartedAt").orEmpty(), offset),
            "created" to moment(inspected.text("Created"), offset),
            "ip-address" to endpointIn(inspected)?.text("IPAddress").orEmpty(),
            "restart-count" to inspected.text("RestartCount"),
            "exit-code" to state?.text("ExitCode").orEmpty(),
            "id" to inspected.text("Id"),
        ),
    )
}

// older engines say the health only in the status: Up 2 minutes (healthy)
private fun health(status: String?, text: String) =
    status?.takeIf { it.isNotEmpty() && it != "none" } ?: Regex("""\((healthy|unhealthy|health: starting)\)""").find(text)?.groupValues?.get(1)?.removePrefix("health: ").orEmpty()

private fun published(ports: List<JsonObject>) = ports.map { port ->
    val inside = "${port.text("PrivatePort")}/${port.text("Type")}"
    val address = port.text("IP").let { if (':' in it) "[$it]" else it }
    when {
        port.text("PublicPort").isEmpty() -> inside
        address.isEmpty() -> "${port.text("PublicPort")}->$inside"
        else -> "$address:${port.text("PublicPort")}->$inside"
    }
}.distinct().joinToString(", ")

// a time of the engine in the time of the host, in seconds since 1970 or in RFC 3339 as each answer writes it; none, 0 and the zero
// time of Go stay empty: an image built without a date of its own has one of them
internal fun moment(text: String, offset: String) =
    (text.toLongOrNull() ?: Instant.parseOrNull(text)?.epochSeconds)?.takeIf { it > 0 }?.let { local(it, offset) }.orEmpty()

// the time of a line of a log, which the engine writes in RFC 3339, in the time of the host to the millisecond
internal fun logTime(text: String, offset: String): String {
    val instant = Instant.parseOrNull(text) ?: return text
    return local(instant.epochSeconds, offset) + "." + (instant.nanosecondsOfSecond / 1_000_000).toString().padStart(3, '0')
}

// the processes of docker top under the titles ps gave them, in lower case: pid, user, %cpu as cpu
internal fun processes(top: JsonObject): List<Item> {
    val titles = top.texts("Titles").map { it.lowercase().trim().trim('%').replace(' ', '-') }
    return (top["Processes"] as? JsonArray).orEmpty().mapNotNull { row -> (row as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } }
        .map { cells -> Item(cells.getOrElse(titles.indexOf("pid")) { "" }, titles.zip(cells).toMap()) }
}

// what the container wrote over its image: a path and whether it was changed, added or deleted; an engine with nothing to tell sends null
internal fun differences(changes: JsonElement): List<Item> = (changes as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { change ->
    Item(change.text("Path"), mapOf("path" to change.text("Path"), "kind" to KINDS.getOrElse(change.long("Kind")?.toInt() ?: -1) { change.text("Kind") }))
}

// the shell of the login types this, so it is quoted for it; attach takes the container's own process instead of a new one
internal fun terminal(cli: String, id: String, shell: String, user: String): String = when (shell) {
    "attach" -> "exec $cli attach ${quote(id)}"
    else -> listOfNotNull("exec", cli, "exec", "-it", user.ifEmpty { null }?.let { "-u ${quote(it)}" }, quote(id), shell.ifEmpty { "sh" }).joinToString(" ")
}

private fun masked(inspected: JsonObject): JsonObject {
    val config = inspected.config() ?: return inspected
    if ("Env" !in config) return inspected
    val env = config.texts("Env").map { if (secret(it)) it.substringBefore('=') + "=********" else it }
    return JsonObject(inspected + ("Config" to JsonObject(config + ("Env" to JsonArray(env.map(::JsonPrimitive))))))
}

// a reference without a tag would pull every tag of the repository, so latest is said; one by digest is pulled as it is
internal fun pulled(image: String): String {
    val repository = image.substringBefore('@')
    val tag = repository.substringAfterLast('/').substringAfter(':', "")
    return when {
        '@' in image -> "fromImage=${urlEncoded(image)}"
        tag.isEmpty() -> "fromImage=${urlEncoded(image)}&tag=latest"
        else -> "fromImage=${urlEncoded(image.dropLast(tag.length + 1))}&tag=${urlEncoded(tag)}"
    }
}

// a variable whose name says it holds a password, a token or a key: its value is not read back into the form
internal fun secret(variable: String) = SECRET_NAME.containsMatchIn(variable.substringBefore('='))

// the secret variables as typed: an empty value keeps the one the variable had
internal fun secrets(old: List<String>, value: String): List<String> = lines(value).map { line ->
    val name = line.substringBefore('=')
    if (line.substringAfter('=', "").isNotEmpty()) line else old.firstOrNull { it.substringBefore('=') == name } ?: "$name="
}

// [address:]host-port:container-port[/protocol] as docker run -p takes it; without the port of the host the engine picks one
internal fun bindings(value: String): Map<String, List<Pair<String, String>>> {
    val bound = linkedMapOf<String, MutableList<Pair<String, String>>>()
    for (line in lines(value)) {
        val match = PORT.matchEntire(line) ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a port mapping: $line")
        val (v6, v4, published, inside, protocol) = match.destructured
        bound.getOrPut("$inside/${protocol.ifEmpty { "tcp" }}") { mutableListOf() } += v6.ifEmpty { v4 } to published
    }
    return bound
}

internal fun portsOf(bindings: JsonObject?): String = bindings.orEmpty().flatMap { (port, list) ->
    (list as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { binding ->
        val address = binding.text("HostIp").let { if (':' in it) "[$it]" else it }
        val published = binding.text("HostPort")
        (if (address.isEmpty()) "" else "$address:") + (if (published.isEmpty() && address.isEmpty()) "" else "$published:") + port
    }
}.joinToString("\n")

// type=volume,source=data,target=/data,readonly as docker run --mount takes it, or source:target[:ro] as -v does
internal fun mountOf(line: String): JsonObject {
    val parts = if ('=' in line) {
        line.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=', "true").trim() }
    } else {
        val words = line.split(':')
        if (words.size == 1) mapOf("target" to words[0]) else mapOf("source" to words[0], "target" to words[1], "readonly" to "${words.getOrNull(2)?.split(',')?.contains("ro") == true}")
    }
    val source = parts["source"] ?: parts["src"]
    val target = parts["target"] ?: parts["destination"] ?: parts["dst"] ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a mount: $line")
    val type = parts["type"] ?: if (source?.startsWith("/") == true) "bind" else "volume"
    if (type !in setOf("volume", "bind", "tmpfs")) throw DeviceException(ErrorCategory.ArgumentValue, "not a type of mount: $type")
    return buildJsonObject {
        put("Type", type)
        source?.let { put("Source", it) }
        put("Target", target)
        if ((parts["readonly"] ?: parts["ro"]) in ValueType.Flag.on) put("ReadOnly", true)
        parts["bind-propagation"]?.let { putJsonObject("BindOptions") { put("Propagation", it) } }
        if (type == "tmpfs" && ("tmpfs-size" in parts || "tmpfs-mode" in parts)) {
            putJsonObject("TmpfsOptions") {
                parts["tmpfs-size"]?.let { put("SizeBytes", sizeBytes(it)) }
                parts["tmpfs-mode"]?.let { put("Mode", it.toLongOrNull(8) ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a mount: $line")) }
            }
        }
    }
}

internal fun mountsOf(inspected: JsonObject): String {
    val mounts = inspected.objects("Mounts").map { mount ->
        val type = mount.text("Type")
        listOfNotNull(
            "type=$type",
            (if (type == "volume") mount.text("Name") else mount.text("Source")).ifEmpty { null }?.let { "source=$it" },
            "target=${mount.text("Destination")}",
            "readonly".takeIf { mount["RW"] is JsonPrimitive && !mount.flag("RW") },
            mount.text("Propagation").takeIf { type == "bind" && it.isNotEmpty() && it != "rprivate" }?.let { "bind-propagation=$it" },
        ).joinToString(",")
    }
    val targets = inspected.objects("Mounts").map { it.text("Destination") }
    val tmpfs = inspected.hostConfig()?.inner("Tmpfs").orEmpty().filterKeys { it !in targets }.map { (target, options) ->
        val given = (options as? JsonPrimitive)?.contentOrNull.orEmpty().split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        listOfNotNull("type=tmpfs", "target=$target", given["size"]?.let { "tmpfs-size=$it" }, given["mode"]?.let { "tmpfs-mode=$it" }).joinToString(",")
    }
    return (mounts + tmpfs).joinToString("\n")
}

// 512m, 1g, 64k or bytes, as docker run takes sizes, in units of 1024
internal fun sizeBytes(text: String): Long {
    val match = Regex("""(-?\d+)([kmgKMG]?)[bB]?""").matchEntire(text.trim()) ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a size: $text")
    val (number, unit) = match.destructured
    val base = number.toLongOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a size: $text")
    return base shl when (unit.lowercase()) {
        "k" -> 10
        "m" -> 20
        "g" -> 30
        else -> 0
    }
}

internal fun size(bytes: Long): String =
    listOf(30 to "g", 20 to "m", 10 to "k").firstOrNull { (shift, _) -> bytes > 0 && bytes % (1L shl shift) == 0L }?.let { (shift, unit) -> "${bytes shr shift}$unit" } ?: "$bytes"

// 1m30s, 500ms or seconds, as Go writes a duration and the healthcheck of docker run takes it
internal fun nanoseconds(text: String): Long {
    if (text.trim().all(Char::isDigit) && text.isNotBlank()) return text.trim().toLong() * 1_000_000_000
    val parts = DURATION.findAll(text.trim()).toList()
    if (parts.isEmpty() || parts.joinToString("") { it.value } != text.trim()) throw DeviceException(ErrorCategory.ArgumentValue, "not a duration: $text")
    return parts.sumOf { part ->
        val (number, unit) = part.destructured
        (number.toDouble() * UNITS.getValue(unit)).toLong()
    }
}

internal fun interval(nanoseconds: Long): String = if (nanoseconds % 1_000_000_000 == 0L) duration(nanoseconds / 1_000_000_000) else "${nanoseconds / 1_000_000}ms"

// the number of CPUs the container may use, in billionths
internal fun nanoCpus(text: String): Long {
    val match = Regex("""(\d+)(?:\.(\d{1,9}))?""").matchEntire(text.trim()) ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a number of CPUs: $text")
    val (whole, fraction) = match.destructured
    return whole.toLong() * 1_000_000_000 + fraction.padEnd(9, '0').toLong()
}

internal fun cpus(nanos: Long): String {
    val fraction = (nanos % 1_000_000_000).toString().padStart(9, '0').trimEnd('0')
    return "${nanos / 1_000_000_000}" + if (fraction.isEmpty()) "" else ".$fraction"
}

// the arguments of a command as the shell of the login would split them, and back: a word with anything but plain characters is quoted
internal fun shellWords(words: List<String>): String = words.joinToString(" ") { word -> if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "-_./:=@%+," }) word else quote(word) }

internal fun lines(value: String) = value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

internal fun array(value: String): JsonArray? = lines(value).takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }

internal fun pairs(value: String): JsonObject? = lines(value).takeIf { it.isNotEmpty() }?.associate { line ->
    if ('=' !in line) throw DeviceException(ErrorCategory.ArgumentValue, "not a name=value line: $line")
    line.substringBefore('=') to JsonPrimitive(line.substringAfter('='))
}?.let(::JsonObject)

internal fun pairsOf(map: JsonObject?) = map.orEmpty().entries.joinToString("\n") { (key, value) -> "$key=${(value as? JsonPrimitive)?.contentOrNull.orEmpty()}" }

private fun MutableMap<String, JsonElement>.place(key: String, value: JsonElement?) {
    if (value == null) remove(key) else put(key, value)
}

// one part of an object of the body, kept with what the field does not touch
private fun MutableMap<String, JsonElement>.within(key: String, change: MutableMap<String, JsonElement>.() -> Unit) {
    val part = (this[key] as? JsonObject).orEmpty().toMutableMap().apply(change)
    place(key, part.takeIf { it.isNotEmpty() }?.let(::JsonObject))
}

private fun JsonObject.config() = inner("Config")

private fun JsonObject.hostConfig() = inner("HostConfig")

private fun objectOf(element: JsonElement) = element as? JsonObject ?: JsonObject(emptyMap())

internal fun many(name: String, element: ValueType = ValueType.Text()) = Argument(name, ValueType.Many(element, "\n"), optional = true)

private fun configText(name: String, key: String) = Field(Argument(name, ValueType.Text(), optional = true), { it.config()?.text(key).orEmpty() }) { spec, value ->
    spec.config.place(key, value.ifEmpty { null }?.let(::JsonPrimitive))
}

private fun configFlag(name: String, key: String) = Field(Argument(name, ValueType.YesNo, optional = true), { "${it.config()?.flag(key) == true}" }) { spec, value ->
    spec.config[key] = JsonPrimitive(value in ValueType.Flag.on)
}

private fun configWords(name: String, key: String) = Field(Argument(name, ValueType.Text(), optional = true), { shellWords(it.config()?.texts(key).orEmpty()) }) { spec, value ->
    spec.config.place(key, words(value).takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) })
}

private fun hostText(name: String, key: String) = Field(Argument(name, ValueType.Text(), optional = true), { it.hostConfig()?.text(key).orEmpty() }) { spec, value ->
    spec.host.place(key, value.ifEmpty { null }?.let(::JsonPrimitive))
}

private fun hostFlag(name: String, key: String) = Field(Argument(name, ValueType.YesNo, optional = true), { "${it.hostConfig()?.flag(key) == true}" }) { spec, value ->
    spec.host[key] = JsonPrimitive(value in ValueType.Flag.on)
}

private fun hostList(name: String, key: String) = Field(many(name), { it.hostConfig()?.texts(key).orEmpty().joinToString("\n") }) { spec, value -> spec.host.place(key, array(value)) }

// zero is what the engine gives a limit nobody set
private fun hostNumber(name: String, key: String) = Field(Argument(name, ValueType.Integer(), optional = true), { it.hostConfig()?.long(key)?.takeIf { n -> n != 0L }?.toString().orEmpty() }) { spec, value ->
    spec.host.place(key, value.ifEmpty { null }?.let { JsonPrimitive(it.toLongOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "$name: $it is no number")) })
}

private fun hostSize(name: String, key: String) = Field(Argument(name, ValueType.Text(), optional = true), { it.hostConfig()?.long(key)?.takeIf { n -> n != 0L }?.let(::size).orEmpty() }) { spec, value ->
    spec.host.place(key, value.ifEmpty { null }?.let { JsonPrimitive(sizeBytes(it)) })
}

private fun healthTime(name: String, key: String) = Field(Argument(name, ValueType.Text(), optional = true), { it.config()?.inner("Healthcheck")?.long(key)?.takeIf { n -> n > 0 }?.let(::interval).orEmpty() }) { spec, value ->
    spec.config.within("Healthcheck") { place(key, value.ifEmpty { null }?.let { JsonPrimitive(nanoseconds(it)) }) }
}

private val RESTARTS = listOf("no", "always", "unless-stopped", "on-failure")

private val LOG_DRIVERS = listOf("json-file", "local", "journald", "syslog", "none", "gelf", "fluentd", "awslogs", "splunk", "gcplogs", "k8s-file")

private val FIELDS: List<Field> = listOf(
    configText("image", "Image"),
    configWords("command", "Cmd"),
    configWords("entrypoint", "Entrypoint"),
    configText("workdir", "WorkingDir"),
    configText("user", "User"),
    configText("hostname", "Hostname"),
    Field(many("labels"), { pairsOf(it.config()?.inner("Labels")) }) { spec, value -> spec.config.place("Labels", pairs(value)) },
    configFlag("tty", "Tty"),
    configFlag("interactive", "OpenStdin"),
    Field(many("env"), { it.config()?.texts("Env").orEmpty().filterNot(::secret).joinToString("\n") }) { spec, value -> spec.visible = lines(value) },
    Field(many("secret-env", ValueType.Secret), { it.config()?.texts("Env").orEmpty().filter(::secret).joinToString("\n") { variable -> variable.substringBefore('=') + "=" } }) { spec, value ->
        spec.hidden = secrets(spec.hidden, value)
    },
    Field(many("ports"), { portsOf(it.hostConfig()?.inner("PortBindings")) }) { spec, value ->
        val bound = bindings(value)
        spec.host.place("PortBindings", JsonObject(bound.mapValues { (_, hosts) ->
            JsonArray(hosts.map { (address, published) -> JsonObject(mapOf("HostIp" to JsonPrimitive(address), "HostPort" to JsonPrimitive(published))) })
        }).takeIf { bound.isNotEmpty() })
        val exposed = spec.config.inner("ExposedPorts").orEmpty() + bound.keys.associateWith { JsonObject(emptyMap()) }
        spec.config.place("ExposedPorts", JsonObject(exposed).takeIf { exposed.isNotEmpty() })
    },
    hostFlag("publish-all", "PublishAllPorts"),
    Field(many("mounts"), ::mountsOf) { spec, value ->
        spec.host.remove("Binds")
        spec.host.remove("Tmpfs")
        spec.host.place("Mounts", lines(value).map(::mountOf).takeIf { it.isNotEmpty() }?.let(::JsonArray))
    },
    Field(Argument("network", ValueType.Enumeration(), optional = true), ::endpointKey) { spec, value ->
        if (value != spec.network) {
            spec.endpoint.remove("IPAMConfig")
            spec.extra.remove(value)
        }
        spec.network = value
        spec.host.place("NetworkMode", value.ifEmpty { null }?.let(::JsonPrimitive))
    },
    Field(many("aliases"), { inspected -> endpointIn(inspected)?.texts("Aliases").orEmpty().filter { it != inspected.text("Id").take(12) }.joinToString("\n") }) { spec, value ->
        spec.endpoint.place("Aliases", array(value))
    },
    Field(Argument("ipv4-address", ValueType.Ip4, optional = true), { endpointIn(it)?.inner("IPAMConfig")?.text("IPv4Address").orEmpty() }) { spec, value ->
        spec.endpoint.within("IPAMConfig") { place("IPv4Address", value.ifEmpty { null }?.let(::JsonPrimitive)) }
    },
    Field(Argument("ipv6-address", ValueType.Ip6, optional = true), { endpointIn(it)?.inner("IPAMConfig")?.text("IPv6Address").orEmpty() }) { spec, value ->
        spec.endpoint.within("IPAMConfig") { place("IPv6Address", value.ifEmpty { null }?.let(::JsonPrimitive)) }
    },
    Field(Argument("mac-address", ValueType.Mac, optional = true), { endpointIn(it)?.text("MacAddress").orEmpty() }) { spec, value ->
        spec.endpoint.place("MacAddress", value.ifEmpty { null }?.let(::JsonPrimitive))
    },
    hostList("dns", "Dns"),
    hostList("dns-search", "DnsSearch"),
    hostList("extra-hosts", "ExtraHosts"),
    Field(Argument("cpus", ValueType.Text(), optional = true), { it.hostConfig()?.long("NanoCpus")?.takeIf { n -> n > 0 }?.let(::cpus).orEmpty() }) { spec, value ->
        spec.host.place("NanoCpus", value.ifEmpty { null }?.let { JsonPrimitive(nanoCpus(it)) })
    },
    hostNumber("cpu-shares", "CpuShares"),
    hostText("cpuset-cpus", "CpusetCpus"),
    hostSize("memory", "Memory"),
    hostSize("memory-swap", "MemorySwap"),
    hostSize("memory-reservation", "MemoryReservation"),
    hostNumber("pids-limit", "PidsLimit"),
    hostSize("shm-size", "ShmSize"),
    Field(many("ulimits"), { inspected ->
        inspected.hostConfig()?.objects("Ulimits").orEmpty().joinToString("\n") { "${it.text("Name")}=${it.text("Soft")}:${it.text("Hard")}" }
    }) { spec, value ->
        spec.host.place("Ulimits", lines(value).map { line ->
            val match = Regex("""([A-Za-z_]+)=(-?\d+)(?::(-?\d+))?""").matchEntire(line) ?: throw DeviceException(ErrorCategory.ArgumentValue, "not a ulimit: $line")
            val (name, soft, hard) = match.destructured
            JsonObject(mapOf("Name" to JsonPrimitive(name), "Soft" to JsonPrimitive(soft.toLong()), "Hard" to JsonPrimitive(hard.ifEmpty { soft }.toLong())))
        }.takeIf { it.isNotEmpty() }?.let(::JsonArray))
    },
    hostFlag("oom-kill-disable", "OomKillDisable"),
    Field(Argument("restart", ValueType.Enumeration(closed = true), RESTARTS, optional = true), { it.hostConfig()?.inner("RestartPolicy")?.text("Name")?.ifEmpty { null } ?: "no" }) { spec, value ->
        spec.host.within("RestartPolicy") {
            put("Name", JsonPrimitive(value.ifEmpty { "no" }))
            if (value != "on-failure") put("MaximumRetryCount", JsonPrimitive(0))
        }
    },
    Field(Argument("restart-retries", ValueType.Integer(min = 0), optional = true, depends = listOf(mapOf("restart" to "on-failure"))), {
        it.hostConfig()?.inner("RestartPolicy")?.long("MaximumRetryCount")?.takeIf { n -> n > 0 }?.toString().orEmpty()
    }) { spec, value -> spec.host.within("RestartPolicy") { put("MaximumRetryCount", JsonPrimitive(value.toLongOrNull() ?: 0)) } },
    Field(Argument("health-cmd", ValueType.Text(), optional = true), { inspected ->
        val test = inspected.config()?.inner("Healthcheck")?.texts("Test").orEmpty()
        when (test.firstOrNull()) {
            "CMD-SHELL" -> test.drop(1).joinToString(" ")
            "CMD" -> shellWords(test.drop(1))
            "NONE" -> "NONE"
            else -> ""
        }
    }) { spec, value ->
        spec.config.within("Healthcheck") {
            place("Test", value.ifEmpty { null }?.let { JsonArray(if (it == "NONE") listOf(JsonPrimitive("NONE")) else listOf(JsonPrimitive("CMD-SHELL"), JsonPrimitive(it))) })
        }
    },
    healthTime("health-interval", "Interval"),
    healthTime("health-timeout", "Timeout"),
    healthTime("health-start-period", "StartPeriod"),
    Field(Argument("health-retries", ValueType.Integer(min = 0), optional = true), { it.config()?.inner("Healthcheck")?.long("Retries")?.takeIf { n -> n > 0 }?.toString().orEmpty() }) { spec, value ->
        spec.config.within("Healthcheck") { place("Retries", value.toLongOrNull()?.let(::JsonPrimitive)) }
    },
    hostFlag("privileged", "Privileged"),
    hostList("cap-add", "CapAdd"),
    hostList("cap-drop", "CapDrop"),
    hostList("security-opt", "SecurityOpt"),
    hostFlag("read-only", "ReadonlyRootfs"),
    hostText("userns", "UsernsMode"),
    Field(many("devices"), { inspected ->
        inspected.hostConfig()?.objects("Devices").orEmpty().joinToString("\n") { listOf(it.text("PathOnHost"), it.text("PathInContainer"), it.text("CgroupPermissions")).joinToString(":") }
    }) { spec, value ->
        spec.host.place("Devices", lines(value).map { line ->
            val parts = line.split(':')
            if (parts.size > 3 || parts[0].isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "not a device: $line")
            JsonObject(mapOf(
                "PathOnHost" to JsonPrimitive(parts[0]),
                "PathInContainer" to JsonPrimitive(parts.getOrNull(1)?.ifEmpty { null } ?: parts[0]),
                "CgroupPermissions" to JsonPrimitive(parts.getOrNull(2)?.ifEmpty { null } ?: "rwm"),
            ))
        }.takeIf { it.isNotEmpty() }?.let(::JsonArray))
    },
    Field(Argument("gpus", ValueType.Text(), optional = true), { inspected ->
        inspected.hostConfig()?.objects("DeviceRequests").orEmpty().firstOrNull { request -> request["Capabilities"].toString().contains("\"gpu\"") }
            ?.long("Count")?.let { if (it < 0) "all" else "$it" }.orEmpty()
    }) { spec, value ->
        val count = when (value) {
            "" -> null
            "all" -> -1L
            else -> value.toLongOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "gpus: $value is no number")
        }
        spec.host.place("DeviceRequests", count?.let { JsonArray(listOf(JsonObject(mapOf("Count" to JsonPrimitive(it), "Capabilities" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("gpu"))))))))) })
    },
    Field(Argument("log-driver", ValueType.Enumeration(), LOG_DRIVERS, optional = true), { it.hostConfig()?.inner("LogConfig")?.text("Type").orEmpty() }) { spec, value ->
        spec.host.within("LogConfig") { place("Type", value.ifEmpty { null }?.let(::JsonPrimitive)) }
    },
    Field(many("log-opt"), { pairsOf(it.hostConfig()?.inner("LogConfig")?.inner("Config")) }) { spec, value ->
        spec.host.within("LogConfig") { place("Config", pairs(value)) }
    },
    hostText("runtime", "Runtime"),
    hostFlag("init", "Init"),
    configText("stop-signal", "StopSignal"),
    Field(Argument("stop-timeout", ValueType.Integer(min = 0, unit = "s"), optional = true), { it.config()?.long("StopTimeout")?.toString().orEmpty() }) { spec, value ->
        spec.config.place("StopTimeout", value.ifEmpty { null }?.let { JsonPrimitive(it.toLongOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "stop-timeout: $it is no number")) })
    },
    hostFlag("auto-remove", "AutoRemove"),
)

private val INFO = listOf("state", "health", "started", "created", "ip-address", "restart-count", "exit-code", "id")

// start: a new container starts once made; recreate: the change that takes a new container is wanted; registries: the logins the
// app keeps, for an image the engine has to pull
private val PSEUDO = setOf("start", "recreate", "registries")

private val CONTAINER_ARGUMENTS = listOf(Argument("name", ValueType.Text(), optional = true)) + FIELDS.map { it.argument } +
    INFO.map { Argument(it, ValueType.Text(), readOnly = true) } +
    listOf(Argument("start", ValueType.YesNo, optional = true, default = "true"), Argument("recreate", ValueType.YesNo, optional = true))

private val CONTAINER_COLUMNS = listOf(
    "name", "image", "state", "health", "status", "ports", "cpu", "memory", "memory-limit", "rx-byte", "tx-byte", "read-byte", "write-byte", "pids",
    "created", "project", "service", "networks", "ip-address", "command",
)

// what docker update takes, by the key of HostConfig the field writes
private val UPDATABLE = mapOf(
    "cpus" to "NanoCpus", "cpu-shares" to "CpuShares", "cpuset-cpus" to "CpusetCpus", "memory" to "Memory", "memory-swap" to "MemorySwap",
    "memory-reservation" to "MemoryReservation", "pids-limit" to "PidsLimit", "restart" to "RestartPolicy", "restart-retries" to "RestartPolicy",
)

private val COMMANDS = listOf(
    "start", "stop", "restart", "pause", "unpause", "kill", "hang-up", "remove-with-volumes", "recreate", "duplicate", "commit",
    "top", "changes", "inspect", "terminal", "ls", "download", "upload", "unused",
)

private val ALIVE = setOf("running", "paused", "restarting")

private val SIGNALS = mapOf("kill" to "KILL", "hang-up" to "HUP")

private val KINDS = listOf("changed", "added", "deleted")

private val ENDPOINT_KEPT = setOf("IPAMConfig", "Links", "DriverOpts", "GwPriority")

private val SECRET_NAME = Regex("pass|secret|token|key|credential|private|auth", RegexOption.IGNORE_CASE)

private val PORT = Regex("""(?:(?:\[([0-9a-fA-F:.]+)\]|(\d{1,3}(?:\.\d{1,3}){3})):)?(?:(\d*):)?(\d+)(?:/(tcp|udp|sctp))?""")

private val DURATION = Regex("""(\d+(?:\.\d+)?)(ns|us|ms|s|m|h|d|w)""")

private val UNITS = mapOf("ns" to 1.0, "us" to 1e3, "ms" to 1e6, "s" to 1e9, "m" to 60e9, "h" to 3600e9, "d" to 86400e9, "w" to 604800e9)

internal const val COMPOSE_PROJECT = "com.docker.compose.project"
internal const val COMPOSE_SERVICE = "com.docker.compose.service"

private const val NEEDS_RECREATE = "this change takes the container made anew: tick Recreate, and what it wrote outside its volumes goes with the old one"
