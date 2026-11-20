package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.json.JsonObject

// the pods of Podman, from its own API: the containers of a pod share its network and start and stop with it
internal class DockerPods(private val api: DockerApi) {
    fun table(present: suspend () -> Boolean) = HostTable(POD_COLUMNS, ::list, remove = ::remove, commands = POD_COMMANDS, run = ::command, present = present)

    private suspend fun list(): List<Item> {
        val pods = objects(api.get("$LIBPOD/pods/json"), "/pods/json")
        val offset = api.offset()
        return pods.map { podRow(it, offset) }
    }

    // a pod goes with its containers
    private suspend fun remove(ids: List<String>) {
        for (id in ids) removed { api.send("DELETE", "$LIBPOD/pods/${urlEncoded(id)}?force=true") }
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        if (command !in POD_COMMANDS) throw DeviceException(ErrorCategory.MissingItem, "container/pod/$command is not on this host")
        for (id in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) api.send("POST", "$LIBPOD/pods/${urlEncoded(id)}/$command")
        return emptyList()
    }
}

// a row a pod: its own containers by name, without the one that only holds its namespaces
internal fun podRow(pod: JsonObject, offset: String): Item {
    val containers = pod.objects("Containers")
    val infra = pod.text("InfraId")
    return Item(pod.text("Id"), linkedMapOf(
        "name" to pod.text("Name"),
        "status" to pod.text("Status"),
        "containers" to containers.filter { it.text("Id") != infra }.joinToString(", ") { it.text("Names") },
        "running" to "${containers.count { it.text("Status") == "running" }}/${containers.size}",
        "networks" to pod.texts("Networks").joinToString(", "),
        "created" to moment(pod.text("Created"), offset),
        "pod-id" to pod.text("Id").take(12),
    ))
}

private val POD_COLUMNS = listOf("name", "status", "containers", "running", "networks", "created", "pod-id")

private val POD_COMMANDS = listOf("start", "stop", "restart", "pause", "unpause", "kill")

// the API of Podman for pods names a version of its own, which must not be below what this Podman still takes
private const val LIBPOD = "/v4.0.0/libpod"
