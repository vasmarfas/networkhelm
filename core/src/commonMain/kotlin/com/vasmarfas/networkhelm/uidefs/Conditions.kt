package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.session.RouterFacts

private fun JgValue?.text() = (this as? JgString)?.text

private fun JgValue?.number() = (this as? JgNumber)?.value?.toInt()

// the answer of a condition that asks about the router; null where it asks about the fields of a form
internal fun holds(condition: JgValue, facts: RouterFacts): Boolean? {
    val fields = (condition as? JgObject)?.fields ?: return null
    return when (fields["type"].text()) {
        "syscap" -> fields["value"].number()?.let { bit -> facts.capabilities.getOrNull(bit / 32)?.let { word -> ((word shr (bit % 32)) and 1L) == 1L } ?: false }
        "board" -> fields["value"].text()?.let { name -> if ((fields["re"].number() ?: 0) != 0) matches(name, facts.board) else name == facts.family || name == facts.board }
        "arch" -> fields["value"].text()?.let { it == facts.arch }
        "quickset" -> fields["bit"].number()?.let { bit -> facts.quickSet?.let { word -> ((word shr bit) and 1L) == 1L } ?: false }
        "addon" -> fields["value"].text()?.let { it in facts.addons }
        "supportmsgid", "winbox" -> true
        "not" -> fields["pred"]?.let { holds(it, facts) }?.not()
        "or" -> (fields["pred"] as? JgList)?.items?.map { holds(it, facts) }?.let { answers -> if (true in answers) true else if (null in answers) null else false }
        else -> null
    }
}

// a pattern of the router that is no regular expression: IllegalArgumentException on the JVM, a SyntaxError of the browser in JavaScript
private fun matches(pattern: String, name: String?): Boolean? = try {
    name?.let { Regex(pattern).containsMatchIn(it) } ?: false
} catch (e: Throwable) {
    null
}

internal fun UiNode.shown(facts: RouterFacts?): Boolean = attrs["pred"]?.let { condition -> facts != null && holds(condition, facts) == true } ?: true

// a window the router's conditions let through stands as the description has it, without the condition
internal fun UiNode.settled(facts: RouterFacts?): UiNode? = when {
    attrs["pred"] == null -> this
    shown(facts) -> UiNode(kind, attrs - "pred", children)
    else -> null
}
