package com.vasmarfas.networkhelm.packs

import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacksTest {

    private val commands = Json.parseToJsonElement(File(System.getProperty("networkhelm.commands")).readText()).jsonObject

    private val oldest = RouterOsVersion.parse(commands.getValue("versions").jsonArray.first().jsonPrimitive.content)

    private fun older(until: String?) = until?.let(RouterOsVersion::parse)?.let { oldest != null && it <= oldest } == true

    private val ofRouterOs6 = setOf("channel.width")

    // the menus of extra packages, which the snapshots of the main package do not hold
    private val extra = setOf("container")

    private fun node(path: String): JsonObject? {
        var children = commands.getValue("menus").jsonObject
        var node: JsonObject? = null
        for (segment in path.split('/')) {
            node = children[segment]?.jsonObject ?: return null
            children = node["c"]?.jsonObject ?: JsonObject(emptyMap())
        }
        return node
    }

    private fun arguments(path: String): Set<String> {
        val node = node(path) ?: return emptySet()
        return node["a"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + node["p"]?.jsonObject?.keys.orEmpty()
    }

    private fun arguments(path: String, command: String) = arguments("$path/$command")

    private fun commands(path: String) =
        node(path)?.get("c")?.jsonObject.orEmpty().filterValues { it.jsonObject["t"]?.jsonPrimitive?.content == "cmd" }.keys

    @Test
    fun bundledPacksMatchTheCommandsOfRouterOs() {
        val packs = bundledPacks()
        assertTrue(packs.isNotEmpty())
        val problems = packs.flatMap { (file, pack) ->
            val checked = pack.platform == "routeros" && !older(pack.until) && pack.window.tabs.none { it.path.substringBefore('/') in extra }
            (if (checked) problems(pack) else formProblems(pack)).map { "$file: $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyPlatformHasItsMenuAndEveryPackItsPlaceInIt() {
        for (platform in Platform.entries) {
            val menu = bundledMenu(platform)
            fun walk(entries: List<MenuEntry>, at: List<String>): List<List<String>> = entries.flatMap { listOf(at + it.title) + walk(it.items, at + it.title) }
            val places = walk(menu.items, emptyList())
            val homeless = bundledPacks().filterValues { it.platform == platform.packName && !it.native && it.menu !in places }.keys
            assertTrue(homeless.isEmpty(), "packs of $platform with no place in its menu: $homeless")
        }
    }

    @Test
    fun menuPathsExistAndEveryPackHasItsPlaceInTheMenu() {
        val menu = bundledMenu()
        fun walk(entries: List<MenuEntry>, at: List<String>): List<Pair<List<String>, MenuEntry>> =
            entries.flatMap { listOf(at + it.title to it) + walk(it.items, at + it.title) }
        val entries = walk(menu.items, emptyList())
        val problems = entries.mapNotNull { (titles, entry) ->
            val path = entry.path
            when {
                path == null && entry.items.isEmpty() && entry.window == null -> "${titles.joinToString(" > ")} opens nothing"
                listOfNotNull(entry.since, entry.until).any { RouterOsVersion.parse(it) == null } -> "${titles.joinToString(" > ")}: not a RouterOS version"
                path != null && entry.requires == null && !older(entry.until) && node(path) == null -> "${titles.joinToString(" > ")}: no menu $path"
                else -> null
            }
        } + bundledPacks().filterValues { it.platform == "routeros" }.mapNotNull { (file, pack) ->
            "$file: no menu entry ${pack.menu.joinToString(" > ")}".takeIf { entries.none { (titles, _) -> titles == pack.menu } }
        } + entries.mapNotNull { (titles, entry) ->
            val owners = bundledPacks().values.filter { it.platform == "routeros" && it.menu == titles }
            "${titles.joinToString(" > ")}: ${entry.path} is no tab of its pack".takeIf {
                entry.path != null && owners.isNotEmpty() && owners.none { pack -> pack.window.tabs.any { it.path == entry.path } }
            }
        } + menu.hidden.filter { node(it) == null }.map { "hidden $it: no such top-level menu" }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theTopLevelOfTheRouterOsMenuStandsInTheOrderOfItsPriorities() {
        val priorities = bundledMenu().items.map { it.prio ?: DEFAULT_MENU_PRIO }
        assertEquals(priorities.sorted(), priorities)
    }

    @Test
    fun aTypoInAPackIsReported() {
        val pack = parsePack(
            """
            {
                "menu": ["IP", "Firewall"],
                "window": { "title": "Firewall", "tabs": [{ "title": "Filter Rules", "path": "ip/firewall/filtr" }] },
                "paths": {
                    "ip/firewall/filter": {
                        "columns": ["dst-adress", "bytes"], "readonly": ["bytes"], "form": "rule",
                        "monitor": { "command": "interface/monitor-trafic", "argument": "interface" }
                    }
                },
                "forms": {
                    "rule": {
                        "tabs": [{ "title": "General", "fields": ["chain", "src-adress", "protocol"] }],
                        "only": [{ "fields": ["dst-prot"], "where": { "protocol": ["tcp"] } }]
                    },
                    "spare": { "tabs": [] }
                }
            }
            """,
        )
        assertEquals(
            listOf(
                "tab Filter Rules: no menu ip/firewall/filtr",
                "ip/firewall/filter: column dst-adress is not an argument of add or set, not joined and not read-only",
                "ip/firewall/filter: no command interface/monitor-trafic",
                "form rule, tab General: no src-adress",
                "form rule: only names dst-prot, which the form does not show",
                "form spare is not opened by any path",
            ),
            problems(pack),
        )
    }

    private fun monitor(monitor: PackMonitor): String? {
        val parent = monitor.command.substringBeforeLast('/')
        val command = monitor.command.substringAfterLast('/')
        return when {
            command !in commands(parent) -> "no command ${monitor.command}"
            monitor.argument?.let { it !in arguments(parent, command) } == true -> "${monitor.command} has no argument ${monitor.argument}"
            else -> null
        }
    }

    private fun formProblems(pack: Pack): List<String> = buildList {
        listOfNotNull(pack.since, pack.until).filter { RouterOsVersion.parse(it) == null }.forEach { add("$it is not a RouterOS version") }
        pack.paths.forEach { (path, shown) -> shown.form?.takeIf { it !in pack.forms }?.let { add("$path: no form $it") } }
        pack.forms.keys.filter { name -> pack.paths.values.none { it.form == name } }.forEach { add("form $it is not opened by any path") }
        pack.paths.filter { (_, shown) -> shown.form != null && shown.columns.isEmpty() && shown.menu.isNotEmpty() }.forEach { (path, _) -> add("$path: its form shows links, not the items of menu") }
        addAll(lineProblems(pack))
    }

    private fun lineProblems(pack: Pack) = pack.forms.flatMap { (name, form) ->
        form.tabs.filter { tab -> tab.fields.firstOrNull() == GROUP_LINE || tab.fields.lastOrNull() == GROUP_LINE || tab.fields.zipWithNext().any { it == GROUP_LINE to GROUP_LINE } }
            .map { "form $name, tab ${it.title}: a line with no field on one side" }
    }

    private fun problems(pack: Pack): List<String> = buildList {
        val versions = listOfNotNull(pack.since, pack.until) + pack.window.tabs.flatMap { listOfNotNull(it.since, it.until) } +
            pack.forms.values.flatMap { form -> form.tabs.flatMap { listOfNotNull(it.since, it.until) } }
        versions.filter { RouterOsVersion.parse(it) == null }.forEach { add("$it is not a RouterOS version") }
        pack.window.tabs.filter { node(it.path) == null }.forEach { add("tab ${it.title}: no menu ${it.path}") }
        val fieldsOfForms = mutableMapOf<String, MutableSet<String>>()
        for ((path, settings) in pack.paths) {
            if (node(path) == null) {
                add("no menu $path")
                continue
            }
            val joined = settings.join?.let { join ->
                if (node(join) == null) add("$path: no menu $join to join")
                arguments(join, "add") + arguments(join, "set")
            }.orEmpty()
            val own = if (node(path)?.get("t")?.jsonPrimitive?.content == "cmd") arguments(path) else arguments(path, "add") + arguments(path, "set") - "numbers"
            val printed = own + settings.readonly + joined
            settings.pairs.forEach { (pair, halves) ->
                if (pair !in printed) add("$path: pair $pair is not a property of the path")
                if (halves.size != 2) add("$path: pair $pair has ${halves.size} halves")
            }
            val fields = printed + settings.pairs.values.flatten()
            settings.columns.filter { it !in fields }.forEach { add("$path: column $it is not an argument of add or set, not joined and not read-only") }
            settings.monitor?.let { monitor(it)?.let { problem -> add("$path: $problem") } }
            if (settings.monitor != null && settings.monitor.argument == null) add("$path: the monitor of a list names no argument for its rows")
            settings.open?.paths?.values?.filter { node(it) == null }?.forEach { add("$path: no menu $it to open") }
            settings.links.filter { node(it.path) == null }.forEach { add("$path: no menu ${it.path} to link") }
            settings.links.filter { link -> link.argument?.let { it !in arguments(link.path) } == true }.forEach { add("$path: ${it.path} takes no ${it.argument}") }
            settings.actions.filter { it.command !in commands(path) }.forEach { add("$path: no command ${it.command}") }
            settings.quick?.takeIf { it !in fields }?.let { add("$path: quick filter by $it, which is not a property of the path") }
            settings.category?.takeIf { it !in fields }?.let { add("$path: categories by $it, which is not a property of the path") }
            settings.iconBy?.takeIf { it !in fields }?.let { add("$path: icons by $it, which is not a property of the path") }
            settings.where.keys.filter { it !in fields }.forEach { add("$path: where by $it, which is not a property of the path") }
            (settings.summary + settings.totals + settings.bits + settings.limits + settings.millis + listOfNotNull(settings.history)).filter { it !in fields }
                .forEach { add("$path: $it is not a property of the path") }
            settings.columnLabels.keys.filter { it !in fields && !(it == "#" && settings.numbered) }.forEach { add("$path: a title for the column $it, which the path does not show") }
            settings.preselect.forEach { (argument, menu) ->
                if (argument !in own) add("$path: preselect of $argument, which is not an argument")
                if (node(menu) == null) add("$path: preselect of $argument from $menu, which is no menu")
            }
            settings.defaults.keys.filter { it !in own }.forEach { add("$path: a default of $it, which is not an argument of add or set") }
            settings.autoset.filter { it !in fields }.forEach { add("$path: autoset of $it, which is not a property of the path") }
            settings.blank.filter { it !in fields }.forEach { add("$path: blank $it, which is not a property of the path") }
            settings.derived.values.filter { it.of !in fields }.forEach { add("$path: a value out of ${it.of}, which is not a property of the path") }
            val form = settings.form ?: continue
            if (form in pack.forms) fieldsOfForms.getOrPut(form) { mutableSetOf() } += fields else add("$path: no form $form")
        }
        for ((name, form) in pack.forms) {
            val fields = fieldsOfForms[name]
            if (fields == null) {
                add("form $name is not opened by any path")
                continue
            }
            for (tab in form.tabs) {
                tab.monitor?.let { monitor(it)?.let { problem -> add("form $name, tab ${tab.title}: $problem") } }
                tab.graph?.takeIf { it !in tab.fields }?.let { add("form $name, tab ${tab.title}: graph of $it, which the tab does not show") }
                if (tab.monitor != null) continue
                val own = tab.path?.let { path ->
                    if (node(path) == null) add("form $name, tab ${tab.title}: no menu $path")
                    arguments(path, "add") + arguments(path, "set")
                } ?: fields
                tab.properties.filter { it !in own }.forEach { add("form $name, tab ${tab.title}: no ${it}") }
            }
            val shown = form.tabs.flatMap { it.properties }.toSet()
            form.only.flatMap { it.fields + it.where.keys }.filter { it !in shown }.forEach { add("form $name: only names $it, which the form does not show") }
            form.confirm.filter { it !in shown }.forEach { add("form $name: confirm names $it, which the form does not show") }
            form.renamed.forEach { (old, new) ->
                if (old !in fields && old !in ofRouterOs6) add("form $name: $old, renamed to $new, is no property of its paths")
                if (new !in shown) add("form $name: $old is renamed to $new, which the form does not show")
            }
        }
        val properties = fieldsOfForms.values.flatten().toSet() + pack.paths.values.flatMap { it.columns + it.readonly + it.pairs.values.flatten() } +
            pack.forms.values.flatMap { form -> form.tabs.flatMap { it.properties } + form.renamed.keys }
        pack.labels.keys.filter { it !in properties }.forEach { add("label for $it, which the pack does not show") }
        addAll(lineProblems(pack))
    }
}
