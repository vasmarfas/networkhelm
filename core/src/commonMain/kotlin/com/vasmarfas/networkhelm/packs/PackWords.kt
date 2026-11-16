package com.vasmarfas.networkhelm.packs

import com.vasmarfas.networkhelm.routeros.schema.label

// a pack in the words of the router's own interface: the titles of the menu, the window, its tabs, forms and buttons, the
// labels of the fields, and a label for each field the pack leaves to its name
fun Pack.worded(words: Map<String, String>): Pack {
    if (words.isEmpty()) return this
    fun word(text: String) = words[text] ?: text
    // a title such as User <{name}> keeps what the window puts in it
    fun title(text: String) = text.substringBefore(" <").let { word(it) + text.substring(it.length) }
    val fields = paths.values.flatMap { it.columns + it.readonly } + forms.values.flatMap { form -> form.tabs.flatMap { it.properties } }
    val named = fields.distinct().filter { field -> field !in labels && paths.values.none { field in it.labels } }.mapNotNull { field -> words[label(field)]?.let { field to it } }
    return Pack(
        schema,
        platform,
        menu.map(::word),
        PackWindow(word(window.title), window.tabs.map { PackTab(word(it.title), it.path, it.since, it.until) }, window.selector),
        paths.mapValues { (_, path) ->
            path.copy(labels = path.labels.mapValues { word(it.value) }, columnLabels = path.columnLabels.mapValues { word(it.value) }, actions = path.actions.map { it.copy(title = word(it.title)) })
        },
        forms.mapValues { (_, form) ->
            PackForm(form.title?.let(::title), form.tabs.map { PackFormTab(word(it.title), it.fields, it.readonly, it.since, it.until, it.path, it.monitor, it.chart, it.graph, it.inline) }, form.only, form.confirm, form.renamed)
        },
        labels.mapValues { word(it.value) } + named,
        since,
        until,
        native,
        prio,
        hidden,
    )
}

fun Menu.worded(words: Map<String, String>): Menu = if (words.isEmpty()) this else Menu(schema, platform, items.map { it.worded(words) }, hidden)

private fun MenuEntry.worded(words: Map<String, String>): MenuEntry =
    MenuEntry(words[title] ?: title, path, items.map { it.worded(words) }, requires, window, since, until, page, arguments, prio, fill, start, item, icon ?: title)
