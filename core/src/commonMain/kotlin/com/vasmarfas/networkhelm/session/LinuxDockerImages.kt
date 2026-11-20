package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

// the images of the engine as docker images lists them, a row a tag: pulled and pushed with the progress of every layer, tagged,
// written to a file of the host and read back from one; what prune would take is listed before it goes
internal class DockerImages(private val api: DockerApi) {
    fun table(present: suspend () -> Boolean) = HostTable(
        IMAGE_COLUMNS, ::list, remove = { remove(it, force = false) }, commands = IMAGE_COMMANDS, run = ::command,
        streams = mapOf("pull" to ::pull, "push" to ::push), present = present,
    )

    private suspend fun list(): List<Item> {
        val used = objects(api.get("/containers/json?all=1"), "/containers/json").groupingBy { it.text("ImageID") }.eachCount()
        val images = objects(api.get("/images/json"), "/images/json")
        val offset = api.offset()
        return imageRows(images, used, offset)
    }

    // a tag removed takes the image along when it was its last one
    private suspend fun remove(ids: List<String>, force: Boolean) {
        for (id in ids) removed { api.send("DELETE", "/images/${urlEncoded(id)}" + if (force) "?force=1" else "") }
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val id by lazy { arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs an image") }
        return when (command) {
            "remove-forced" -> {
                remove(arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }, force = true)
                emptyList()
            }
            "history" -> layers(api.get("/images/${urlEncoded(id)}/history"), api.offset())
            "inspect" -> listOf(Item(id, mapOf("json" to laidOut(api.get("/images/${urlEncoded(id)}/json")))))
            "tag" -> {
                tag(id, arguments["reference"].orEmpty())
                emptyList()
            }
            "unused" -> unused(arguments["all"] in ValueType.Flag.on)
            "save" -> listOf(save(id, hostFile(arguments)))
            "load" -> lines(api.command("load -i ${quote(hostFile(arguments))}")).map { Item("", mapOf("line" to it)) }
            "login" -> login(arguments).map { Item("", mapOf("line" to it)) }
            else -> throw DeviceException(ErrorCategory.MissingItem, "container/image/$command is not on this host")
        }
    }

