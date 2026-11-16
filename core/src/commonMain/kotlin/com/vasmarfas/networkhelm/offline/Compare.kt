package com.vasmarfas.networkhelm.offline

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.openwrt.parseUci
import com.vasmarfas.networkhelm.vyos.vyosConfig

class MenuDifference(val path: MenuPath, val removed: List<Item>, val added: List<Item>, val changed: List<Pair<Item, Item>>)

fun configOf(platform: Platform, text: String): Config = when (platform) {
    Platform.RouterOs -> parseExport(text)
    Platform.OpenWrt -> uciConfig(parseUci(text))
    Platform.Vyos -> vyosConfig(text)
    Platform.Keenetic -> keeneticConfig(text)
    Platform.Ssh, Platform.Opnsense, Platform.Snmp, Platform.Upnp, Platform.Pfsense -> Config(emptyMap())
}

fun compare(before: Config, after: Config): List<MenuDifference> =
    (after.menus.keys + before.menus.keys).distinct().mapNotNull { path ->
        val old = before.menus[path]
        val new = after.menus[path]
        val settings = (old ?: new)?.kind == NodeKind.Settings
        difference(path, old?.items.orEmpty(), new?.items.orEmpty(), settings)
    }

private fun difference(path: MenuPath, old: List<Item>, new: List<Item>, settings: Boolean): MenuDifference? {
    val removed = mutableListOf<Item>()
    val added = mutableListOf<Item>()
    val changed = mutableListOf<Pair<Item, Item>>()
    fun pair(a: Item, b: Item) {
        if (a.values != b.values) changed += a to b
    }
    if (settings) {
        pair(old.firstOrNull() ?: Item("", emptyMap()), new.firstOrNull() ?: Item("", emptyMap()))
    } else {
        val oldKeys = old.mapNotNull(::key).groupingBy { it }.eachCount()
        val newKeys = new.mapNotNull(::key).groupingBy { it }.eachCount()
        val byKey = new.filter { key(it)?.let { name -> newKeys[name] == 1 && oldKeys[name] == 1 } == true }.associateBy { key(it) }
        val restOld = mutableListOf<Item>()
        for (item in old) {
            val name = key(item)
            when {
                name == null || oldKeys[name] != 1 -> restOld += item
                newKeys[name] == 1 -> pair(item, byKey.getValue(name))
                newKeys[name] == null -> removed += item
                else -> restOld += item
            }
        }
        val restNew = mutableListOf<Item>()
        for (item in new) {
            val name = key(item)
            when {
                name == null || newKeys[name] != 1 -> restNew += item
                oldKeys[name] == null -> added += item
                oldKeys[name] != 1 -> restNew += item
            }
        }
        if (restOld.size.toLong() * restNew.size > LCS_LIMIT) unordered(restOld, restNew, removed, added) else ordered(restOld, restNew, removed, added, changed)
    }
    return if (removed.isEmpty() && added.isEmpty() && changed.isEmpty()) null else MenuDifference(path, removed, added, changed)
}

private fun key(item: Item): String? = item[".name"] ?: item["name"] ?: item["default-name"]?.let { "default-name=$it" }

private fun ordered(old: List<Item>, new: List<Item>, removed: MutableList<Item>, added: MutableList<Item>, changed: MutableList<Pair<Item, Item>>) {
    val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lengths[i][j] = if (old[i].values == new[j].values) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }
    var i = 0
    var j = 0
    val gapOld = mutableListOf<Item>()
    val gapNew = mutableListOf<Item>()
    fun close() {
        val paired = minOf(gapOld.size, gapNew.size)
        for (k in 0 until paired) changed += gapOld[k] to gapNew[k]
        removed += gapOld.drop(paired)
        added += gapNew.drop(paired)
        gapOld.clear()
        gapNew.clear()
    }
    while (i < old.size || j < new.size) {
        when {
            i < old.size && j < new.size && old[i].values == new[j].values -> {
                close()
                i++
                j++
            }
            j >= new.size || i < old.size && lengths[i + 1][j] >= lengths[i][j + 1] -> gapOld += old[i++]
            else -> gapNew += new[j++]
        }
    }
    close()
}

private fun unordered(old: List<Item>, new: List<Item>, removed: MutableList<Item>, added: MutableList<Item>) {
    val left = new.groupBy { it.values }.mapValues { it.value.toMutableList() }
    for (item in old) {
        val same = left[item.values]
        if (same.isNullOrEmpty()) removed += item else same.removeAt(0)
    }
    added += left.values.flatten()
}

private const val LCS_LIMIT = 4_000_000L
