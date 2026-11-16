package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// the days of the week as the router counts them, from Sunday
private val DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

private val ENTRY = Regex("(start|stop)\\s+(\\d{1,2}):(\\d{1,2})\\s+(\\*|[a-z]{3}(,[a-z]{3})*)")

// the schedules that rules, connections, access points and the LEDs follow. The router knows a schedule by its name, the web
// interface by its description, and it holds the moments it starts and stops, each on its days of the week, which the window
// writes as "start 08:00 mon,tue,wed; stop 17:00 mon,tue,wed", * for every day
internal class Schedules(private val api: KeeneticCall) : Node("schedule") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text(), readOnly = true),
        Argument("description", ValueType.Text()),
        Argument("actions", ValueType.Text(), optional = true, placeholder = "start 08:00 mon,tue; stop 17:00 mon,tue"),
    )

    override suspend fun read(): List<Item> = (api.shows("rc/schedule").single() as? JsonObject).orEmpty().map { (name, element) ->
        val schedule = element as? JsonObject
        val actions = when (val action = schedule?.get("action")) {
            is JsonArray -> action.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(action)
            else -> emptyList()
        }
        Item(name, linkedMapOf("name" to name, "description" to schedule?.text("description").orEmpty(), "actions" to actions.joinToString("; ", transform = ::entry)))
    }

    // the web interface names a new schedule by the first number no other one has
    override suspend fun add(values: Map<String, String>): String {
        val description = values["description"].orEmpty()
        if (description.isBlank()) throw DeviceException(ErrorCategory.ArgumentValue, "a schedule needs its description")
        val taken = read().map { it.id }.toSet()
        val name = generateSequence(0) { it + 1 }.map { "schedule$it" }.first { it !in taken }
        val actions = actions(values["actions"].orEmpty())
        api.post(listOf(schedule(name, mapOf<String, JsonElement>("description" to JsonPrimitive(description)) + listOfNotNull(actions.takeIf { it.isNotEmpty() }?.let { "action" to JsonArray(it) }))))
        return name
    }

    // new moments take the place of all the old ones
    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "schedule holds schedules, not settings")
        if (read().none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "schedule has no $name")
        val parts = buildMap<String, JsonElement> {
            changes["description"]?.let { description ->
                if (description.isBlank()) throw DeviceException(ErrorCategory.ArgumentValue, "a schedule needs its description")
                put("description", JsonPrimitive(description))
            }
            changes["actions"]?.let { put("action", JsonArray(listOf(NO) + actions(it))) }
        }
        if (parts.isNotEmpty()) api.post(listOf(schedule(name, parts)))
    }

    // a schedule with no name could stand for every schedule, as no without a key stands for the whole list
    override suspend fun remove(ids: List<String>) {
        if (ids.any { it.isBlank() }) throw DeviceException(ErrorCategory.ArgumentValue, "a schedule with no name is not removed")
        api.post(ids.map { schedule(it, mapOf("no" to JsonPrimitive(true))) })
    }

    private fun schedule(name: String, parts: Map<String, JsonElement>) = JsonObject(mapOf("schedule" to JsonObject(mapOf<String, JsonElement>("name" to JsonPrimitive(name)) + parts)))

    private fun actions(text: String): List<JsonObject> = text.split(';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { line ->
        val parts = ENTRY.matchEntire(line)?.groupValues
        val hour = parts?.get(2)?.toInt()
        val minute = parts?.get(3)?.toInt()
        val days = parts?.get(4)?.let { days -> if (days == "*") days else days.split(',').map(DAYS::indexOf).takeIf { it.none { day -> day < 0 } }?.joinToString(",") }
        if (parts == null || hour == null || hour > 23 || minute == null || minute > 59 || days == null) {
            throw DeviceException(ErrorCategory.ArgumentValue, "\"$line\" is no start or stop, a time as 08:00 and days as mon,tue or *")
        }
        JsonObject(mapOf("action" to JsonPrimitive(parts[1]), "hour" to JsonPrimitive("$hour"), "min" to JsonPrimitive("$minute"), "dow" to JsonPrimitive(days)))
    }

    // the router keeps the hour, the minute and the days as text; days it counts otherwise stay as it gives them
    private fun entry(action: JsonObject): String {
        val dow = action.text("dow").orEmpty()
        val days = if (dow == "*") dow else dow.split(',').joinToString(",") { day -> day.trim().toIntOrNull()?.let(DAYS::getOrNull) ?: day }
        return "${action.text("action").orEmpty()} ${action.text("hour").orEmpty().padStart(2, '0')}:${action.text("min").orEmpty().padStart(2, '0')} $days"
    }
}
