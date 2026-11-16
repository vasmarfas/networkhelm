package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

// the modems the SMS and USSD pages of the web interface take
private val TEXTING = setOf("UsbQmi", "UsbLte")

// the settings of a DSL line the router keeps, by the first word of their names
private val LINE = setOf("operating-mode", "dsl", "adsl", "vdsl")

private const val USSD = "ussd/send"

// the router goes on with a USSD request while the operator answers; a minute with no answer ends it
private const val USSD_ROUNDS = 60

// the DSL line as the DSL page of the web interface shows it: what the modem reports of the line, read with its details, and
// the settings of the line the router keeps; only shown
internal class DslLine(private val api: KeeneticCall, private val components: Set<String>?) : Node("internet/dsl", NodeKind.Settings) {
    override suspend fun read(): List<Item> {
        val name = if (components?.contains("usbdsl") == true) "UsbDsl0" else "Dsl0"
        val details = JsonObject(mapOf("show" to JsonObject(mapOf("interface" to JsonObject(mapOf("name" to JsonPrimitive(name), "details" to JsonPrimitive("yes")))))))
        val (line, config) = api.post(listOf(details, showOf("rc/interface")))
        val settings = (inside(shown(config, "rc/interface"), name) as? JsonObject)?.let(::fields).orEmpty().filterKeys { it.substringBefore('.') in LINE }
        return listOf(Item("", linkedMapOf("name" to name) + (shown(line, "interface") as? JsonObject)?.let(::fields).orEmpty() + settings))
    }
}

// the SMS of the modems as the SMS page shows them, each by its modem and its place in the SIM card or the modem; a new one is
// sent. Reading, sending and removing go to the modem at the paths of their commands, as none of them is a setting to save
internal class Messages(private val api: KeeneticCall) : Node("internet/sms") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments(): List<Argument> {
        val modems = modems(api)
        return listOf(
            Argument("interface", ValueType.Enumeration(closed = true), modems.keys.toList(), labels = modems, default = modems.keys.firstOrNull(), fixed = true),
            Argument("to", ValueType.Text(), fixed = true),
            Argument("message", ValueType.Text(), fixed = true),
            Argument("read", ValueType.YesNo),
        )
    }

    override suspend fun read(): List<Item> = modems(api).keys.flatMap { modem ->
        val answer = answered(api.rest("POST", "sms/list", JsonObject(mapOf("interface" to JsonPrimitive(modem)))))
        ((answer as? JsonObject)?.get("messages") as? JsonObject).orEmpty().map { (place, message) ->
            val values = (message as? JsonObject)?.let(::fields).orEmpty()
            Item(
                "$modem/$place",
                linkedMapOf(
                    "interface" to modem,
                    "from" to values["from"].orEmpty(),
                    "text" to values["text"].orEmpty(),
                    "timestamp" to values["timestamp"].orEmpty(),
                    "read" to (values["read"] ?: "false"),
                    "storage" to if (place.startsWith("sim-")) "sim" else "nv",
                ),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val modem = values["interface"]?.ifEmpty { null } ?: modems(api).keys.firstOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "the router has no modem that sends SMS")
        val to = values["to"].orEmpty()
        val text = values["message"].orEmpty()
        if (to.isEmpty() || text.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "an SMS needs the number it goes to and its text")
        answered(api.rest("POST", "sms/send", JsonObject(mapOf("interface" to JsonPrimitive(modem), "to" to JsonPrimitive(to), "message" to JsonPrimitive(text)))))
        return ""
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val message = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $id")
        if (changes.keys.any { it != "read" }) throw DeviceException(ErrorCategory.Unsupported, "an SMS is only marked as read")
        if (changes["read"] in ValueType.Flag.on && message["read"] != "true") answered(api.rest("POST", "sms/read", placeOf(message)))
    }

    override suspend fun remove(ids: List<String>) {
        val messages = read().associateBy { it.id }
        val chosen = ids.map { messages[it] ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $it") }
        for (message in chosen) answered(api.rest("POST", "sms/delete", placeOf(message)))
    }

    private fun placeOf(message: Item) = JsonObject(mapOf("interface" to JsonPrimitive(message["interface"].orEmpty()), "id" to JsonPrimitive(message.id.substringAfter('/'))))
}

// a USSD request through a modem and the answer of the operator, which the router waits for as a command that goes on; the last
// request and its answer stay in the window
internal class Ussd(private val api: KeeneticCall) : Node("internet/ussd", NodeKind.Settings) {
    override val commands = listOf("print", "set")

    @Volatile
    private var last: Map<String, String> = emptyMap()

    override suspend fun arguments(): List<Argument> {
        val modems = modems(api)
        return listOf(
            Argument("interface", ValueType.Enumeration(closed = true), modems.keys.toList(), labels = modems, default = modems.keys.firstOrNull()),
            Argument("request", ValueType.Text(), placeholder = "*100#"),
            Argument("response", ValueType.Text(), readOnly = true),
        )
    }

    override suspend fun read(): List<Item> = listOf(Item("", last))

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val modem = changes["interface"]?.ifEmpty { null } ?: last["interface"] ?: modems(api).keys.firstOrNull()
            ?: throw DeviceException(ErrorCategory.MissingItem, "the router has no modem for USSD")
        val request = changes["request"]?.ifEmpty { null } ?: last["request"] ?: throw DeviceException(ErrorCategory.ArgumentValue, "a USSD request is needed, such as *100#")
        var answer = api.rest("POST", USSD, JsonObject(mapOf("interface" to JsonPrimitive(modem), "request" to JsonPrimitive(request))))
        var rounds = 0
        try {
            while (goesOn(answer)) {
                if (rounds++ == USSD_ROUNDS) throw DeviceException(ErrorCategory.General, "the operator gave no answer to $request in a minute")
                delay(1.seconds)
                answer = api.rest("GET", USSD)
            }
        } finally {
            if (goesOn(answer)) withContext(NonCancellable) { api.rest("DELETE", USSD) }
        }
        answered(answer)
        last = linkedMapOf("interface" to modem, "request" to request, "response" to (answer as? JsonObject)?.text("response").orEmpty())
    }

    private fun goesOn(answer: JsonElement) = ((answer as? JsonObject)?.get("continued") as? JsonPrimitive)?.booleanOrNull == true
}

