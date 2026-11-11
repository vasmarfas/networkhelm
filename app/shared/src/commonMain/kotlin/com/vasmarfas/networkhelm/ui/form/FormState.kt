package com.vasmarfas.networkhelm.ui.form

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class FormState(router: Map<String, String> = emptyMap()) {
    var router by mutableStateOf(router)
        private set
    private val edits = mutableStateMapOf<String, String?>()

    private val base = mutableMapOf<String, String?>()

    fun value(name: String): String? = if (name in edits) edits[name] else router[name]

    fun touched(name: String) = name in edits

    fun edit(name: String, value: String?) {
        if (name !in edits) base[name] = router[name]
        edits[name] = value
    }

    fun update(item: Map<String, String>) {
        router = item
    }

    fun changedOnRouter(name: String) = name in edits && base[name] != router[name]

    fun changes(): Map<String, String?> = edits.filter { (name, value) ->
        val now = router[name]
        value != now && !(now == null && value.isNullOrEmpty())
    }

    // what was typed while the request went stays an edit, now of the values just written
    fun applied(written: Map<String, String?>) {
        router = (router + written.filterValues { it != null }.mapValues { it.value.orEmpty() }) - written.filterValues { it == null }.keys
        discard(edits.keys - changes().keys)
        for (name in written.keys) if (name in edits) base[name] = router[name]
    }

    fun discard(names: Collection<String>) {
        for (name in names) {
            edits.remove(name)
            base.remove(name)
        }
    }

    // an edit that the router has taken over is no edit any more
    fun settle(names: Collection<String>) = discard(names.filter { it in edits && edits[it] == router[it] })
}

// a placeholder lists fallbacks: an IPsec peer has no name before RouterOS 6.43, so its title uses the address
fun formTitle(template: String, value: (String) -> String?): String {
    // ICU, which Android runs regular expressions on, takes no bare closing brace
    val placeholder = Regex("""\{([^}]+)\}""")
    val pieces = mutableListOf<Pair<Boolean, String>>()
    var at = 0
    for (match in placeholder.findAll(template)) {
        pieces += false to template.substring(at, match.range.first)
        pieces += true to match.groupValues[1].split('|').firstNotNullOfOrNull { name -> value(name)?.takeIf { it.isNotEmpty() } }.orEmpty()
        at = match.range.last + 1
    }
    pieces += false to template.substring(at)
    return pieces.mapIndexed { index, (field, text) ->
        val lonely = pieces.getOrNull(index - 1)?.second.isNullOrEmpty() || pieces.getOrNull(index + 1)?.second.isNullOrEmpty()
        if (!field && text == ":" && lonely) "" else text
    }.joinToString("")
}
