package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

private const val NOT_USED = "not-used"
private const val SELECTED_WAN = "SelectedWan"
private const val SELECTED_SCHEDULE = "SelectedSchedule"

private val LED_MODES = linkedMapOf("on" to "All indicators on", "all" to "All indicators off", "front" to "Main indicators off", "back" to "Port indicators off")

private val PRESSES = linkedMapOf("click" to "Quick pressing", "double-click" to "Double pressing", "hold" to "Long pressing")

private val HANDLERS = listOf(
    "FactoryReset", "Reboot", "WifiToggle", "WifiGuestApToggle", "LedToggle", "WpsStartMainAp", "WpsStartMainAp5", "WpsStartStation", "WpsStartStation5",
    "WpsStartRepeater", "WpsStartRepeater5", "WpsStartExtender", "WpsStartExtender5", "UnmountUsb1", "UnmountUsb2", "UnmountAll", "DlnaDirectoryRescan",
    "DlnaDirectoryFullRescan", "TorrentAltSpeedToggle", "TorrentClientStateToggle", "DectHandsetRegistrationToggle", "DectHandsetPagingToggle", "OpkgRunScript",
)

private val CONTROLS = listOf(SELECTED_SCHEDULE, SELECTED_WAN, "BackupWan", "UpdatesAvailable", "UsbPortDeviceAttached", "Usb1PortDeviceAttached", "Usb2PortDeviceAttached", "ExtenderMode")

// the texts of the windows of the IPv6 firewall, the schedules, the LEDs, the buttons and the updates, and the keys under which the
// dictionary of the web interface names the same thing; the jobs of the buttons and the LEDs go by the names the router gives them
internal val SYSTEM_KEYS = mapOf(
    "Code" to "firewall.rule-table.icmp-details.code",
    "Direction" to "diagnostics.tools.stream",
    "Schedule name" to "schedules.editor.fields.description",
    "Schedule editor" to "schedules.editor.title",
    "Work schedule" to "schedules.select.default-title",
    "Indicators & Buttons" to "system.tabs.indicators-and-buttons",
    "Operation Indicators" to "system.led-indicators.title",
    "Indicator mode" to "system.led-indicators.mode.label",
    "All indicators on" to "system.led-indicators.mode.options.enabled",
    "All indicators off" to "system.led-indicators.mode.options.disabled-all",
    "Main indicators off" to "system.led-indicators.mode.options.disabled-front",
    "Port indicators off" to "system.led-indicators.mode.options.disabled-back",
    "Indicator" to "system.buttons-and-leds.leds.led",
    "Turns on" to "system.buttons-and-leds.leds.selectbox-label",
    "Never" to "system.buttons-and-leds.leds.control.not-used",
    "Not used" to "system.buttons-and-leds.buttons.handler.not-used",
    "By default" to "default",
    "Installed version" to "system.components.current-fw-version",
    "Available version" to "system.components.available-version",
    "Automatic updates" to "system.components.auto-update.toggle-label",
    "Install updates" to "system.components.auto-update.schedule.dropdown-label",
    "Update available" to "dashboard.card_system.updateAvailable",
    "Update KeeneticOS" to "system.components.ui.update-firmware-btn",
) + PRESSES.entries.associate { (press, text) -> text to "system.buttons-and-leds.buttons.action.$press" } +
    HANDLERS.associateWith { "system.buttons-and-leds.buttons.handler.$it" } +
    CONTROLS.associateWith { "system.buttons-and-leds.leds.control.$it" }

// a window whose buttons run commands of the router, as Check for Updates and Update KeeneticOS of the updates
internal interface Commanded {
    suspend fun run(command: String, arguments: Map<String, String>): List<Item>
}

// the operation indicators of Indicators & Buttons of the web interface: all LEDs on, or all, the main or the port LEDs off,
// at all times or while a schedule runs
internal class Leds(private val api: KeeneticCall) : Node("system/led", NodeKind.Settings) {
    override val commands = listOf("print", "set")

