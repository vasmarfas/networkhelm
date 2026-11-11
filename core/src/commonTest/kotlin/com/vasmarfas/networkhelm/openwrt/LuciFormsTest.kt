package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class LuciFormsTest {

    @Test
    fun anOptionKeepsItsLabelTabAndConditions() {
        val tabs = listOf(LuciTab("general", JsonPrimitive("General Settings")), LuciTab("advanced", JsonPrimitive("Advanced Settings")))
        val masquerading = listOf(JsonObject(mapOf("masq" to JsonPrimitive("1"))))
        val forward = LuciOption("Value", "dest", JsonPrimitive("Allow forward to <em>destination zones</em>:"), tab = "general", depends = masquerading)
        val argument = forward.argument(tabs, listOf(mapOf("proto" to "static")))
        assertEquals("Allow forward to destination zones:", argument?.label)
        assertEquals("General Settings", argument?.tab)
        assertEquals(listOf(mapOf("masq" to "1", "proto" to "static")), argument?.depends)
        val reversed = LuciOption("Value", "src", depends = listOf(JsonObject(mapOf("!reverse" to JsonPrimitive(true), "masq" to JsonPrimitive("1")))))
        assertEquals(emptyList(), reversed.argument()?.depends)
    }

    @Test
    fun aFlagHasTheWordsAndTheDefaultOfItsView() {
        val password = LuciOption("Flag", "PasswordAuth", default = JsonPrimitive("on"), enabled = JsonPrimitive("on"), disabled = JsonPrimitive("off")).argument()
        assertEquals(listOf("on", "off"), password?.choices)
        assertEquals("on", password?.default)
        val enabled = LuciOption("Flag", "enabled", default = JsonPrimitive("1"), enabled = JsonPrimitive("1"), disabled = JsonPrimitive("0")).argument()
        assertEquals(emptyList(), enabled?.choices)
        assertEquals("1", enabled?.default)
    }

    @Test
    fun aViewEditsTheConfigsItsSectionsName() {
        assertEquals(setOf("network", "dhcp"), configsOfView("m = new form.Map('network', _('Interfaces')); ss = o.subsection; ss.uciconfig = 'dhcp';"))
    }

    @Test
    fun aMenuFileThatIsNotAJsonObjectHasNoPages() {
        assertEquals(emptyList<LuciNode>(), menuNodes("luci-app-rdtest", "{ \"admin/services/rdtest\": { \"title\": "))
        assertEquals(emptyList<LuciNode>(), menuNodes("luci-app-rdtest", "[]"))
        assertEquals(listOf("admin/services/rdtest"), menuNodes("luci-app-rdtest", "{ \"admin/services/rdtest\": { \"title\": \"RD Test\" } }").map { it.path })
    }

    @Test
    fun aWidgetOfSeveralIsAList() {
        assertEquals(ValueType.Many(ValueType.Text(), LIST), LuciOption("CBI.NetworkSelect", "network", multiple = true).argument()?.type)
    }

    @Test
    fun aFieldKeepsTheLabelsAndThePlaceholderOfItsView() {
        val version = LuciOption("ListValue", "use_ipv6", default = JsonPrimitive("0"), optional = true, values = listOf(listOf("0", "IPv4-Address"), listOf("1", "IPv6-Address"))).argument()
        assertEquals(mapOf("0" to "IPv4-Address", "1" to "IPv6-Address"), version?.labels)
        assertEquals(false, version?.optional)
        val port = LuciOption("Value", "Port", datatype = "port", placeholder = JsonPrimitive("22"), optional = true).argument()
        assertEquals("22", port?.placeholder)
        assertEquals(false, port?.optional)
        val unit = LuciOption("ListValue", "check_unit", optional = true, values = listOf(listOf("seconds", "seconds"), listOf("minutes", "minutes"))).argument()
        assertEquals(emptyMap(), unit?.labels)
        assertEquals(true, unit?.optional)
    }

    @Test
    fun theNegationOfAListIsThatOfItsEntries() {
        val subnets = LuciOption("DynamicList", "subnet", datatype = "neg(cidr(\"true\"))").argument()
        assertEquals(ValueType.Many(ValueType.Ip, LIST, negatable = true), subnets?.type)
        assertEquals(false, subnets?.negatable)
        assertEquals(true, LuciOption("Value", "dest_port", datatype = "neg(portrange)").argument()?.negatable)
    }
}
