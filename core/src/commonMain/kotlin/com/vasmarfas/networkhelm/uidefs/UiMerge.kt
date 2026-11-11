package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow

fun routerFirst(bundled: Collection<Pack>, made: List<Pack>): List<Pack> {
    val attached = LinkedHashMap<Pack, MutableList<Pack>>()
    val own = mutableListOf<Pack>()
    for (pack in made) {
        val first = pack.window.tabs.firstOrNull()?.path
        val target = bundled.firstOrNull { it.menu == pack.menu } ?: bundled.firstOrNull { first != null && first in it.paths }
        if (target == null) own += pack else attached.getOrPut(target) { mutableListOf() } += pack
    }
    return attached.map { (target, packs) -> described(target, packs) } + own
}

private fun described(bundled: Pack, made: List<Pack>): Pack {
    val paths = LinkedHashMap(bundled.paths)
    val forms = LinkedHashMap(bundled.forms)
    for (source in made) {
        for ((path, said) in source.paths) {
            val have = paths[path]
            val wanted = said.form?.let(source.forms::get)
            val key = have?.form ?: said.form
            if (key != null && wanted != null) forms[key] = forms[key]?.let { mergedForm(it, wanted) } ?: wanted
            paths[path] = if (have == null) said else mergedPath(have, said, key)
        }
    }
    val primary = made.firstOrNull { it.menu == bundled.menu }
    val tabs = if (primary == null) bundled.window.tabs else mergedTabs(bundled.window.tabs, primary.window.tabs)
    return Pack(bundled.schema, bundled.platform, bundled.menu, PackWindow(bundled.window.title, tabs), paths, forms, bundled.labels, bundled.since, bundled.until, bundled.native, bundled.prio)
}

private fun mergedTabs(have: List<PackTab>, said: List<PackTab>): List<PackTab> {
    val tabs = said.map { tab -> have.firstOrNull { it.path == tab.path }?.let { own -> PackTab(tab.title.ifEmpty { own.title }, tab.path, own.since, own.until) } ?: tab }.toMutableList()
    for (own in have.filter { own -> said.none { it.path == own.path } }) {
        val before = have.takeWhile { it !== own }.lastOrNull { earlier -> tabs.any { it.path == earlier.path } }
        tabs.add(if (before == null) 0 else tabs.indexOfFirst { it.path == before.path } + 1, own)
    }
    return tabs.map { tab ->
        val own = have.firstOrNull { it.path == tab.path }
        if (own != null && own.title != tab.title && tabs.count { squeezed(it.title) == squeezed(tab.title) } > 1) PackTab(own.title, tab.path, tab.since, tab.until) else tab
    }
}

private fun mergedPath(have: PackPath, said: PackPath, form: String?) = have.copy(
    columns = mergedColumns(have.columns, said.columns),
    readonly = (have.readonly + said.readonly).distinct(),
    form = form,
    actions = have.actions + said.actions.filter { action -> have.actions.none { it.command == action.command } },
    // a link of the router with the title of a bundled one is that button, and the bundled one knows where it goes
    links = have.links + said.links.filter { link -> have.links.none { it.path == link.path || it.title == link.title } },
    menu = have.menu + said.menu.filter { item -> have.menu.none { it.title == item.title && it.path == item.path } },
    labels = have.labels + said.labels,
    flags = said.flags.ifEmpty { have.flags },
    native = said.native ?: have.native,
    mirror = said.mirror ?: have.mirror,
    refresh = said.refresh ?: have.refresh,
    autoset = (have.autoset + said.autoset).distinct(),
    radios = (have.radios + said.radios).distinct(),
)

private fun mergedColumns(have: List<String>, said: List<String>): List<String> {
    if (said.isEmpty()) return have
    val columns = said.toMutableList()
    for (column in have.filter { it !in said }) {
        val before = have.takeWhile { it != column }.lastOrNull { it in columns }
        columns.add(if (before == null) 0 else columns.indexOf(before) + 1, column)
    }
    return columns
}

private fun mergedForm(have: PackForm, said: PackForm): PackForm {
    val named = said.tabs.flatMap { it.properties }.toSet()
    val used = mutableSetOf<PackFormTab>()
    val first = said.tabs.firstOrNull()?.takeIf { tab -> have.tabs.none { squeezed(it.title) == squeezed(tab.title) } }
    val firstOwn = have.tabs.firstOrNull()?.takeIf { own -> first != null && own.path == null && said.tabs.none { squeezed(it.title) == squeezed(own.title) } }
    val tabs = said.tabs.map { tab ->
        val own = have.tabs.firstOrNull { squeezed(it.title) == squeezed(tab.title) } ?: firstOwn.takeIf { tab === first }
        if (own == null || own.path != null) return@map own?.also { used += it } ?: tab
        used += own
        val fields = tab.fields.toMutableList()
        for (field in own.fields.filter { it != GROUP_LINE && it !in named }) {
            val before = own.fields.takeWhile { it != field }.lastOrNull { it != GROUP_LINE && it in fields }
            fields.add(if (before == null) fields.size else fields.indexOf(before) + 1, field)
        }
        own.withTitle(tab.title, fields)
    }
    val left = have.tabs.filter { it !in used }.mapNotNull { tab ->
        val kept = tab.fields.filter { it == GROUP_LINE || it !in named }
        when {
            tab.path != null || tab.monitor != null || tab.chart || tab.graph != null -> tab
            kept.any { it != GROUP_LINE } -> tab.withTitle(tab.title, kept)
            else -> null
        }
    }
    return PackForm(have.title, (tabs + left).map { tab -> tab.fields.tidied().let { if (it == tab.fields) tab else tab.withTitle(tab.title, it) } }, have.only, have.confirm, have.renamed)
}

private fun List<String>.tidied(): List<String> =
    fold(mutableListOf<String>()) { out, field -> out.also { if (field != GROUP_LINE || out.isNotEmpty() && out.last() != GROUP_LINE) it += field } }.dropLastWhile { it == GROUP_LINE }

private fun PackFormTab.withTitle(title: String, fields: List<String>) = PackFormTab(title, fields, readonly, since, until, path, monitor, chart, graph)