// the drives of the USB ports and their partitions, as Storages and Devices shows them, each ejected safely as the button of the
// web interface does
internal class Storages(private val api: KeeneticCall) : Node("system/storage"), Commanded {
    override val commands = listOf("print", "eject")

    override suspend fun read(): List<Item> = (api.shows("media").single() as? JsonObject).orEmpty().mapNotNull { (id, element) ->
        val media = element as? JsonObject ?: return@mapNotNull null
        val partitions = (media["partition"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }
        val values = fields(JsonObject(media - "partition"))
        val free = partitions.mapNotNull { it.text("free")?.toLongOrNull() }
        Item(
            id,
            linkedMapOf(
                "name" to listOfNotNull(values["manufacturer"], values["product"]).joinToString(" ").trim(),
                "state" to values["state"].orEmpty(),
                "size" to values["size"].orEmpty(),
                "free" to if (free.isEmpty()) "" else "${free.sum()}",
                "partitions" to partitions.joinToString(", ") { listOfNotNull(it.text("label")?.ifEmpty { null } ?: it.text("uuid"), it.text("fstype")).joinToString(" ") },
                "usb" to listOfNotNull(values["usb.port"], values["usb.usb-version"] ?: values["usb.version"]).joinToString(" "),
                "ejectable" to values["ejectable"].orEmpty(),
            ) + (values - "name"),
        )
    }

    override suspend fun run(command: String, arguments: Map<String, String>): List<Item> {
        if (command != "eject") throw DeviceException(ErrorCategory.MissingItem, "Keenetic has no $path/$command")
        val ids = arguments["numbers"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val drives = read().map { it.id }.toSet()
        ids.firstOrNull { it !in drives }?.let { throw DeviceException(ErrorCategory.MissingItem, "$path has no $it") }
        for (id in ids) answered(api.rest("POST", "system/eject", JsonObject(mapOf("name" to JsonPrimitive(id)))))
        return emptyList()
    }
}

// OPKG as the router keeps it, the drive of Entware and its scripts, and the components of OPKG the router has; only shown
internal class Opkg(private val api: KeeneticCall, private val components: Set<String>?) : Node("system/opkg", NodeKind.Settings) {
    override suspend fun read(): List<Item> {
        val settings = (api.shows("rc/opkg").single() as? JsonObject)?.let(::fields).orEmpty()
        return listOf(Item("", settings + ("components" to components.orEmpty().filter { it.startsWith("opkg") }.sorted().joinToString(", "))))
    }
}

// IntelliQoS as the router keeps it: whether the classifier puts its priorities on the traffic, and the limits of the upstream
// of each connection; only shown
internal class IntelliQos(private val api: KeeneticCall) : Node("qos", NodeKind.Settings) {
    override suspend fun read(): List<Item> {
        val (qos, limits) = api.shows("rc/ntce/qos", "rc/ntce/upstream/rate-limit")
        val upstream = rows(limits).flatMap { row -> row.values.map { (key, value) -> "rate-limit.${row.id}.$key" to value } }
        return listOf(Item("", (qos as? JsonObject)?.let(::fields).orEmpty() + upstream))
    }
}

private suspend fun modems(api: KeeneticCall): Map<String, String> =
    interfaces(api.shows("interface").single(), JsonObject(emptyMap())).filter { it["type"] in TEXTING && it["plugged"] != "no" }.let(::titles)

// a command at a path of its own answers with its failure in the status list; the text of the router is shown as it is
private fun answered(answer: JsonElement): JsonElement {
    failure(answer)?.let { throw DeviceException(ErrorCategory.ArgumentValue, it) }
    return answer
}