    private suspend fun tag(id: String, reference: String) {
        if (reference.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the new name of the image")
        val (repository, tag) = repositoryAndTag(reference)
        api.send("POST", "/images/${urlEncoded(id)}/tag?repo=${urlEncoded(repository)}" + if (tag.isEmpty()) "" else "&tag=${urlEncoded(tag)}")
    }

    // what docker image prune takes: images no container uses, only those without a tag unless all are asked for
    private suspend fun unused(all: Boolean): List<Item> = list().filter { it["containers"] == "0" && (all || it["repository"].isNullOrEmpty()) }

    // the CLI writes the archive on the host, where the window of files takes it; one written through sudo goes to the login
    private suspend fun save(id: String, file: String): Item {
        val path = quote(file)
        val written = api.command("save -o $path ${quote(id)} && { [ -z \"\$SUDO_UID\" ] || chown \"\$SUDO_UID:\$SUDO_GID\" $path; } && readlink -f $path")
        return Item(id, mapOf("file" to written.trim()))
    }

    // docker login on the host, at the button of the user alone: the CLI of the host keeps the login in its config file, and says so;
    // the password goes on the input, never on the line of the command
    private suspend fun login(arguments: Map<String, String>): List<String> {
        val user = arguments["username"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the login of the registry")
        val server = arguments["server"]?.ifEmpty { null }?.let { " " + quote(it) }.orEmpty()
        val cli = api.bareCli()
        val result = api.shell("$cli login --username ${quote(user)} --password-stdin$server", arguments["password"].orEmpty())
        return lines(checked(result) + "\n" + result.error)
    }

    private fun hostFile(arguments: Map<String, String>) = arguments["file"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a file of the host")

    // docker pull: a line a layer as it moves, until the engine has the image; a login goes with it for this pull alone
    private fun pull(arguments: Map<String, String>): Flow<Item> = flow {
        val image = arguments["image"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs an image")
        val platform = arguments["platform"].orEmpty()
        val query = pulled(image) + if (platform.isEmpty()) "" else "&platform=${urlEncoded(platform)}"
        val login = arguments["username"].orEmpty()
        val headers = if (login.isEmpty()) emptyMap() else mapOf(AUTH to registryAuth(login, arguments["password"].orEmpty(), registryOf(image)))
        api.lines("POST", "/images/create?$query", headers = headers).collect { emit(Item("", dockerProgress(it))) }
    }

    // docker push of a name the image was tagged with; Docker takes no push without a login, an empty one included
    private fun push(arguments: Map<String, String>): Flow<Item> = flow {
        val image = arguments["image"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs an image")
        val (repository, tag) = repositoryAndTag(image)
        val path = "/images/${urlEncoded(repository)}/push" + if (tag.isEmpty()) "" else "?tag=${urlEncoded(tag)}"
        val headers = mapOf(AUTH to registryAuth(arguments["username"].orEmpty(), arguments["password"].orEmpty(), registryOf(image)))
        api.lines("POST", path, headers = headers).collect { emit(Item("", dockerProgress(it))) }
    }
}

// a row a tag, as docker images lists them; an image without a tag is one row named by its short ID
internal fun imageRows(images: List<JsonObject>, used: Map<String, Int>, offset: String): List<Item> = images.flatMap { image ->
    val id = image.text("Id")
    val short = id.substringAfter(':').take(12)
    val common = linkedMapOf(
        "image-id" to short,
        "created" to moment(image.text("Created"), offset),
        "size" to image.text("Size"),
        "containers" to "${used[id] ?: 0}",
        "digest" to image.texts("RepoDigests").firstOrNull()?.substringAfter('@').orEmpty(),
    )
    val tags = image.texts("RepoTags").filter { it != "<none>:<none>" }
    if (tags.isEmpty()) {
        listOf(Item(id, linkedMapOf("name" to short, "repository" to "", "tag" to "") + common))
    } else {
        tags.map { reference ->
            val (repository, tag) = repositoryAndTag(reference)
            Item(reference, linkedMapOf("name" to reference, "repository" to repository, "tag" to tag) + common)
        }
    }
}

// docker history: the layers from the newest, each with what made it
internal fun layers(history: JsonElement, offset: String): List<Item> = objects(history, "history").map { layer ->
    Item(layer.text("Id"), linkedMapOf(
        "created" to moment(layer.text("Created"), offset),
        "created-by" to layer.text("CreatedBy"),
        "size" to layer.text("Size"),
        "tags" to layer.texts("Tags").joinToString(", "),
        "comment" to layer.text("Comment"),
    ))
}

// registry.local:5000/app:1.2 is the repository registry.local:5000/app and the tag 1.2: the colon of a port comes before a slash
internal fun repositoryAndTag(reference: String): Pair<String, String> {
    val name = reference.substringBefore('@')
    val colon = name.lastIndexOf(':')
    return if (colon > name.lastIndexOf('/')) name.take(colon) to name.drop(colon + 1) else name to ""
}

// the registry is the part before the first slash when it has a dot or a port or is localhost, else Docker Hub
fun registryOf(reference: String): String {
    val first = reference.substringBefore('/', "")
    return if ('.' in first || ':' in first || first == "localhost") first else DOCKER_HUB
}

// Docker Hub goes by several names, and the CLI of Docker sends the oldest one of them
fun hub(server: String): String {
    val name = server.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
    return if (name.isEmpty() || name in HUB) DOCKER_HUB else name
}

// the header of a pull no window asks a login for, that of a new container or of one made anew: the login of the registry of the
// image out of those the app keeps, as it keeps them, and no header without one
internal fun savedLogin(logins: String?, image: String): Map<String, String> {
    val registry = hub(registryOf(image))
    val login = logins?.let { objects(Json.parseToJsonElement(it), "the logins of registries") }?.firstOrNull { hub(it.text("server")) == registry } ?: return emptyMap()
    return mapOf(AUTH to registryAuth(login.text("username"), login.text("password"), registry))
}

// the login as the CLI of Docker sends it: JSON in base64url, an empty object for none
internal fun registryAuth(username: String, password: String, server: String): String {
    val login = buildJsonObject {
        if (username.isNotEmpty()) {
            put("username", username)
            put("password", password)
            put("serveraddress", server)
        }
    }
    return Base64.UrlSafe.encode(login.toString().encodeToByteArray())
}

private val IMAGE_COLUMNS = listOf("name", "repository", "tag", "image-id", "created", "size", "containers", "digest")

private val IMAGE_COMMANDS = listOf("remove-forced", "history", "inspect", "tag", "unused", "save", "load", "login")

private val HUB = setOf("docker.io", "index.docker.io", "index.docker.io/v1", "registry-1.docker.io")

private const val AUTH = "X-Registry-Auth"
const val DOCKER_HUB = "https://index.docker.io/v1/"