    override suspend fun arguments(): List<Argument> {
        val schedules = scheduleNames(api.shows("rc/schedule").single())
        return listOf(
            Argument("mode", ValueType.Enumeration(closed = true), LED_MODES.keys.toList(), labels = LED_MODES),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + schedules.keys, optional = true, labels = schedules, depends = LED_MODES.keys.drop(1).map { mapOf("mode" to it) }),
        )
    }

    // show rc keeps the shutdown in a list with the jobs of the LEDs, or alone; the LEDs are on where it keeps none
    override suspend fun read(): List<Item> {
        val config = api.shows("rc/system/led").single()
        val power = (((config as? JsonArray)?.firstOrNull { it is JsonObject && "power" in it } ?: config) as? JsonObject)?.let(::fields).orEmpty()
        return listOf(Item("", linkedMapOf("mode" to (power["power.shutdown.mode"] ?: "on"), "schedule" to power["power.schedule"].orEmpty())))
    }

    // the mode and its schedule go together, as the web interface sends them
    override suspend fun set(id: String?, changes: Map<String, String?>) {
        if ("mode" !in changes && "schedule" !in changes) return
        val old = read().first()
        val mode = changes["mode"]?.ifEmpty { null } ?: old["mode"].orEmpty()
        if (mode !in LED_MODES) throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no mode of the LEDs")
        val schedule = changes["schedule"] ?: old["schedule"].orEmpty()
        val on = mode == "on"
        val power = JsonObject(mapOf("schedule" to if (on || schedule.isEmpty()) NO else JsonPrimitive(schedule), "shutdown" to if (on) NO else JsonObject(mapOf("mode" to JsonPrimitive(mode)))))
        api.post(listOf(nested(listOf("system", "led"), JsonObject(mapOf("power" to power)))))
    }
}

// the LEDs a user may give another job, with what each shows now and by default. A LED that follows an internet connection or
// a schedule names it after its job, SelectedWan__PPPoE0, as the web interface does: the interface keeps led wan, the schedule led
internal class LedBindings(private val api: KeeneticCall, private val words: () -> Map<String, String>) : Node("system/led/binding") {
    override val commands = listOf("print", "set")

    override suspend fun arguments(): List<Argument> {
        val (controls, shown, schedules) = api.shows("led/controls", "interface", "rc/schedule")
        val word = words()
        val own = ((controls as? JsonObject)?.get("control") as? JsonObject).orEmpty().filterValues { it.flag("user_configurable") }.keys
        val choices = linkedMapOf(NOT_USED to (word["Never"] ?: "Never"))
        for (control in own) {
            val named = word[control] ?: control
            when (control) {
                SELECTED_WAN -> interfaces(shown, JsonObject(emptyMap())).filter { it["global"] == "true" }.forEach { choices["${control}__${it.id}"] = "$named ${title(it)}" }
                SELECTED_SCHEDULE -> scheduleNames(schedules).forEach { (name, description) -> choices["${control}__$name"] = "$named $description" }
                else -> choices[control] = named
            }
        }
        val labels = choices + CONTROLS.filter { it !in choices }.associateWith { word[it] ?: it }
        return listOf(
            Argument("led", ValueType.Text(), readOnly = true),
            Argument("control", ValueType.Enumeration(closed = true), choices.keys.toList(), labels = labels),
            Argument("default", ValueType.Enumeration(closed = true), labels.keys.toList(), readOnly = true, labels = labels),
        )
    }

    override suspend fun read(): List<Item> {
        val (bindings, config, schedules) = api.shows("led/bindings", "rc/interface", "rc/schedule")
        val wan = (config as? JsonObject).orEmpty().entries.firstOrNull { ((it.value as? JsonObject)?.get("led") as? JsonObject)?.containsKey("wan") == true }?.key
        val plan = (schedules as? JsonObject).orEmpty().entries.firstOrNull { (it.value as? JsonObject)?.containsKey("led") == true }?.key
        return rows(bindings).filter { it["user_configurable"] == "true" }.map { binding ->
            val active = binding["active_control"].orEmpty()
            val control = when {
                active.isEmpty() -> NOT_USED
                active == SELECTED_WAN && wan != null -> "${active}__$wan"
                active == SELECTED_SCHEDULE && plan != null -> "${active}__$plan"
                else -> active
            }
            val led = binding["led"].orEmpty()
            Item(led, linkedMapOf("led" to led, "control" to control, "default" to binding["default_control"].orEmpty().ifEmpty { NOT_USED }))
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val control = changes["control"] ?: return
        val led = read().firstOrNull { it.id == id }?.id ?: throw DeviceException(ErrorCategory.MissingItem, "system/led/binding has no $id")
        val kind = control.substringBefore("__")
        val target = control.substringAfter("__", "")
        val off = kind == NOT_USED || kind.isEmpty()
        val binding = mapOf("led" to JsonPrimitive(led), "control" to JsonPrimitive(if (off) "" else kind), "indicate" to JsonPrimitive(true)) + if (off) mapOf("no" to JsonPrimitive(true)) else emptyMap()
        api.post(
            listOfNotNull(
                nested(listOf("interface", target, "led"), JsonObject(mapOf("wan" to JsonPrimitive(true)))).takeIf { kind == SELECTED_WAN && target.isNotEmpty() },
                nested(listOf("schedule"), JsonObject(mapOf("name" to JsonPrimitive(target), "led" to JsonObject(mapOf("action" to JsonPrimitive("start")))))).takeIf { kind == SELECTED_SCHEDULE && target.isNotEmpty() },
                nested(listOf("system", "led"), JsonObject(binding)),
            ),
        )
    }
}

// what the buttons of the router do at a press, a double press and a long one. A binding the router protects, as the factory reset
// at a long press of Reset, is only shown, and a job the router protects is offered only where it is already
internal class Buttons(private val api: KeeneticCall) : Node("system/button") {
    override val commands = listOf("print", "set")

