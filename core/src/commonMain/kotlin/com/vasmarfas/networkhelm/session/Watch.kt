package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

fun Flow<ListEvent>.rows(): Flow<List<Item>> = filterNot { it is ListEvent.Progress }.runningFold(emptyList(), ::folded)

// the rows of a table as they come: those a long read has given so far, then the whole table
fun Flow<ListEvent>.rowsSoFar(): Flow<List<Item>> = filterNot { it is ListEvent.Progress && it.added.isEmpty() }.runningFold(emptyList()) { rows, event ->
    if (event is ListEvent.Progress) (rows.associateBy { it.id } + event.added.associateBy { it.id }).values.toList() else folded(rows, event)
}

private fun folded(rows: List<Item>, event: ListEvent): List<Item> =
    when (event) {
        is ListEvent.Progress -> rows
        is ListEvent.Snapshot -> event.items
        is ListEvent.Removed -> rows.filterNot { it.id == event.id }
        is ListEvent.Upsert -> {
            val at = rows.indexOfFirst { it.id == event.item.id }
            val next = event.next
            when {
                next == null && at >= 0 -> rows.toMutableList().also { it[at] = event.item }
                next == null || next.isEmpty() -> rows.filterNot { it.id == event.item.id } + event.item
                else -> {
                    val others = rows.filterNot { it.id == event.item.id }
                    val before = others.indexOfFirst { it.id == next }
                    if (before < 0) others + event.item else others.toMutableList().also { it.add(before, event.item) }
                }
            }
        }
    }

fun <T> Flow<T>.atMostEvery(period: Duration): Flow<T> = conflate().transform {
    emit(it)
    delay(period)
}

private val turnsOfSeconds: Flow<Unit> = flow {
    while (true) {
        delay(1000 - Clock.System.now().toEpochMilliseconds() % 1000)
        emit(Unit)
    }
}.shareIn(CoroutineScope(Dispatchers.Default), SharingStarted.WhileSubscribed(5_000))

fun <T> Flow<T>.onTheSecond(turns: Flow<Unit> = turnsOfSeconds): Flow<T> = channelFlow {
    val values = buffer(Channel.CONFLATED).produceIn(this)
    var first = true
    for (value in values) {
        if (!first) turns.first()
        first = false
        val newer = values.tryReceive()
        send(if (newer.isSuccess) newer.getOrThrow() else value)
    }
}

fun Flow<Item>.rounds(key: (Item) -> String?): Flow<Map<String, Map<String, String>>> = flow {
    var round = HashMap<String, Map<String, String>>()
    var section: String? = null
    collect { item ->
        val now = item[".section"]
        if (now != section && round.isNotEmpty()) {
            emit(round.toMap())
            round = HashMap()
        }
        section = now
        key(item)?.let { round[it] = item.values - ".section" }
        if (now == null) {
            emit(round.toMap())
            round = HashMap()
        }
    }
    if (round.isNotEmpty()) emit(round.toMap())
}

// a read takes at most a tenth of the time, so a slow table is asked less often than the period; a change asks at once
fun polled(period: Duration, again: Flow<Unit> = emptyFlow(), read: suspend () -> List<Item>): Flow<ListEvent> = channelFlow {
    val asked = again.buffer(Channel.CONFLATED).produceIn(this)
    var waiting = true
    var items: List<Item>? = null
    while (true) {
        val started = TimeSource.Monotonic.markNow()
        val fresh = read()
        if (fresh != items) send(ListEvent.Snapshot(fresh))
        items = fresh
        val wait = maxOf(period, started.elapsedNow() * 9)
        if (!waiting || withTimeoutOrNull(wait) { asked.receiveCatching() }?.isClosed == true) {
            waiting = false
            delay(wait)
        }
    }
}

fun DeviceSession.watchSettings(path: MenuPath, properties: List<String>? = null): Flow<Item> =
    watch(path, properties = properties).mapNotNull { event ->
        when (event) {
            is ListEvent.Snapshot -> event.items.firstOrNull()
            is ListEvent.Upsert -> event.item
            is ListEvent.Removed, is ListEvent.Progress -> null
        }
    }
