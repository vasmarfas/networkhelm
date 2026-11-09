package com.vasmarfas.networkhelm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Item(val id: String, val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]
}

fun duration(seconds: Long): String {
    var rest = seconds
    return listOf(604_800L to "w", 86_400L to "d", 3_600L to "h", 60L to "m", 1L to "s").mapNotNull { (size, unit) ->
        val count = rest / size
        rest %= size
        if (count > 0) "$count$unit" else null
    }.joinToString("").ifEmpty { "0s" }
}

@Serializable
sealed interface ListEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val items: List<Item>) : ListEvent

    @Serializable
    @SerialName("upsert")
    data class Upsert(val item: Item, val next: String? = null) : ListEvent

    @Serializable
    @SerialName("removed")
    data class Removed(val id: String) : ListEvent

    // a big table is on its way; the rows it has given since the last progress come with it, the whole table with the snapshot
    @Serializable
    @SerialName("progress")
    data class Progress(val read: Int, val total: Int, val added: List<Item> = emptyList()) : ListEvent
}