    override suspend fun arguments(): List<Argument> {
        val handlers = ((api.shows("button/handlers").single() as? JsonObject)?.get("handler") as? JsonObject).orEmpty()
        val offered = handlers.filterValues { !it.flag("switch_related") && !it.flag("protected") }.keys
        val names = mapOf(NOT_USED to "Not used") + handlers.keys.associateWith { it }
        return listOf(
            Argument("button", ValueType.Text(), readOnly = true),
            Argument("action", ValueType.Enumeration(closed = true), PRESSES.keys.toList(), readOnly = true, labels = PRESSES),
            Argument("handler", ValueType.Enumeration(closed = true), listOf(NOT_USED) + offered, labels = names),
            Argument("default", ValueType.Enumeration(closed = true), names.keys.toList(), readOnly = true, labels = names),
        )
    }

    // the switches of the router, such as a mode slider, take no job
    override suspend fun read(): List<Item> {
        val (buttons, bindings) = api.shows("button", "button/bindings")
        val pressed = (((buttons as? JsonObject)?.get("buttons") as? JsonObject)?.get("button") as? JsonObject).orEmpty().filterValues { !it.flag("is_switch") }.keys
        return rows(bindings).filter { it["button"] in pressed }.map { binding ->
            val button = binding["button"].orEmpty()
            val action = binding["action"].orEmpty()
            Item(
                "$button/$action",
                linkedMapOf(
                    "button" to button,
                    "action" to action,
                    "handler" to binding["active_handler"].orEmpty().ifEmpty { NOT_USED },
                    "default" to binding["default_handler"].orEmpty().ifEmpty { NOT_USED },
                    "protected" to (binding["protected"] ?: "false"),
                ),
            )
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val handler = changes["handler"] ?: return
        val binding = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "system/button has no $id")
        if (binding["protected"] == "true") throw DeviceException(ErrorCategory.Unsupported, "the router keeps what ${binding["button"]} does at ${binding["action"]}")
        val off = handler == NOT_USED || handler.isEmpty()
        val part = mapOf("button" to JsonPrimitive(binding["button"].orEmpty()), "handler" to JsonPrimitive(if (off) "" else handler), "action" to JsonPrimitive(binding["action"].orEmpty())) +
            if (off) mapOf("no" to JsonPrimitive(true)) else emptyMap()
        api.post(listOf(nested(listOf("system", "button"), JsonObject(part))))
    }
}

// KeeneticOS and Updates of the web interface: the version installed, the channel, the automatic update and the time it may
// install in, and the commands that look for a new version and install it. The router looks with components list, which asks
// the update server and takes seconds, so a read shows what the last check found; components commit downloads the update,
// writes it to flash and restarts the router
internal class Firmware(private val api: KeeneticCall) : Node("system/update", NodeKind.Settings), Commanded {
    override val commands = listOf("print", "set", "check-update", "install")

    @Volatile
    private var found: Map<String, String> = emptyMap()

