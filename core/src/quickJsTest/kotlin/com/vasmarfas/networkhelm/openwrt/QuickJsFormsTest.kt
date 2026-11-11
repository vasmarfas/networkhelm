package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuickJsFormsTest {

    @Test
    fun longModuleNamesAreRejectedBeforeRequestingTheirSource() = runTest {
        var files = 0
        val base = router("hostModule('x'.repeat(513));", emptyMap())
        val ubus = object : Ubus by base {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
                if (obj == "file" && method == "read") files++
                return base.call(obj, method, args)
            }
        }
        assertFailsWith<DeviceException> { QuickJsForms(ubus, null).maps("rdtest/names") }
        assertEquals(1, files)
    }

    @Test
    fun missingModulesCountTowardsTheCacheLimit() = runTest {
        var files = 0
        val base = router("for (var i = 0; i < 2000; i++) hostModule('missing' + i);", emptyMap())
        val ubus = object : Ubus by base {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
                if (obj == "file" && method == "read") files++
                return base.call(obj, method, args)
            }
        }
        assertFailsWith<DeviceException> { QuickJsForms(ubus, null).maps("rdtest/modules") }
        assertEquals(1_025, files)
    }

    @Test
    fun anOversizedSourceIsRejectedBeforeEvaluation() = runTest {
        assertFailsWith<DeviceException> { QuickJsForms(router(" ".repeat(101), emptyMap()), null, sourceLimit = 100).maps("rdtest/large") }
    }

    @Test
    fun aFormResultCannotExceedItsLimit() = runTest {
        val view = "'require view'; 'require form'; return view.extend({render: function() { return new form.Map('network').render(); }});"
        assertFailsWith<DeviceException> { QuickJsForms(router(view, emptyMap()), null, resultLimit = 1).maps("rdtest/large") }
    }

    @Test
    fun javascriptAllocationsCannotGrowPastTheRuntimeLimit() = runTest {
        val view = "var values = []; for (var i = 0; i < 1000000; i++) values.push('xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' + i);"
        val refused = assertFailsWith<DeviceException> { QuickJsForms(router(view, emptyMap()), null, memoryLimit = 512 * 1024).maps("rdtest/large") }
        assertContains(refused.message.orEmpty(), "out of memory")
    }

    @Test
    fun aPageThatFailsToEvaluateIsLeftWithoutAForm() = runTest {
        val view = "'require view'; return view.extend({load: function() { return null.packages; }});"
        assertEquals(emptyList<LuciMap>(), QuickJsForms(router(view, emptyMap()), null).maps("rdtest/broken"))
    }

    @Test
    fun aPageThatDoesNotEndInItsTimeIsLeftWithoutAForm() = runTest {
        val view = "'require view'; return view.extend({load: function() { while (true) {} }});"
        assertEquals(emptyList<LuciMap>(), QuickJsForms(router(view, emptyMap()), null, timeLimit = 2_000).maps("rdtest/endless"))
    }

    @Test
    fun aPageWhoseSchemaCannotBeReadIsLeftWithoutAForm() = runTest {
        val view = "'require view'; 'require form'; return view.extend({render: function() { var m = new form.Map('rdtest'); m.section(form.TypedSection, 'rule', { text: 'Rules' }); return m.render(); }});"
        assertEquals(emptyList<LuciMap>(), QuickJsForms(router(view, emptyMap()), null).maps("rdtest/titled"))
    }

    @Test
    fun menusAndViewsTheLoginMayNotReadLeaveNoForms() = runTest {
        val ubus = object : Ubus {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = when ("$obj $method") {
                "file list" -> JsonObject(mapOf("entries" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("luci-base.json")))))))
                else -> throw UbusException(UbusStatus.PERMISSION_DENIED)
            }

            override suspend fun objects() = setOf("file", "uci")

            override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

            override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
        }
        val forms = QuickJsForms(ubus, null)
        assertEquals(emptyList(), forms.menu())
        assertEquals(emptyMap(), forms.views())
        assertEquals(emptyList(), forms.maps("network/interfaces"))
        assertEquals(emptyList(), forms.protocol("dhcp"))
    }

    private fun router(view: String, sections: Map<String, Map<String, String>>) = object : Ubus {
        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = when ("$obj $method") {
            "file read" -> JsonObject(mapOf("data" to JsonPrimitive(view.takeIf { args["path"]?.jsonPrimitive?.contentOrNull?.contains("/view/rdtest/") == true })))
            "uci get" -> JsonObject(
                mapOf(
                    "values" to JsonObject(
                        sections.entries.mapIndexed { index, (name, options) ->
                            name to JsonObject(mapOf(".name" to JsonPrimitive(name), ".index" to JsonPrimitive(index)) + options.mapValues { JsonPrimitive(it.value) })
                        }.toMap(),
                    ),
                ),
            )
            else -> throw UbusException(UbusStatus.METHOD_NOT_FOUND)
        }

        override suspend fun objects() = setOf("file", "uci")

        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

        override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
    }

    @Test
    fun anEmojiOfAViewAndOfTheRouterComesThroughWhole() = runTest {
        val view = """
            'use strict';
            'require view';
            'require form';
            'require uci';
            return view.extend({
                load: function () { return uci.load('wireless'); },
                render: function () {
                    var m = new form.Map('wireless');
                    var s = m.section(form.TypedSection, 'wifi-iface');
                    s.addremove = true;
                    s.option(form.Value, 'ssid', '📝 ' + uci.get('wireless', 'cfg1', 'ssid'));
                    return m.render();
                }
            });
        """.trimIndent()
        val maps = QuickJsForms(router(view, mapOf("cfg1" to mapOf(".type" to "wifi-iface", "ssid" to "Кафе☕🙂"))), null).maps("rdtest/emoji")
        assertEquals("📝 Кафе☕🙂", maps.single().sections.single().options.single().title?.contentOrNull)
    }

    @Test
    fun theFieldsOfADialogTiedToItsSectionShowWhateverItHas() = runTest {
        val view = """
            'use strict';
            'require view';
            'require form';
            'require uci';
            return view.extend({
                load: function () { return uci.load('ddns'); },
                render: function () {
                    var m = new form.Map('ddns');
                    var s = m.section(form.GridSection, 'service');
                    s.addremove = true;
                    s.option(form.Flag, 'enabled', 'Enabled');
                    s.addModalOptions = function (s, section_id) {
                        var service = uci.get('ddns', section_id, 'service_name') || '-';
                        var o = s.option(form.Flag, 'enabled', 'Enabled');
                        o.default = '1';
                        o = s.option(form.ListValue, 'service_name', 'DDNS Service provider');
                        o.value('-', 'custom');
                        o.value('dyndns.org');
                        o.cfgvalue = function (sid) { return uci.get('ddns', sid, 'service_name') || '-'; };
                        o = s.option(form.Button, '_switch_proto');
                        o.depends({ service_name: service, '!reverse': true });
                        o = s.option(form.Value, 'update_url', 'Custom update-URL');
                        o.depends('service_name', '-');
                        s.option(form.Value, 'domain', 'Domain');
                        o = s.option(form.Value, 'ip_network', 'Network');
                        o.depends('ip_source', 'network');
                        for (var c of s.children) {
                            if (c.option === 'service_name' || c.option === 'update_url' || c.option === '_switch_proto') continue;
                            if (c.deps.length) for (var d of c.deps) d.service_name = service;
                            else c.depends('service_name', service);
                        }
                    };
                    return m.render();
                }
            });
        """.trimIndent()
        val sections = mapOf(
            "home" to mapOf(".type" to "service", "service_name" to "dyndns.org", "ip_source" to "network"),
            "office" to mapOf(".type" to "service", "ip_source" to "network"),
        )
        val service = QuickJsForms(router(view, sections), null).maps("rdtest/ddns").single().sections.single()
        val options = service.options.associateBy { it.name }
        assertEquals("0", options.getValue("enabled").argument()?.default)
        assertEquals(mapOf("enabled" to "1"), service.added)
        val provider = options.getValue("service_name").argument()
        assertEquals(listOf("", "dyndns.org"), provider?.choices)
        assertEquals(mapOf("" to "custom"), provider?.labels)
        assertEquals(listOf(mapOf("service_name" to "")), options.getValue("update_url").argument()?.depends)
        assertEquals(emptyList(), options.getValue("domain").argument()?.depends)
        assertEquals(listOf(mapOf("ip_source" to "network")), options.getValue("ip_network").argument()?.depends)
    }

    @Test
    fun theButtonThatAddsASectionTellsWhatANewOneHas() = runTest {
        val view = """
            'use strict';
            'require view';
            'require form';
            'require uci';
            return view.extend({
                load: function () { return uci.load('rdtest'); },
                render: function () {
                    var m = new form.Map('rdtest');
                    var rules = m.section(form.GridSection, 'rule');
                    rules.anonymous = true;
                    rules.addremove = true;
                    rules.option(form.Value, 'name', 'Name');
                    rules.handleAdd = function (ev) {
                        var sid = uci.add('rdtest', 'rule');
                        uci.set('rdtest', sid, 'src', 'wan');
                        uci.set('rdtest', sid, 'dest', 'lan');
                        return this.renderMoreOptionsModal(sid);
                    };
                    var interfaces = m.section(form.GridSection, 'interface');
                    interfaces.anonymous = true;
                    interfaces.addremove = true;
                    interfaces.option(form.Value, 'proto', 'Protocol');
                    interfaces.handleAdd = function (ev) {
                        var m2 = new form.Map('rdtest');
                        var s2 = m2.section(form.NamedSection, '_new_');
                        var name = s2.option(form.Value, 'name', 'Name');
                        m2.render().then(function (nodes) { nodes.querySelector('input').focus(); });
                    };
                    return m.render();
                }
            });
        """.trimIndent()
        val sections = mapOf("cfg01" to mapOf(".type" to "rule", "name" to "rd-test-ping"), "lan" to mapOf(".type" to "interface", "proto" to "static"))
        val forms = QuickJsForms(router(view, sections), null).maps("rdtest/add").single().sections.associateBy { it.type }
        assertEquals(mapOf("src" to "wan", "dest" to "lan"), forms.getValue("rule").added)
        assertEquals(null, forms.getValue("rule").named)
        assertEquals(true, forms.getValue("interface").named)
    }
}
