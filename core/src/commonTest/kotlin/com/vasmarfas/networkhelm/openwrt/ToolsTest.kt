package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolsTest {

    private class Rpcd(private val printed: String) : Ubus {
        val calls = mutableListOf<JsonObject>()
        val methods = mutableListOf<String>()

        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
            calls += args
            methods += "$obj $method"
            return JsonObject(mapOf("code" to JsonPrimitive(0), "stdout" to JsonPrimitive(printed)))
        }

        override suspend fun objects() = setOf("file")

        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

        override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
    }

    private class Netifd : Ubus {
        private var reads = 0

        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
            val step = reads++ * 1000L
            fun device(rx: Long, tx: Long) = JsonObject(mapOf("statistics" to JsonObject(mapOf("rx_bytes" to JsonPrimitive(rx), "tx_bytes" to JsonPrimitive(tx)))))
            return JsonObject(mapOf("eth0" to device(step, 2 * step), "eth1" to device(3 * step, 0), "lo" to device(step, step)))
        }

        override suspend fun objects() = setOf("network.device")

        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

        override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
    }

    private val tool = MenuPath.parse("tool")

    @Test
    fun theDevicesOfASecondShareItsRound() = runTest {
        val rows = Tools(Netifd(), setOf("network.device")).run("traffic", emptyMap()).take(4).toList()
        assertEquals(listOf("eth0" to "0", "eth1" to "0", "eth0" to "1", "eth1" to "1"), rows.map { it["name"] to it[".section"] })
        assertEquals(listOf("8000", "16000"), listOf("rx-bits-per-second", "tx-bits-per-second").map { rows[0][it] })
    }

    @Test
    fun aRequestWithoutAnswerIsATimeoutAndTheTotalsGoWithEveryRow() = runTest {
        val rpcd = Rpcd(
            "PING 192.0.2.1 (192.0.2.1): 56 data bytes\n64 bytes from 192.0.2.1: seq=0 ttl=64 time=0.109 ms\n" +
                "64 bytes from 192.0.2.1: seq=2 ttl=64 time=0.091 ms\n\n--- 192.0.2.1 ping statistics ---\n" +
                "4 packets transmitted, 2 packets received, 50% packet loss\nround-trip min/avg/max = 0.091/0.100/0.109 ms\n",
        )
        val tools = Tools(rpcd, setOf("file"))
        assertTrue(tools.has(tool, "ping"))
        val rows = tools.run("ping", mapOf("address" to "192.0.2.1")).toList()
        assertEquals(listOf("", "timeout", "", "timeout"), rows.map { it["status"].orEmpty() })
        assertEquals(listOf("0", "1", "2", "3"), rows.map { it["seq"] })
        assertEquals(listOf("4", "2", "50%", "0.091ms", "0.1ms", "0.109ms"), listOf("sent", "received", "packet-loss", "min-rtt", "avg-rtt", "max-rtt").map { rows.last()[it] })
        assertEquals("[\"-c4\",\"192.0.2.1\"]", rpcd.calls.single()["params"].toString(), "over /ubus ping sends four requests unless told how many")
        assertEquals("/bin/ping", rpcd.calls.single()["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun aHopShowsItsAddressLossAndTimes() = runTest {
        val printed = "traceroute to 192.0.2.9 (192.0.2.9), 30 hops max, 46 byte packets\n 1  192.168.99.1  0.008 ms  0.005 ms  0.005 ms\n" +
            " 2  *  *  *\n 3  192.0.2.9  1.000 ms  *  2.000 ms !H\n"
        val (first, silent, last) = Tools(Rpcd(printed), setOf("file")).run("traceroute", mapOf("address" to "192.0.2.9")).toList()
        assertEquals(listOf("1", "192.168.99.1", "0%", "0.005ms"), listOf(first["hop"], first["address"], first["loss"], first["best"]))
        assertEquals(listOf(null, "100%", "3"), listOf(silent["address"], silent["loss"], silent["sent"]))
        assertEquals(listOf("192.0.2.9", "33%", "2ms", "!H"), listOf(last["address"], last["loss"], last["last"], last["status"]))
    }

    @Test
    fun aNameGivesItsAddressesOrWhatTheServerSaid() = runTest {
        val found = "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:53\n\nName:\tlocalhost.lan\nAddress: 127.0.0.1\n\nName:\tlocalhost.lan\nAddress: ::1\n"
        assertEquals(listOf("127.0.0.1", "::1"), Tools(Rpcd(found), setOf("file")).run("nslookup", mapOf("name" to "localhost")).toList().map { it["address"] })
        val unknown = "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:53\n\n** server can't find rd-test.invalid: NXDOMAIN\n\n** server can't find rd-test.invalid: NXDOMAIN\n"
        val said = Tools(Rpcd(unknown), setOf("file")).run("nslookup", mapOf("name" to "rd-test.invalid")).toList().single()
        assertEquals("server can't find rd-test.invalid: NXDOMAIN", said["status"])
        val missing = assertFailsWith<DeviceException> { Tools(Rpcd(""), setOf("file")).run("ping", emptyMap()).toList() }
        assertEquals(ErrorCategory.ArgumentValue, missing.category)
    }

    @Test
    fun aPasswordIsSetThroughRpcSysOnceBothCopiesMatch() = runTest {
        val rpcd = Rpcd("")
        val tools = Tools(rpcd, setOf("rpc-sys"))
        tools.run("password", mapOf("new-password" to "rd-test", "confirm-new-password" to "rd-test")).toList()
        assertEquals(listOf("rpc-sys password_set"), rpcd.methods)
        assertEquals("{\"user\":\"root\",\"password\":\"rd-test\"}", rpcd.calls.single().toString())
        val differ = assertFailsWith<DeviceException> { tools.run("password", mapOf("new-password" to "a", "confirm-new-password" to "b")).toList() }
        assertEquals(ErrorCategory.ArgumentValue, differ.category)
    }
}
