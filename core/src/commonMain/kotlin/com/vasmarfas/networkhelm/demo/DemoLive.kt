package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

internal fun wander(base: Long, tick: Int, salt: Int): Long = base + base * ((tick * 37 + salt * 11).mod(41) - 20) / 100

internal fun clock(): Map<String, String> {
    val seconds = Clock.System.now().toEpochMilliseconds().floorDiv(1000) + 3 * 3_600
    val z = seconds.floorDiv(86_400) + 719_468
    val era = z.floorDiv(146_097)
    val day = z - era * 146_097
    val year = (day - day / 1_460 + day / 36_524 - day / 146_096) / 365
    val inYear = day - (365 * year + year / 4 - year / 100)
    val shifted = (5 * inYear + 2) / 153
    val month = if (shifted < 10) shifted + 3 else shifted - 9
    val second = seconds.mod(86_400L)
    return mapOf(
        "date" to "${year + era * 400 + if (month <= 2) 1 else 0}-${two(month)}-${two(inYear - (153 * shifted + 2) / 5 + 1)}",
        "time" to "${two(second / 3_600)}:${two(second % 3_600 / 60)}:${two(second % 60)}",
    )
}

private fun two(value: Long) = value.toString().padStart(2, '0')

internal fun interfaceRates(name: String, uplink: Boolean, running: Boolean, tick: Int): Map<String, String> {
    val salt = name.hashCode()
    val down = if (!running) 0L else if (uplink) wander(21_400_000, tick, salt) else wander(180_000L + salt.mod(7) * 310_000L, tick, salt)
    val up = if (!running) 0L else if (uplink) wander(3_100_000, tick, salt + 1) else wander(90_000L + salt.mod(5) * 120_000L, tick, salt + 1)
    return mapOf(
        "rx-bits-per-second" to "$down",
        "tx-bits-per-second" to "$up",
        "rx-packets-per-second" to "${down / 9_600}",
        "tx-packets-per-second" to "${up / 9_600}",
        "fp-rx-bits-per-second" to "$down",
        "fp-tx-bits-per-second" to "$up",
        "fp-rx-packets-per-second" to "${down / 9_600}",
        "fp-tx-packets-per-second" to "${up / 9_600}",
    )
}

internal fun counting(bytes: String = "bytes", packets: String = "packets"): Live = { row, tick ->
    val salt = row.id.hashCode().mod(900) + 100
    val speed = salt.mod(7) + 1L
    mapOf(bytes to "${salt * 1_000L + tick * 1_480L * speed}", packets to "${salt * 1_000L / 700 + tick * speed}")
}

internal fun refusal(reason: String): Flow<Item> = flow { throw DeviceException(ErrorCategory.General, "this is a demo router: $reason") }

internal fun monitorTraffic(arguments: Map<String, String>, uplink: String = "ether1"): Flow<Item> = flow {
    val names = arguments["interface"].orEmpty().split(',').filter { it.isNotEmpty() }
    var tick = 0
    while (true) {
        for (name in names) emit(Item("", mapOf("name" to name) + interfaceRates(name, name == uplink, true, tick)))
        tick++
        delay(1000)
    }
}

internal fun ping(arguments: Map<String, String>): Flow<Item> = flow {
    val host = arguments["address"] ?: arguments["host"].orEmpty()
    val count = arguments["count"]?.toIntOrNull()
    val interval = arguments["interval"]?.let(::milliseconds) ?: 1000L
    var sent = 0
    var total = 0L
    var min = Long.MAX_VALUE
    var max = 0L
    while (count == null || sent < count) {
        val micros = wander(11_400, sent, host.hashCode())
        total += micros
        min = minOf(min, micros)
        max = maxOf(max, micros)
        sent++
        emit(
            Item(
                "",
                mapOf(
                    "seq" to "${sent - 1}", "host" to host, "size" to (arguments["size"] ?: "56"), "ttl" to "58", "time" to rtt(micros),
                    "sent" to "$sent", "received" to "$sent", "packet-loss" to "0",
                    "min-rtt" to rtt(min), "avg-rtt" to rtt(total / sent), "max-rtt" to rtt(max),
                ),
            ),
        )
        delay(interval)
    }
}

internal fun traceroute(arguments: Map<String, String>): Flow<Item> = flow {
    val hops = listOf("203.0.113.1", "198.51.100.1", "198.51.100.17", "192.0.2.9", "192.0.2.42", arguments["address"].orEmpty())
    val count = arguments["count"]?.toIntOrNull()
    val best = LongArray(hops.size) { Long.MAX_VALUE }
    val worst = LongArray(hops.size)
    val total = LongArray(hops.size)
    var round = 0
    while (count == null || round < count) {
        round++
        hops.forEachIndexed { index, address ->
            val last = wander(1_900L * (index + 1), round, index)
            best[index] = minOf(best[index], last)
            worst[index] = maxOf(worst[index], last)
            total[index] += last
            emit(
                Item(
                    "",
                    mapOf(
                        ".section" to "$round", "hop" to "${index + 1}", "address" to address, "loss" to "0", "sent" to "$round",
                        "last" to millis(last), "avg" to millis(total[index] / round), "best" to millis(best[index]), "worst" to millis(worst[index]),
                        "std-dev" to "0.4ms", "status" to "",
                    ),
                ),
            )
        }
        delay(1000)
    }
}

private fun rtt(micros: Long) = "${micros / 1000}ms${micros % 1000}us"

private fun millis(micros: Long) = "${micros / 1000}.${(micros % 1000) / 100}ms"

private fun milliseconds(text: String): Long? = when {
    text.endsWith("ms") -> text.dropLast(2).toLongOrNull()
    text.endsWith("s") -> text.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
    else -> text.toLongOrNull()
}
