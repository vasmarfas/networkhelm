package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KeeneticSystemTest {

    // answers shaped as the web interface of KeeneticOS 5.1.8 reads them, names and addresses replaced
    private val shows = mapOf(
        "show/interface" to """{"Bridge0":{"id":"Bridge0","interface-name":"Home","type":"Bridge","description":"Home network","security-level":"private"},""" +
            """"PPPoE0":{"id":"PPPoE0","interface-name":"PPPoE0","type":"PPPoE","description":"rd-test provider","global":true,"security-level":"public"}}""",
        "show/rc/interface" to """{"Bridge0":{"up":true},"PPPoE0":{"ipv6":{"access-group":[{"acl":"_WEBADMIN_PPPoE0","direction":"in"}]},"led":{"wan":true},"up":true}}""",
        "show/rc/ipv6/access-list" to """[{"acl":"_WEBADMIN_PPPoE0","index":"h0","action":"permit","protocol":"tcp","source":"::/0","destination":"2001:db8::7","destination-mask":"255.255.255.255",""" +
            """"dst-port-operator":"eq","destination-port":"22","description":"rd-test ssh"},""" +
            """{"acl":"_WEBADMIN_PPPoE0","index":"h1","action":"deny","protocol":"icmp","source":"::/0","destination":"::/0","icmp-type":"echo-request","disable":true},""" +
            """{"acl":"_WEBADMIN_PPPoE0","auto-delete":true}]""",
        "show/rc/schedule" to """{"schedule0":{"description":"rd-test night","action":[{"action":"start","hour":"22","min":"0","dow":"*"},""" +
            """{"action":"stop","hour":"7","min":"30","dow":"1,2,3,4,5"}],"led":{"action":"start"}}}""",
        "show/rc/system/led" to """[{"power":{"shutdown":{"mode":"all"},"schedule":"schedule0"}},{"led":"FN1","control":"UpdatesAvailable","indicate":true}]""",
        "show/led/controls" to """{"control":{"UpdatesAvailable":{"user_configurable":true},"SelectedWan":{"user_configurable":true},"SelectedSchedule":{"user_configurable":true},""" +
            """"Internet":{"user_configurable":false}}}""",
        "show/led/bindings" to """{"binding":[{"index":0,"led":"FN1","default_control":"","active_control":"SelectedWan","user_configurable":true},""" +
            """{"index":1,"led":"SYS","default_control":"System","active_control":"System","user_configurable":false}]}""",
        "show/button" to """{"buttons":{"button":{"WLAN":{"is_switch":false},"RESET":{"is_switch":false},"MODE":{"is_switch":true}}}}""",
        "show/button/bindings" to """{"binding":[{"index":0,"button":"WLAN","action":"click","active_handler":"WpsStartMainAp","default_handler":"WpsStartMainAp","protected":false},""" +
            """{"index":1,"button":"WLAN","action":"double-click","protected":false},""" +
            """{"index":2,"button":"RESET","action":"hold","active_handler":"FactoryReset","default_handler":"FactoryReset","protected":true},""" +
            """{"index":3,"button":"MODE","action":"click","protected":false}]}""",
        "show/button/handlers" to """{"handler":{"WifiToggle":{},"WpsStartMainAp":{},"LedToggle":{},"FactoryReset":{"protected":true},"ModeSwitch":{"switch_related":true}}}""",
        "show/version" to """{"release":"5.01.C.8.0-2","sandbox":"stable","title":"5.1.8"}""",
        "show/rc/components/auto-update" to """{"channel":"stable","schedule":"schedule0"}""",
    )

    // a show answered from the answers above, a change with nothing
    private fun answerOf(command: JsonObject): JsonElement {
        var at = command["show"] as? JsonObject ?: return JsonObject(emptyMap())
        val path = mutableListOf<String>()
        while (at.size == 1) {
            val (key, inner) = at.entries.single()
            if (inner !is JsonObject) break
            path += key
            at = inner
        }
        val result = shows["show/" + path.joinToString("/")]?.let(Json::parseToJsonElement) ?: Json.parseToJsonElement("""{"status":[{"status":"error","message":"no such command"}]}""")
        return JsonObject(mapOf("show" to path.foldRight(result) { key, inner -> JsonObject(mapOf(key to inner)) }))
    }

    // the commands that go on are answered in turn from answers
    private inner class Fake : KeeneticCall {
        val posted = mutableListOf<String>()
        val rested = mutableListOf<String>()
        val answers = ArrayDeque<String>()

        override suspend fun show(path: String): JsonElement = Json.parseToJsonElement(shows[path] ?: throw DeviceException(ErrorCategory.MissingItem, "this router has no /rci/$path"))

        override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> = commands.map { command ->
            if ("show" !in command) posted += command.toString()
            answerOf(command)
        }

        override suspend fun realm() = "Keenetic Launcher"

        override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement {
            rested += listOfNotNull(method, path, body?.toString()).joinToString(" ")
            return Json.parseToJsonElement(answers.removeFirst())
        }
    }

    @Test
    fun aRuleOfIpv6IsAddedBoundChangedMovedAndRemovedByTheMatchTheRouterShows() = runTest {
        val router = Fake()
        val firewall = Firewall6(router) { emptyMap() }
        val rules = firewall.read()
        assertEquals(listOf("h0", "h1"), rules.map { it.id })
        assertEquals(listOf("PPPoE0", "in", "", "2001:db8::7", "22", "false"), listOf("interface", "direction", "source", "destination", "destination-port", "disabled").map { rules[0][it] })
        assertEquals(listOf("icmp", "echo-request", "true"), listOf("protocol", "icmp-type", "disabled").map { rules[1][it] })
        val arguments = firewall.arguments().associateBy { it.name }
        assertEquals("rd-test night", arguments.getValue("schedule").labels["schedule0"])
        assertEquals("rd-test provider (PPPoE0)", arguments.getValue("interface").labels["PPPoE0"])
        assertEquals("TCP/22 - Secure Shell (SSH)", arguments.getValue("protocol").labels["tcp/22"])

        firewall.add(
            mapOf(
                "interface" to "Bridge0", "action" to "permit", "protocol" to "icmp", "destination" to "2001:db8::/64", "icmp-type" to "echo-request", "icmp-code" to "0",
                "source-port" to "1000", "description" to "rd-test ping",
            ),
        )
        assertEquals(
            listOf(
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_Bridge0","action":"permit","protocol":"icmp","source":"::/0","destination":"2001:db8::/64","icmp-type":"echo-request","icmp-code":"0"}}}""",
                """{"interface":{"Bridge0":{"ipv6":{"access-group":{"acl":"_WEBADMIN_Bridge0","direction":"in"}}}}}""",
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_Bridge0","rule":{"index":0,"description":"rd-test ping"}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        firewall.add(mapOf("interface" to "PPPoE0", "protocol" to "tcp/443", "source" to "2001:db8::1", "destination-port" to "8443"))
        assertEquals(
            listOf(
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","action":"permit","protocol":"tcp","source":"2001:db8::1","source-mask":"255.255.255.255","destination":"::/0",""" +
                    """"dst-port-operator":"eq","destination-port":"443"}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        firewall.set("h1", mapOf("description" to "rd-test icmp", "disabled" to "no"))
        assertEquals(
            listOf(
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"description":"rd-test icmp"}}}}""",
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"disable":{"no":true}}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        firewall.set("h0", mapOf("source" to "2001:db8::/48"))
        assertEquals(
            listOf(
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","action":"permit","protocol":"tcp","source":"2001:db8::/48","destination":"2001:db8::7",""" +
                    """"destination-mask":"255.255.255.255","dst-port-operator":"eq","destination-port":"22"}}}""",
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":2,"new-index":0}}}}""",
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","no":true,"action":"permit","protocol":"tcp","source":"::/0","destination":"2001:db8::7",""" +
                    """"destination-mask":"255.255.255.255","dst-port-operator":"eq","destination-port":"22"}}}""",
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":0,"description":"rd-test ssh"}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        firewall.move(listOf("h1"), "h0")
        assertEquals(listOf("""{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"new-index":0}}}}"""), router.posted)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { firewall.set("h0", mapOf("interface" to "Bridge0")) }.category)

        router.posted.clear()
        firewall.remove(listOf("h0", "h1"))
        assertEquals(
            listOf(
                """{"ipv6":{"access-list":{"acl":"_WEBADMIN_PPPoE0","no":true,"action":"deny","protocol":"icmp","source":"::/0","destination":"::/0","icmp-type":"echo-request"}}}""",
                """{"interface":{"PPPoE0":{"ipv6":{"access-group":{"no":true,"acl":"_WEBADMIN_PPPoE0","direction":"in"}}}}}""",
                """{"parse":"no ipv6 access-list \"_WEBADMIN_PPPoE0\""}""",
            ),
            router.posted.drop(1),
        )
    }

    @Test
    fun aScheduleKeepsItsMomentsAsTheRouterDoesAndIsRemovedByItsName() = runTest {
        val router = Fake()
        val schedules = Schedules(router)
        assertEquals(listOf("schedule0", "rd-test night", "start 22:00 *; stop 07:30 mon,tue,wed,thu,fri"), schedules.read().single().let { listOf(it.id, it["description"], it["actions"]) })

        assertEquals("schedule1", schedules.add(mapOf("description" to "rd-test work", "actions" to "start 8:00 mon,tue; stop 17:05 MON,TUE")))
        assertEquals(
            """{"schedule":{"name":"schedule1","description":"rd-test work","action":[{"action":"start","hour":"8","min":"0","dow":"1,2"},{"action":"stop","hour":"17","min":"5","dow":"1,2"}]}}""",
            router.posted.last(),
        )
        schedules.set("schedule0", mapOf("actions" to "start 00:00 *"))
        assertEquals("""{"schedule":{"name":"schedule0","action":[{"no":true},{"action":"start","hour":"0","min":"0","dow":"*"}]}}""", router.posted.last())
        schedules.remove(listOf("schedule0"))
        assertEquals("""{"schedule":{"name":"schedule0","no":true}}""", router.posted.last())

        router.posted.clear()
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { schedules.add(mapOf("description" to "rd-test", "actions" to "start 25:00 mon")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { schedules.add(mapOf("description" to "rd-test", "actions" to "begin 08:00 mon")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { schedules.add(mapOf("description" to " ")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { schedules.remove(listOf("")) }.category)
        assertEquals(emptyList(), router.posted)
    }

    @Test
    fun theLedsGoOffOnAScheduleAndEachFollowsTheJobChosenForIt() = runTest {
        val router = Fake()
        val leds = Leds(router)
        assertEquals(listOf("all", "schedule0"), leds.read().single().let { listOf(it["mode"], it["schedule"]) })
        leds.set(null, mapOf("mode" to "on"))
        assertEquals("""{"system":{"led":{"power":{"schedule":{"no":true},"shutdown":{"no":true}}}}}""", router.posted.last())
        leds.set(null, mapOf("mode" to "front", "schedule" to ""))
        assertEquals("""{"system":{"led":{"power":{"schedule":{"no":true},"shutdown":{"mode":"front"}}}}}""", router.posted.last())
        leds.set(null, mapOf("schedule" to "schedule0"))
        assertEquals("""{"system":{"led":{"power":{"schedule":"schedule0","shutdown":{"mode":"all"}}}}}""", router.posted.last())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { leds.set(null, mapOf("mode" to "rd-test")) }.category)

        val bindings = LedBindings(router) { mapOf("SelectedWan" to "If using the Internet connection:") }
        assertEquals(listOf("FN1" to "SelectedWan__PPPoE0"), bindings.read().map { it.id to it["control"] })
        val control = bindings.arguments().single { it.name == "control" }
        assertEquals(listOf("not-used", "UpdatesAvailable", "SelectedWan__PPPoE0", "SelectedSchedule__schedule0"), control.choices)
        assertEquals("If using the Internet connection: rd-test provider", control.labels["SelectedWan__PPPoE0"])
        bindings.set("FN1", mapOf("control" to "SelectedSchedule__schedule0"))
        assertEquals(
            listOf("""{"schedule":{"name":"schedule0","led":{"action":"start"}}}""", """{"system":{"led":{"led":"FN1","control":"SelectedSchedule","indicate":true}}}"""),
            router.posted.takeLast(2),
        )
        bindings.set("FN1", mapOf("control" to "not-used"))
        assertEquals("""{"system":{"led":{"led":"FN1","control":"","indicate":true,"no":true}}}""", router.posted.last())
    }

    @Test
    fun aButtonTakesAJobTheRouterOffersAndKeepsTheOnesItProtects() = runTest {
        val router = Fake()
        val buttons = Buttons(router)
        assertEquals(listOf("WLAN/click" to "WpsStartMainAp", "WLAN/double-click" to "not-used", "RESET/hold" to "FactoryReset"), buttons.read().map { it.id to it["handler"] })
        assertEquals(listOf("not-used", "WifiToggle", "WpsStartMainAp", "LedToggle"), buttons.arguments().single { it.name == "handler" }.choices)
        buttons.set("WLAN/double-click", mapOf("handler" to "WifiToggle"))
        assertEquals("""{"system":{"button":{"button":"WLAN","handler":"WifiToggle","action":"double-click"}}}""", router.posted.last())
        buttons.set("WLAN/click", mapOf("handler" to "not-used"))
        assertEquals("""{"system":{"button":{"button":"WLAN","handler":"","action":"click","no":true}}}""", router.posted.last())
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { buttons.set("RESET/hold", mapOf("handler" to "WifiToggle")) }.category)
    }

    @Test
    fun anUpdateIsLookedForInTheChannelAndInstalledOnlyWhereTheRouterFoundOne() = runTest {
        val router = Fake()
        val firmware = Firmware(router)
        assertEquals(listOf("5.1.8", "stable", "true", "schedule0"), firmware.read().single().let { listOf(it["installed-version"], it["channel"], it["auto-update"], it["schedule"]) })

        router.answers += listOf(
            """{"continued":true}""",
            """{"sandbox":"stable","firmware":{"title":"5.1.9","version":"5.01.C.9.0-0"},"component":{"base":{"installed":"5.01.C.8.0-2","version":"5.01.C.9.0-0"}}}""",
        )
        firmware.run("check-update", emptyMap())
        assertEquals(listOf("""POST components/list {"sandbox":"stable"}""", "GET components/list"), router.rested)
        assertEquals(listOf("5.1.9", "true"), firmware.read().single().let { listOf(it["latest-version"], it["update-available"]) })

        router.rested.clear()
        router.answers += listOf(
            """{"sandbox":"stable","firmware":{"title":"5.1.9"},"component":{"base":{"installed":"5.01.C.8.0-2","version":"5.01.C.9.0-0"}}}""",
            """{"progress":{"flash:firmware":40},"continued":true}""",
            """{"progress":{"flash:firmware":200},"file_size":10485760,"continued":true}""",
        )
        firmware.run("install", emptyMap())
        assertEquals(listOf("""POST components/list {"sandbox":"stable"}""", """POST components/commit {"reason":"manual"}""", "GET components/commit"), router.rested)
        assertEquals("100", firmware.read().single()["progress"])

        router.answers += """{"sandbox":"stable","firmware":{"title":"5.1.8"},"component":{"base":{"installed":"5.01.C.8.0-2","version":"5.01.C.8.0-2"}}}"""
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { firmware.run("install", emptyMap()) }.category)
        router.answers += """{"status":[{"status":"error","message":"rd-test: no update server"}]}"""
        assertEquals("rd-test: no update server", assertFailsWith<DeviceException> { firmware.run("check-update", emptyMap()) }.message)
        assertEquals(listOf("""POST components/commit {"reason":"manual"}"""), router.rested.filter { "commit" in it && it.startsWith("POST") })

        router.posted.clear()
        firmware.set(null, mapOf("channel" to "preview", "auto-update" to "no"))
        assertEquals(listOf("""{"components":{"auto-update":{"channel":"preview","disable":true}}}"""), router.posted)
        assertNull(firmware.read().single()["latest-version"])
        firmware.set(null, mapOf("schedule" to ""))
        assertEquals("""{"components":{"auto-update":{"schedule":{"no":true}}}}""", router.posted.last())
    }
}
