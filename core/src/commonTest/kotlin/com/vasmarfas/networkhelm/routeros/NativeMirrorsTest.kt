package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMirrorsTest {
    private val addresses = nativeMirrors.getValue("ip/firewall/address-list")
    private val connections = nativeMirrors.getValue("ip/firewall/connection")

    @Test
    fun aRowOfTheWindowIsWrittenAsTheConsolePrintsIt() {
        val item = Item("*6", mapOf("list" to "C", "address" to "10.6.0.1", "timeout" to "3562", "creation-time" to "1794241550", "dynamic" to "true", "disabled" to "false", "comment" to ""))
        assertEquals(
            mapOf(".id" to "*6", "list" to "C", "address" to "10.6.0.1", "timeout" to "00:59:22", "creation-time" to "2026-11-09 16:25:50", "dynamic" to "true", "disabled" to "false"),
            addresses.row(item),
        )
        assertEquals("2d3h3m27s", addresses.row(Item("*7", mapOf("timeout" to "183807")))["timeout"])
    }

    @Test
    fun aConnectionNamesItsProtocolAndStateAndLeavesOutAnEmptyState() {
        val tcp = connections.row(Item("*16", mapOf("protocol" to "6", "tcp-state" to "3", "timeout" to "86399")))
        assertEquals(mapOf(".id" to "*16", "protocol" to "tcp", "tcp-state" to "established", "timeout" to "23:59:59"), tcp)
        assertNull(connections.row(Item("*1", mapOf("protocol" to "17", "tcp-state" to "0")))["tcp-state"])
        assertEquals("250", connections.row(Item("*2", mapOf("protocol" to "250")))["protocol"])
    }

    @Test
    fun theSampleTakesTheFirstRowsAndTheRowsWithWhatTheFirstOnesLack() {
        val rows = (1..200).map { mapOf(".id" to "*$it", "list" to "A") } +
            mapOf(".id" to "*C9", "list" to "A", "comment" to "x") + mapOf(".id" to "*CA", "list" to "A", "timeout" to "00:00:05")
        val sample = addresses.sample(rows)
        assertEquals(52, sample.size)
        assertTrue("*C9" in sample && "*CA" in sample && "*1" in sample)
        val protocols = (1..100).map { mapOf(".id" to "*$it", "protocol" to "udp") } + mapOf(".id" to "*FF", "protocol" to "gre")
        assertTrue("*FF" in connections.sample(protocols))
    }

    @Test
    fun theConsoleMustPrintTheSameFixedValuesAndTheSameShapeOfMovingOnes() {
        val native = mapOf(".id" to "*1", "protocol" to "udp", "src-port" to "53", "timeout" to "00:59:24", "orig-bytes" to "57", "assured" to "false")
        val mirrored = mapOf("*1" to native)
        assertTrue(connections.agrees(mirrored, listOf(native + ("timeout" to "00:59:20") + ("orig-bytes" to "114") + ("assured" to "true"))))
        assertFalse(connections.agrees(mirrored, listOf(native + ("src-port" to "54"))))
        assertFalse(connections.agrees(mirrored, listOf(native + ("timeout" to "59m20s x"))))
        assertFalse(connections.agrees(mirrored, listOf(native + ("tcp-state" to "established"))))
        assertFalse(connections.agrees(mirrored, listOf(native + (".id" to "*2"))))
        assertFalse(connections.agrees(mirrored, emptyList()))
    }

    @Test
    fun aRecordOfTheDnsCacheShowsTheTimeLeftAndTheRecordsTheConsoleHidesAreKnown() {
        val dns = nativeMirrors.getValue("ip/dns/cache")
        val root = dns.row(Item("*26A4", mapOf("name" to "", "type" to "2", "data" to "g.root-servers.net.", "ttl" to "397094", "static" to "false")), uptime = 179_340)
        assertEquals(mapOf(".id" to "*26A4", "name" to "", "type" to "NS", "data" to "g.root-servers.net.", "ttl" to "2d12h29m14s", "static" to "false"), root)
        assertEquals("04:30:58", dns.row(Item("*1", mapOf("ttl" to "195598")), uptime = 179_340)["ttl"])
        assertEquals("0s", dns.row(Item("*2", mapOf("ttl" to "179991")), uptime = 180_200)["ttl"])
        val reverse = mapOf(".id" to "*F136", "type" to "PTR", "data" to "nh-a.test.", "static" to "true")
        val negative = mapOf(".id" to "*F13E", "name" to "nh-nx.test", "negative" to "true")
        assertTrue(dns.shown(root))
        assertFalse(dns.shown(reverse))
        assertFalse(dns.shown(negative))
        assertEquals(listOf("*F136", "*F13E"), dns.unseen(listOf(reverse, negative)))
    }

    @Test
    fun aCounterOfTheWindowIsNoGreaterThanTheConsolePrintsAfterIt() {
        val native = mapOf(".id" to "*1", "orig-packets" to "4", "orig-bytes" to "1218", "repl-packets" to "3", "repl-bytes" to "132")
        assertTrue(connections.agrees(mapOf("*1" to native), listOf(native + ("orig-packets" to "5") + ("orig-bytes" to "1270"))))
        assertFalse(connections.agrees(mapOf("*1" to native + ("orig-bytes" to "3") + ("repl-packets" to "1218")), listOf(native)))
        assertFalse(connections.agrees(mapOf("*1" to native), listOf(native + ("repl-bytes" to "-"))))
    }
}