    override suspend fun arguments(): List<Argument> {
        val (version, auto, schedules) = api.shows("version", "rc/components/auto-update", "rc/schedule")
        val current = ((auto as? JsonObject)?.text("channel") ?: (version as? JsonObject)?.text("sandbox")).orEmpty()
        val channels = (CHANNELS + current).filter { it.isNotEmpty() }.distinct()
        val plans = scheduleNames(schedules)
        return listOf(
            Argument("installed-version", ValueType.Text(), readOnly = true),
            Argument("release", ValueType.Text(), readOnly = true),
            Argument("channel", ValueType.Enumeration(), channels, labels = channels.associateWith { it }),
            Argument("auto-update", ValueType.YesNo),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + plans.keys, optional = true, labels = plans),
            Argument("latest-version", ValueType.Text(), readOnly = true),
            Argument("update-available", ValueType.YesNo, readOnly = true),
            Argument("progress", ValueType.Integer(0, 100, unit = "%"), readOnly = true),
        )
    }

    override suspend fun read(): List<Item> {
        val (version, auto) = api.shows("version", "rc/components/auto-update")
        val about = (version as? JsonObject)?.let(::fields).orEmpty()
        val own = (auto as? JsonObject)?.let(::fields).orEmpty()
        val values = linkedMapOf(
            "installed-version" to about["title"].orEmpty(),
            "release" to about["release"].orEmpty(),
            "channel" to (own["channel"] ?: about["sandbox"]).orEmpty(),
            "auto-update" to "${own["disable"] != "true"}",
            "schedule" to own["schedule"].orEmpty(),
        )
        return listOf(Item("", values + found))
    }

    // a check made in another channel tells nothing of the new one
    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val parts = buildMap<String, JsonElement> {
            changes["channel"]?.ifEmpty { null }?.let { put("channel", JsonPrimitive(it)) }
            changes["auto-update"]?.let { put("disable", JsonPrimitive(it !in ValueType.Flag.on)) }
            changes["schedule"]?.let { put("schedule", if (it.isEmpty()) NO else JsonPrimitive(it)) }
        }
        if (parts.isEmpty()) return
        api.post(listOf(nested(listOf("components", "auto-update"), JsonObject(parts))))
        if ("channel" in parts) found = emptyMap()
    }

    override suspend fun run(command: String, arguments: Map<String, String>): List<Item> {
        when (command) {
            "check-update" -> check()
            "install" -> install()
            else -> throw DeviceException(ErrorCategory.MissingItem, "Keenetic has no $path/$command")
        }
        return emptyList()
    }

    // the router lists the components of the channel with the version of each it has and the one it would install
    private suspend fun check() {
        val channel = read().first()["channel"].orEmpty()
        val list = continued("components/list", JsonObject(if (channel.isEmpty()) emptyMap() else mapOf("sandbox" to JsonPrimitive(channel))))
        val base = ((list["component"] as? JsonObject)?.get("base") as? JsonObject).orEmpty()
        val firmware = (list["firmware"] as? JsonObject).orEmpty()
        val latest = firmware.text("title") ?: firmware.text("version") ?: base.text("version")
        found = mapOf("latest-version" to latest.orEmpty(), "update-available" to "${base.text("installed") != base.text("version")}")
    }

    // the update is looked for again first, as the web interface offers it only after a check; the window waits until the flash is
    // written, the restart that follows ends the session
    private suspend fun install() {
        check()
        if (found["update-available"] != "true") throw DeviceException(ErrorCategory.ArgumentValue, "the router found no other KeeneticOS in its channel")
        continued("components/commit", JsonObject(mapOf("reason" to JsonPrimitive("manual")))) { answer ->
            (answer["progress"] as? JsonObject)?.text("flash:firmware")?.toDoubleOrNull()?.let { found = found + ("progress" to "${(it / 2).roundToInt()}") }
            "file_size" in answer
        }
    }

    // a command that goes on, as the web interface runs it: POST, then a GET each second while the answer says continued
    private suspend fun continued(target: String, body: JsonObject, done: (JsonObject) -> Boolean = { false }): JsonObject {
        var answer = api.rest("POST", target, body) as? JsonObject ?: JsonObject(emptyMap())
        while (true) {
            failure(answer)?.let { throw DeviceException(ErrorCategory.Api, it) }
            if (done(answer) || (answer["continued"] as? JsonPrimitive)?.booleanOrNull != true) return answer
            delay(1.seconds)
            answer = api.rest("GET", target) as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private companion object {
        val CHANNELS = listOf("stable", "preview", "draft")
    }
}

// the schedules by their names, with the description the web interface shows for each
internal fun scheduleNames(schedules: JsonElement): Map<String, String> =
    named(schedules).associate { (name, schedule) -> name to (schedule["description"]?.ifEmpty { null } ?: name) }

private fun JsonElement.flag(name: String) = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.booleanOrNull == true
