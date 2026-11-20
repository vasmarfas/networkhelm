package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// the volumes of the engine with the containers that mount them. A volume is browsed through a container of its own that mounts
// it, made when the user agrees and removed when the window is done with it; container makes and starts one as the table does
internal class DockerVolumes(private val api: DockerApi, private val container: suspend (Map<String, String>) -> String) {
    fun table(present: suspend () -> Boolean) = HostTable(
        VOLUME_COLUMNS, ::list, arguments = { VOLUME_ARGUMENTS }, add = ::create, remove = ::remove, commands = VOLUME_COMMANDS, run = ::command, present = present,
    )

    private suspend fun list(): List<Item> {
        val volumes = (api.get("/volumes") as? JsonObject)?.objects("Volumes").orEmpty()
        val users = objects(api.get("/containers/json?all=1"), "/containers/json")
            .flatMap { container -> container.objects("Mounts").filter { it.text("Type") == "volume" }.map { it.text("Name") }.distinct() }
            .groupingBy { it }.eachCount()
        val offset = api.offset()
        return volumes.map { volumeRow(it, users[it.text("Name")] ?: 0, offset) }
    }

    private suspend fun create(values: Map<String, String>): String = api.created("/volumes/create", buildJsonObject {
        values["name"]?.ifEmpty { null }?.let { put("Name", it) }
        values["driver"]?.ifEmpty { null }?.let { put("Driver", it) }
        pairs(values["options"].orEmpty())?.let { put("DriverOpts", it) }
        pairs(values["labels"].orEmpty())?.let { put("Labels", it) }
    }).text("Name")

    private suspend fun remove(names: List<String>) {
        for (name in names) removed { api.send("DELETE", "/volumes/${urlEncoded(name)}") }
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> = when (command) {
        // what docker volume prune takes: volumes no container mounts, only those the engine made for a container unless all are asked for
        "unused" -> list().filter { it["containers"] == "0" && (arguments["all"] in ValueType.Flag.on || it["anonymous"] == "true") }
        "browse" -> listOf(browse(arguments["id"].orEmpty()))
        "unbrowse" -> {
            unbrowse(arguments["id"].orEmpty())
            emptyList()
        }
        else -> throw DeviceException(ErrorCategory.MissingItem, "container/volume/$command is not on this host")
    }

    // a container of a small image with the volume at /volume, whose files come and go as those of any container; it ends by itself
    // in an hour and is removed then, should the window never say it is done. sleep ignores the signal to stop, and Podman waits the
    // stop timeout out before it removes a running container
    private suspend fun browse(volume: String): Item {
        if (volume.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a volume")
        val id = container(mapOf(
            "image" to BROWSER_IMAGE, "command" to "sleep 3600", "mounts" to "type=volume,source=$volume,target=$BROWSED",
            "network" to "none", "auto-remove" to "true", "stop-timeout" to "1", "labels" to "$BROWSER=$volume",
        ))
        return Item(id, mapOf("container" to id, "path" to BROWSED))
    }

    // every container that browses the volume goes, one of a window that never said it was done too; the engine finds them by their
    // label, and one without it stays even with an engine that ignores the filter
    private suspend fun unbrowse(volume: String) {
        if (volume.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a volume")
        val filter = urlEncoded("""{"label":["$BROWSER=$volume"]}""")
        val browsers = objects(api.get("/containers/json?all=1&filters=$filter"), "/containers/json").filter { it.inner("Labels")?.text(BROWSER) == volume }
        for (browser in browsers) removed { api.send("DELETE", "/containers/${urlEncoded(browser.text("Id"))}?force=1") }
    }
}

internal fun volumeRow(volume: JsonObject, users: Int, offset: String): Item {
    val name = volume.text("Name")
    return Item(name, linkedMapOf(
        "name" to name,
        "driver" to volume.text("Driver"),
        "scope" to volume.text("Scope"),
        "mountpoint" to volume.text("Mountpoint"),
        "created" to moment(volume.text("CreatedAt"), offset),
        "containers" to "$users",
        // Docker marks a volume it made for a container by a label, Podman leaves it the ID it got for a name
        "anonymous" to "${volume.inner("Labels")?.containsKey(ANONYMOUS) == true || ANONYMOUS_NAME.matches(name)}",
        "options" to pairsOf(volume.inner("Options")),
        "labels" to pairsOf(volume.inner("Labels")),
    ))
}

private val VOLUME_COLUMNS = listOf("name", "driver", "containers", "anonymous", "mountpoint", "scope", "created")

private val VOLUME_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), optional = true),
    Argument("driver", ValueType.Enumeration(), listOf("local"), optional = true, default = "local"),
    many("options"),
    many("labels"),
) + listOf("mountpoint", "scope", "created", "containers", "anonymous").map { Argument(it, ValueType.Text(), readOnly = true) }

private val VOLUME_COMMANDS = listOf("unused", "browse", "unbrowse")

private val ANONYMOUS_NAME = Regex("[0-9a-f]{64}")

private const val ANONYMOUS = "com.docker.volume.anonymous"
private const val BROWSER = "networkhelm.browse"
private const val BROWSER_IMAGE = "docker.io/library/alpine:latest"
private const val BROWSED = "/volume"
