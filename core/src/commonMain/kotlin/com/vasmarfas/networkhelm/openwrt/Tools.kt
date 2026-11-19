package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

internal class Tools(private val ubus: Ubus, private val objects: Set<String>) {
    private val shell = ubus as? SshUbus
    private val commands = mapOf(
        "tool/ping" to text("address", "count", "size", "ttl", "interface"),
        "tool/traceroute" to text("address", "max-hops"),
        "tool/nslookup" to text("name", "server"),
        "tool/traffic" to text("interface"),
        "tool/password" to text("user") + listOf(Argument("new-password", ValueType.Secret), Argument("confirm-new-password", ValueType.Secret)),
        "tool/reboot" to emptyList(),
    )

    fun schema(path: MenuPath): MenuSchema? = commands[path.toString()]?.let { MenuSchema(path, NodeKind.Command, emptyList(), emptyList(), it) }

    fun has(path: MenuPath, command: String) = path.child(command).toString() in commands

    fun run(command: String, arguments: Map<String, String>): Flow<Item> = when (command) {
        "ping" -> ping(arguments)
        "traceroute" -> traceroute(arguments)
        "nslookup" -> nslookup(arguments)
        "traffic" -> traffic(arguments)
        "password" -> flow<Item> { password(arguments) }
        "reboot" -> flow<Item> { ubus.call("system", "reboot") }
        else -> error("$command is not a tool of OpenWrt")
    }

