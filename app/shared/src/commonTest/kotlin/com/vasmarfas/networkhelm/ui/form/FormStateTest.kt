package com.vasmarfas.networkhelm.ui.form

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormStateTest {

    @Test
    fun onlyTouchedFieldsThatDifferGoToTheRouter() {
        val form = FormState(mapOf("chain" to "forward", "src-address" to "!192.0.2.0/24", "comment" to "web"))
        form.edit("chain", "forward")
        form.edit("src-address", null)
        form.edit("comment", "")
        form.edit("dst-address", "")
        form.edit("dst-port", "8080")
        assertEquals(mapOf("src-address" to null, "comment" to "", "dst-port" to "8080"), form.changes())
    }

    @Test
    fun theRouterMovesUntouchedFieldsAndMarksTouchedOnes() {
        val form = FormState(mapOf("chain" to "forward", "action" to "accept"))
        form.edit("action", "drop")
        form.update(mapOf("chain" to "input", "action" to "reject"))
        assertEquals("input", form.value("chain"))
        assertEquals("drop", form.value("action"))
        assertTrue(form.changedOnRouter("action"))
        assertFalse(form.changedOnRouter("chain"))

        form.applied(form.changes())
        assertEquals(mapOf("chain" to "input", "action" to "drop"), form.router)
        assertFalse(form.touched("action"))
        assertEquals(emptyMap(), form.changes())
    }

    @Test
    fun unsetFieldsLeaveTheRouterValuesAfterApply() {
        val form = FormState(mapOf("src-address" to "10.0.0.0/8", "protocol" to "tcp"))
        form.edit("src-address", null)
        form.applied(form.changes())
        assertEquals(mapOf("protocol" to "tcp"), form.router)
    }

    @Test
    fun whatIsTypedWhileAWriteGoesStaysAnEditAndTheWrittenValuesDoNot() {
        val form = FormState(mapOf("chain" to "forward"))
        form.edit("chain", "input")
        form.edit("comment", "web")
        val sent = form.changes()
        form.edit("chain", "output")
        form.edit("action", "drop")
        form.applied(sent)
        assertEquals(mapOf("chain" to "input", "comment" to "web"), form.router)
        assertEquals(mapOf<String, String?>("chain" to "output", "action" to "drop"), form.changes())
        assertFalse(form.touched("comment"))
        assertFalse(form.changedOnRouter("chain"))
    }

    @Test
    fun titlesDropColonsNextToEmptyFields() {
        val template = "Firewall Rule <{src-address}:{src-port}->{dst-address}:{dst-port}>"
        val values = mapOf("src-address" to "!192.0.2.0/24", "dst-port" to "8080")
        assertEquals("Firewall Rule <!192.0.2.0/24->8080>", formTitle(template, values::get))
        assertEquals("Firewall Rule <10.0.0.1:53->10.0.0.2:53>", formTitle(template, mapOf("src-address" to "10.0.0.1", "src-port" to "53", "dst-address" to "10.0.0.2", "dst-port" to "53")::get))
        assertEquals("Firewall Rule <->>", formTitle(template) { null })
    }

    // a peer of IPsec has a name only since RouterOS 6.43
    @Test
    fun aTitleFallsBackOnTheNextField() {
        val template = "IPsec Peer <{name|address}>"
        assertEquals("IPsec Peer <peer1>", formTitle(template, mapOf("name" to "peer1", "address" to "192.0.2.1/32")::get))
        assertEquals("IPsec Peer <192.0.2.1/32>", formTitle(template, mapOf("address" to "192.0.2.1/32")::get))
    }
}