    private fun traffic(arguments: Map<String, String>): Flow<Item> = flow {
        val names = arguments["interface"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        var last = counters()
        var round = 0
        while (true) {
            delay(1.seconds)
            val now = counters()
            for ((name, bytes) in now) {
                val before = last[name] ?: continue
                if (if (names != null) name !in names else name == "lo") continue
                val rates = mapOf("rx-bits-per-second" to (bytes.first - before.first) * 8, "tx-bits-per-second" to (bytes.second - before.second) * 8)
                emit(Item(name, mapOf("name" to name) + rates.mapValues { "${it.value.coerceAtLeast(0)}" } + (".section" to "$round")))
            }
            last = now
            round++
        }
    }

    private suspend fun counters(): Map<String, Pair<Long, Long>> = ubus.call("network.device", "status").mapNotNull { (name, device) ->
        val statistics = (device as? JsonObject)?.get("statistics") as? JsonObject ?: return@mapNotNull null
        val rx = statistics["rx_bytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val tx = statistics["tx_bytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        name to (rx to tx)
    }.toMap()

    private fun ping(arguments: Map<String, String>): Flow<Item> = flow {
        val address = required(arguments, "address")
        val count = arguments["count"] ?: "4".takeIf { shell == null }
        val params = listOfNotNull(count?.let { "-c$it" }, arguments["size"]?.let { "-s$it" }, arguments["ttl"]?.let { "-t$it" }, arguments["interface"]?.let { "-I$it" }, address)
        var sent = 0
        val times = mutableListOf<Double>()
        suspend fun row(values: Map<String, String>) {
            val totals = mapOf("sent" to "$sent", "received" to "${times.size}", "packet-loss" to "${if (sent == 0) 0 else (sent - times.size) * 100 / sent}%")
            val rtt = if (times.isEmpty()) emptyMap() else mapOf("min-rtt" to ms(times.min()), "avg-rtt" to ms(times.average()), "max-rtt" to ms(times.max()))
            emit(Item("", values + totals + rtt))
        }
        suspend fun lostUntil(seq: Int) {
            while (sent < seq) {
                sent++
                row(mapOf("seq" to "${sent - 1}", "host" to address, "status" to "timeout"))
            }
        }
        lines("/bin/ping", params).collect { line ->
            val reply = pingReply.matchEntire(line)
            val totals = pingTotals.find(line)
            when {
                reply != null -> {
                    val (size, host, seq, ttl, time) = reply.destructured
                    lostUntil(seq.toInt())
                    sent = seq.toInt() + 1
                    times += time.toDouble()
                    row(mapOf("seq" to seq, "host" to host, "size" to size, "ttl" to ttl, "time" to "${time}ms"))
                }
                totals != null -> lostUntil(totals.groupValues[1].toInt())
                line.startsWith("ping: ") -> emit(Item("", mapOf("status" to line.removePrefix("ping: "))))
            }
        }
    }

    private fun traceroute(arguments: Map<String, String>): Flow<Item> = flow {
        val address = required(arguments, "address")
        lines("/bin/traceroute", listOfNotNull("-n", "-w1", arguments["max-hops"]?.let { "-m$it" }, address)).collect { line ->
            val words = line.trim().split(blanks)
            val hop = words.first().toIntOrNull()
            if (hop == null) {
                if (line.startsWith("traceroute: ")) emit(Item("", mapOf("status" to line.removePrefix("traceroute: "))))
                return@collect
            }
            val times = words.zipWithNext().filter { it.second == "ms" }.mapNotNull { it.first.toDoubleOrNull() }
            val probes = times.size + words.count { it == "*" }
            val values = mutableMapOf("hop" to "$hop", "sent" to "$probes")
            words.drop(1).firstOrNull { it != "*" && it != "ms" && !it.startsWith("!") && it.toDoubleOrNull() == null }?.let { values["address"] = it }
            if (probes > 0) values["loss"] = "${(probes - times.size) * 100 / probes}%"
            if (times.isNotEmpty()) {
                values["last"] = ms(times.last())
                values["avg"] = ms(times.average())
                values["best"] = ms(times.min())
                values["worst"] = ms(times.max())
            }
            words.firstOrNull { it.startsWith("!") }?.let { values["status"] = it }
            emit(Item("", values))
        }
    }

    private fun nslookup(arguments: Map<String, String>): Flow<Item> = flow {
        val name = required(arguments, "name")
        var answered: String? = null
        val said = mutableSetOf<String>()
        lines("/usr/bin/nslookup", listOfNotNull(name, arguments["server"])).collect { line ->
            when {
                line.startsWith("Name:") -> answered = line.substringAfter(':').trim()
                line.startsWith("Address") -> answered?.let { emit(Item("", mapOf("name" to it, "address" to line.substringAfter(':').trim()))) }
                line.startsWith("** ") || line.startsWith("nslookup: ") -> {
                    val status = line.substringAfter(' ')
                    if (said.add(status)) emit(Item("", mapOf("name" to name, "status" to status)))
                }
            }
        }
    }

    private fun lines(tool: String, params: List<String>): Flow<String> = shell?.output(untilClosed((listOf(tool) + params).joinToString(" ") { quote(it) } + " 2>&1")) ?: flow {
        val answer = ubus.call("file", "exec", JsonObject(mapOf("command" to JsonPrimitive(tool), "params" to JsonArray(params.map(::JsonPrimitive)))))
        for (stream in listOf("stdout", "stderr")) answer[stream]?.jsonPrimitive?.contentOrNull?.lines()?.forEach { emit(it) }
    }

    private suspend fun password(arguments: Map<String, String>) {
        val user = arguments["user"] ?: "root"
        val password = required(arguments, "new-password")
        if (password != arguments["confirm-new-password"]) throw DeviceException(ErrorCategory.ArgumentValue, "new passwords do not match")
        val shell = shell
        when {
            "rpc-sys" in objects -> ubus.call("rpc-sys", "password_set", JsonObject(mapOf("user" to JsonPrimitive(user), "password" to JsonPrimitive(password))))
            shell != null -> {
                val result = shell.sh("printf '%s\\n%s\\n' ${quote(password)} ${quote(password)} | passwd ${quote(user)}")
                if (result.exitStatus != 0) throw DeviceException(ErrorCategory.ArgumentValue, result.output.trim())
            }
            "luci" in objects -> {
                val result = ubus.call("luci", "setPassword", JsonObject(mapOf("username" to JsonPrimitive(user), "password" to JsonPrimitive(password))))
                if (result["result"]?.jsonPrimitive?.booleanOrNull == false) throw DeviceException(ErrorCategory.ArgumentValue, "passwd turned down the password of $user")
            }
            else -> throw DeviceException(ErrorCategory.Unsupported, "without rpc-sys or LuCI a password is set over SSH")
        }
    }

    private fun required(arguments: Map<String, String>, name: String) =
        arguments[name] ?: throw DeviceException(ErrorCategory.ArgumentValue, "missing value of argument $name")

    private fun ms(value: Double) = "${(value * 1000).roundToLong() / 1000.0}".removeSuffix(".0") + "ms"

    private fun text(vararg names: String) = names.map { Argument(it, ValueType.Text()) }

    private companion object {
        val blanks = Regex("\\s+")
        val pingReply = Regex("(\\d+) bytes from (.+): seq=(\\d+) ttl=(\\d+) time=([\\d.]+) ms")
        val pingTotals = Regex("^(\\d+) packets transmitted")
    }
}
