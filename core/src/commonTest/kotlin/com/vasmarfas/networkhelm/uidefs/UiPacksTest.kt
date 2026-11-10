package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.PRESENCE
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.session.RouterFacts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiPacksTest {

    private class Router(val menus: Map<String, Pair<UiMembers, List<String>>>) : UiSchema {
        override suspend fun members(path: MenuPath) = menus[path.toString()]?.first

        override suspend fun properties(path: MenuPath) = menus[path.toString()]?.second.orEmpty()
    }

    private fun members(menus: String = "", commands: String = "") = UiMembers(menus.split(' ').filter { it.isNotEmpty() }, commands.split(' ').filter { it.isNotEmpty() })

    private val router = Router(
        mapOf(
            "" to (members("ip cmr user") to emptyList()),
            "ip" to (members("gadget pool") to emptyList()),
            "ip/gadget" to (members("option", "print set add get renew") to listOf("name", "use-peer-dns", "allow-reconfigure", "address", "state", "disabled", "dynamic")),
            "ip/gadget/option" to (members("", "print set add get") to listOf("name", "code")),
            "cmr" to (members("device alert", "print set get") to listOf("enabled", "pairing-requirement")),
            "cmr/device" to (members("", "print set get") to listOf("identity", "board", "version")),
            "cmr/alert" to (members("", "print set add get") to listOf("name", "severity")),
            "user" to (members("", "print set add get") to listOf("name", "group")),
        ),
    )

    private fun entries(text: String) = uiNodes(parseJg(text))

    private val gadgets = "[{name:'Gadgets',group:'IP',c:[" +
        "{name:'Gadgets',title:'Gadgets',type:'map',path:[1,1],nameval:'Name',c:[" +
        "{name:'General',type:'tab'},{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Use Peer DNS',type:'bool',id:'b3',width:45},{type:'separator'}," +
        "{name:'Allow Reconfigure Messages',type:'bool',id:'b4'},{name:'Status',type:'tab'},{name:'IP Address',type:'network',id:'u6',ro:1,width:80}," +
        "{name:'Unknown Thing',type:'string',id:'s9'},{type:'doit',name:'Renew',cmd:1},{type:'statusbar',c:[{name:'State',type:'enm',id:'u5',width:70}]}]}," +
        "{name:'Gadget Option',title:'Gadget Options',type:'map',path:[1,5],c:[{name:'Name',type:'string',id:'sfe0010',width:80},{name:'Code',type:'number',id:'u1',width:50}]}]}]"

    @Test
    fun anEntryBecomesAPackWithTheTabsTheColumnsAndTheLabelsOfItsWindows() = runTest {
        val pack = uiPacks(entries(gadgets), router, emptyMap()).single()
        assertEquals(listOf("IP", "Gadgets"), pack.menu)
        assertEquals(listOf("Gadgets" to "ip/gadget", "Gadget Options" to "ip/gadget/option"), pack.window.tabs.map { it.title to it.path })
        val path = pack.paths.getValue("ip/gadget")
        assertEquals(listOf("name", "use-peer-dns", "address", "state"), path.columns)
        assertEquals(listOf("address"), path.readonly)
        assertEquals(listOf("renew"), path.actions.map { it.command })
        val form = pack.forms.getValue(path.form.orEmpty())
        assertEquals(listOf("General", "Status"), form.tabs.map { it.title })
        assertEquals(listOf("name", "use-peer-dns", "-", "allow-reconfigure"), form.tabs[0].fields)
        assertEquals(listOf("address"), form.tabs[1].fields)
        assertEquals(mapOf("allow-reconfigure" to "Allow Reconfigure Messages", "address" to "IP Address"), path.labels)
        assertEquals(listOf("name", "code"), pack.paths.getValue("ip/gadget/option").columns)
    }

    @Test
    fun aTableIsReadAgainAsOftenAsItsWindowSays() = runTest {
        val slow = gadgets.replace("type:'map',path:[1,1],", "type:'map',path:[1,1],autorefresh:5000,")
        val pack = uiPacks(entries(slow), router, emptyMap()).single()
        assertEquals(5000L, pack.paths.getValue("ip/gadget").refresh)
        assertNull(pack.paths.getValue("ip/gadget/option").refresh)
    }

    @Test
    fun anEnumerationTheRouterDrawsAsRadioButtonsIsNamedInTheRadiosOfItsPath() = runTest {
        val radios = gadgets.replace("{name:'Use Peer DNS',type:'bool',id:'b3',width:45}", "{name:'Use Peer DNS',type:'enm',id:'u3',radio:1,compact:1,values:{type:'static',map:['no','yes']},width:45}")
        val path = uiPacks(entries(radios), router, emptyMap()).single().paths.getValue("ip/gadget")
        assertEquals(listOf("use-peer-dns"), path.radios)
        assertEquals(emptyList(), uiPacks(entries(gadgets), router, emptyMap()).single().paths.getValue("ip/gadget").radios)
    }

    @Test
    fun aTitleThatSharesNoWordWithThePropertyItIsPairedWithGivesItNoLabel() = runTest {
        val lonely = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("", "print set add") to listOf("name", "dscp")),
            ),
        )
        val text = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[1,1],c:[" +
            "{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Differentiated Services',type:'number',id:'u1',width:50}]}]}]"
        val pack = uiPacks(entries(text), lonely, emptyMap()).single()
        assertEquals(listOf("name", "dscp"), pack.forms.getValue("ip/gadget").tabs.single().fields)
        assertEquals(emptyMap(), pack.paths.getValue("ip/gadget").labels)
    }

    @Test
    fun theWindowAfterOneThatIsLeftOutFindsItsOwnMenuAndNotTheMenuOfTheEntry() = runTest {
        val peers = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("peer", "print set add") to listOf("name", "address")),
                "ip/gadget/peer" to (members("", "print set add") to listOf("name", "public-key")),
            ),
        )
        val text = "[{name:'Gadget',group:'IP',c:[" +
            "{name:'Interface',title:'Gadget',type:'map',path:[1,1],inherit:'iface',c:[{name:'Name',type:'string',id:'s1',width:100}]}," +
            "{name:'Gadget Peer',title:'Peers',type:'map',path:[1,2],c:[{name:'Name',type:'string',id:'s2',width:100},{name:'Public Key',type:'string',id:'s3',width:160}]}]}]"
        val pack = uiPacks(entries(text), peers, emptyMap()).single()
        assertEquals(listOf("Peers" to "ip/gadget/peer"), pack.window.tabs.map { it.title to it.path })
        assertEquals(listOf("name", "public-key"), pack.paths.getValue("ip/gadget/peer").columns)
    }

    @Test
    fun aListIsATabAndASettingsWindowIsAButtonOfTheTabBeforeItAndAllOthersAreButtonsOfAnEntryThatStartsWithSettings() = runTest {
        val gadgets = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("option settings", "print set add") to listOf("name", "state")),
                "ip/gadget/option" to (members("", "print set add") to listOf("name", "code")),
                "ip/gadget/settings" to (members("", "print set") to listOf("limit", "enabled")),
            ),
        )
        val list = "{name:'Gadgets',title:'Gadgets',type:'map',path:[1,1],c:[{name:'Name',type:'string',id:'s1',width:100},{name:'State',type:'enm',id:'u2',width:60}]}"
        val settings = "{name:'Gadget Settings',title:'Settings',type:'item',path:[1,2],c:[{name:'Limit',type:'number',id:'u1'},{name:'Enabled',type:'bool',id:'b2'}]}"
        val options = "{name:'Gadget Option',title:'Options',type:'map',path:[1,5],c:[{name:'Name',type:'string',id:'s1',width:80},{name:'Code',type:'number',id:'u1',width:50}]}"
        val listFirst = uiPacks(entries("[{name:'Gadgets',group:'IP',c:[$list,$settings,$options]}]"), gadgets, emptyMap()).single()
        assertEquals(listOf("Gadgets" to "ip/gadget", "Options" to "ip/gadget/option"), listFirst.window.tabs.map { it.title to it.path })
        assertEquals(listOf("Settings" to "ip/gadget/settings"), listFirst.paths.getValue("ip/gadget").links.map { it.title to it.path })
        val settingsFirst = uiPacks(entries("[{name:'Gadgets',group:'IP',c:[${settings.replace("[1,2]", "[1,1]").replace("Limit", "Name").replace("Enabled", "State")},$options]}]"), gadgets, emptyMap()).single()
        assertEquals(listOf("Settings" to "ip/gadget"), settingsFirst.window.tabs.map { it.title to it.path })
        assertEquals(listOf("Options" to "ip/gadget/option"), settingsFirst.paths.getValue("ip/gadget").links.map { it.title to it.path })
    }

    @Test
    fun aPropertyInsideAGroupOfTheRouterIsTheFieldOfThatNameInTheTabOfTheGroupOrTheOnlyOne() = runTest {
        val wifi = Router(
            mapOf(
                "" to (members("interface") to emptyList()),
                "interface" to (members("wifi") to emptyList()),
                "interface/wifi" to (
                    members("", "print set add") to
                        listOf("name", "channel.band", "channel.frequency", "security.encryption", "security.passphrase", "security.ft-reassociation-deadline", "interworking.encryption")
                    ),
            ),
        )
        val text = "[{name:'WiFi',c:[{name:'WiFi',title:'WiFi',type:'map',path:[1,1],c:[{name:'General',type:'tab'},{name:'Name',type:'string',id:'s1'}," +
            "{name:'Channel',type:'tab'},{name:'Band',type:'opt',c:[{type:'enm',id:'u1'}]},{name:'Frequency',type:'number',id:'u2'}," +
            "{name:'Security',type:'tab'},{name:'Passphrase',type:'secret',id:'s3'},{name:'Encryption',type:'set',id:'u4'},{name:'FT Reassoc. Deadline',type:'interval',id:'u5'}," +
            "{name:'Interworking',type:'tab'},{name:'Encryption',type:'set',id:'u6'}]}]}]"
        val pack = uiPacks(entries(text), wifi, emptyMap()).single()
        val tabs = pack.forms.getValue("interface/wifi").tabs
        assertEquals(
            listOf(
                "General" to listOf("name"),
                "Channel" to listOf("channel.band", "channel.frequency"),
                "Security" to listOf("security.passphrase", "security.encryption", "security.ft-reassociation-deadline"),
                "Interworking" to listOf("interworking.encryption"),
            ),
            tabs.map { it.title to it.fields },
        )
    }

    @Test
    fun aWindowThatFitsNothingOfTheMenuOfTheEntryFindsTheMenuBesideItAndOneThatFitsNoMenuGetsNoPack() = runTest {
        val beside = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget pool") to emptyList()),
                "ip/gadget" to (members("", "print set add") to listOf("name", "use-peer-dns", "address")),
                "ip/pool" to (members("", "print set add") to listOf("name", "ranges")),
            ),
        )
        val known = mapOf("ip/gadgets" to "ip/pool")
        val window = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[1,1],c:[" +
            "{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Use Peer DNS',type:'bool',id:'b3',width:45},{name:'IP Address',type:'network',id:'u6',width:80}]}]}]"
        val pack = uiPacks(entries(window), beside, known).single()
        assertEquals(listOf("Gadgets" to "ip/gadget"), pack.window.tabs.map { it.title to it.path })
        assertEquals(listOf("name", "use-peer-dns", "address"), pack.paths.getValue("ip/gadget").columns)
        val strangers = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[1,1],c:[" +
            "{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Alpha',type:'bool',id:'b3',width:45},{name:'Beta',type:'network',id:'u6',width:80},{name:'Gamma',type:'string',id:'s7',width:80}]}]}]"
        assertEquals(emptyList(), uiPacks(entries(strangers), beside, known))
    }

    @Test
    fun theFlagsAndOtherMenusOfEveryElementAreNotTakenForFieldsOfAWindow() = runTest {
        val pack = uiPacks(entries(gadgets), router, emptyMap()).single()
        val fields = pack.forms.getValue("ip/gadget").tabs.flatMap { it.fields }
        assertTrue("disabled" !in fields && "dynamic" !in fields, fields.toString())
    }

    @Test
    fun aSectionAndAnEntryNamedByTheMenuItHoldsFindTheirMenus() = runTest {
        val text = "[{name:'CMR Devices',dname:'Devices',section:'Controller',c:[{name:'Devices',title:'Devices',type:'map',path:[167,1],c:[" +
            "{name:'Identity',type:'string',id:'s9',width:100},{name:'Board',type:'string',id:'s6',ro:1,width:120},{name:'Version',type:'string',id:'s5',ro:1,width:70}]}]}," +
            "{name:'CMR Alerts',dname:'Alerts',section:'Controller',c:[{name:'Alerts',title:'Alerts',type:'map',path:[167,5],c:[{name:'Name',type:'string',id:'sfe0010',width:100}]}]}," +
            "{name:'CMR Settings',dname:'Settings',section:'Controller',c:[{title:'Settings',type:'item',path:[167,2],c:[{name:'Enabled',type:'bool',id:'b1'},{name:'Pairing Requirement',type:'enm',id:'u2301'}]}]}," +
            "{name:'CMR Dashboard',dname:'Dashboard',section:'Controller',c:[{name:'Dashboard',title:'Dashboard',type:'item',path:[167,1],c:[]}]}]"
        val packs = uiPacks(entries(text), router, emptyMap())
        assertEquals(
            listOf(listOf("Controller", "Devices") to "cmr/device", listOf("Controller", "Alerts") to "cmr/alert", listOf("Controller", "Settings") to "cmr"),
            packs.map { it.menu to it.window.tabs.first().path },
        )
        assertEquals(listOf("identity", "board", "version"), packs[0].paths.getValue("cmr/device").columns)
        assertEquals(listOf("enabled", "pairing-requirement"), packs[2].forms.getValue("cmr").tabs.single().fields)
    }

    @Test
    fun aPathTheBundledMenuKnowsIsTakenFromIt() = runTest {
        val text = "[{name:'Users',group:'System',c:[{name:'Users',title:'Users',type:'map',path:[9,1],c:[{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Group',type:'enm',id:'u2',width:80}]}]}]"
        assertEquals(emptyList(), uiPacks(entries(text), router, emptyMap()))
        val known = uiKnownPaths(listOf(MenuEntry("System", items = listOf(MenuEntry("Users", "user")))), emptyList())
        assertEquals("user", uiPacks(entries(text), router, known).single().window.tabs.single().path)
    }

    @Test
    fun aWindowThatStandsForSeveralKindsAndAnEntryOfNoMenuGetNoPack() = runTest {
        val text = "[{name:'Gadgets',group:'IP',c:[{name:'Interface',title:'Gadget',type:'map',path:[1,1],generic:'iface',typeon:'type',c:[{name:'Name',type:'string',id:'s1',width:10}]}]}," +
            "{name:'Nothing',group:'IP',c:[{name:'Nothing',title:'Nothing',type:'map',path:[2,1],c:[{name:'Name',type:'string',id:'s1',width:10}]}]}]"
        assertEquals(emptyList(), uiPacks(entries(text), router, emptyMap()))
    }

    @Test
    fun anEntryThatDoesNotFitIsLeftOutAndTheOthersStayButALostConnectionIsNot() = runTest {
        val users = "[{name:'Users',group:'System',c:[{name:'Users',title:'Users',type:'map',path:[9,1],c:[{name:'Name',type:'string',id:'sfe0010',width:100},{name:'Group',type:'enm',id:'u2',width:80}]}]}]"
        val known = uiKnownPaths(listOf(MenuEntry("System", items = listOf(MenuEntry("Users", "user")))), emptyList())
        val both = entries(gadgets) + entries(users)
        val odd = object : UiSchema {
            override suspend fun members(path: MenuPath) = router.members(path)

            override suspend fun properties(path: MenuPath) = if (path.toString() == "ip/gadget") error("odd") else router.properties(path)
        }
        assertEquals(listOf(listOf("System", "Users")), uiPacks(both, odd, known).map { it.menu })
        val lost = object : UiSchema {
            override suspend fun members(path: MenuPath) = router.members(path)

            override suspend fun properties(path: MenuPath): List<String> = throw DeviceException(ErrorCategory.ConnectionLost, "lost")
        }
        assertFailsWith<DeviceException> { uiPacks(both, lost, known) }
    }

    @Test
    fun theFlagsAndTheToolsOfAListBecomeTheLettersOfItsRowsItsActionsAndItsLinks() = runTest {
        val tools = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("", "print set add reboot pair run-script") to listOf("name", "state", "controller", "upgrade-available", "disabled")),
            ),
        )
        val text = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[7,1],c:[{type:'enable'},{name:'controller',type:'flag',id:'b1',hint:'L'}," +
            "{name:'upgrade available',type:'flag',id:'b2',hint:'U'},{name:'Name',type:'string',id:'sfe0010',width:100},{name:'State',type:'string',id:'s3',ro:1,width:80}]}," +
            "{title:'Reboot Tool',type:'doit',path:[7,1],cmd:2,confirm:'Reboot?'},{title:'Pair Tool',type:'query',path:[7,1]},{title:'Script Tool',type:'query',path:[7,1]}," +
            "{title:'Hidden Tool',type:'doit',path:[7,1],hide:1},{title:'Elsewhere Tool',type:'doit',path:[7,2]}]}]"
        val path = uiPacks(entries(text), tools, emptyMap()).single().paths.getValue("ip/gadget")
        assertEquals(listOf("disabled" to "X", "controller" to "L", "upgrade-available" to "U"), path.flags.map { it.property to it.letter })
        assertEquals(listOf("reboot" to true), path.actions.map { it.command to it.confirm })
        assertEquals(listOf("Pair Tool" to "ip/gadget/pair", "Script Tool" to "ip/gadget/run-script"), path.links.map { it.title to it.path })
    }

    @Test
    fun aButtonThatOpensAnotherEntryIsALinkToTheWindowOfThatEntry() = runTest {
        val controller = Router(
            mapOf(
                "" to (members("cmr") to emptyList()),
                "cmr" to (members("device upgrade", "print set") to emptyList()),
                "cmr/device" to (members("", "print set add") to listOf("identity")),
                "cmr/upgrade" to (members("", "print set add") to listOf("name")),
            ),
        )
        val text = "[{name:'CMR Devices',dname:'Devices',section:'Controller',c:[{name:'Devices',title:'Devices',type:'map',path:[167,1],c:[{name:'Identity',type:'string',id:'s9',width:100}," +
            "{name:'Upgrade Rules',type:'contextmenu',globalbutton:1,open:'CMR Upgrades',link:[]},{name:'Pair',type:'contextmenu',open:'CMR Devices',tab:'Pair'}]}]}," +
            "{name:'CMR Upgrades',dname:'Upgrades',section:'Controller',c:[{name:'Upgrades',title:'Upgrades',type:'map',path:[167,9],c:[{name:'Name',type:'string',id:'s1',width:100}]}]}]"
        val packs = uiPacks(entries(text), controller, emptyMap())
        assertEquals(listOf("Upgrade Rules" to "cmr/upgrade"), packs.first().paths.getValue("cmr/device").links.map { it.title to it.path })
        assertEquals(emptyList(), packs.last().paths.getValue("cmr/upgrade").links)
    }

    @Test
    fun aHiddenWindowGivesItsDialogItsLayoutAndNoTab() = runTest {
        val update = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("update", "print set add") to listOf("name")),
                "ip/gadget/update" to (members("", "print set") to listOf("channel", "mode", "installed-version", "latest-version", "status")),
            ),
        )
        val text = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[7,1],c:[{name:'Name',type:'string',id:'sfe0010',width:100}]}," +
            "{title:'Check For Updates',type:'item',path:[7,2],hide:1,c:[{type:'autoset',c:[{name:'Channel',type:'enm',id:'u1'}]},{type:'autoset',c:[{name:'Mode',type:'enm',id:'u2'}]},{type:'separator'}," +
            "{name:'Installed Version',type:'string',id:'s1',ro:1},{name:'Latest Version',type:'string',id:'s2',ro:1}]}]}]"
        val pack = uiPacks(entries(text), update, emptyMap()).single()
        assertEquals(listOf("ip/gadget"), pack.window.tabs.map { it.path })
        val form = pack.forms.getValue("ip/gadget/update")
        assertEquals(listOf("General" to listOf("channel", "mode", "-", "installed-version", "latest-version")), form.tabs.map { it.title to it.fields })
        assertEquals(listOf("channel", "mode"), pack.paths.getValue("ip/gadget/update").autoset)
        assertNull(pack.paths.getValue("ip/gadget/update").native)
    }

    @Test
    fun theTextBoxWithoutATitleOfAWindowOfSettingsIsReadFromTheRouterAsTheChangelogAndStandsAtTheEndOfTheForm() = runTest {
        val update = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("gadget") to emptyList()),
                "ip/gadget" to (members("update", "print set add") to listOf("name")),
                "ip/gadget/update" to (members("", "print set") to listOf("channel", "installed-version", "latest-version", "status")),
            ),
        )
        val text = "[{name:'Gadgets',group:'IP',c:[{name:'Gadgets',title:'Gadgets',type:'map',path:[7,1],c:[{name:'Name',type:'string',id:'sfe0010',width:100}]}," +
            "{title:'Check For Updates',type:'item',path:[7,2],hide:1,c:[{type:'autoset',c:[{name:'Channel',type:'enm',id:'ua'}]},{type:'separator'}," +
            "{name:'Installed Version',type:'string',id:'s1',ro:1},{name:'Latest Version',type:'string',id:'s2',ro:1},{type:'separator'}," +
            "{name:'',type:'multilinestring',id:'s4',cols:40,hold:1,lines:25,ro:1},{type:'statusbar',c:[{type:'string',id:'s3'}]}]}]}]"
        val path = uiPacks(entries(text), update, emptyMap(), natively = true).single().let { it.paths.getValue("ip/gadget/update") to it.forms.getValue("ip/gadget/update") }
        val native = assertNotNull(path.first.native)
        assertEquals(listOf(7L, 2L), native.handler)
        assertTrue(native.single)
        assertEquals(listOf("changelog" to 4), native.fields.map { it.name to it.key })
        assertEquals("multilinestring", native.fields.single().kind)
        assertEquals(listOf("channel", "-", "installed-version", "latest-version", "-", "changelog"), path.second.tabs.single().fields)
        assertEquals(listOf("channel"), path.first.autoset)
        assertEquals("", path.first.labels.getValue("changelog"))
    }

    private val controller = Router(
        mapOf(
            "" to (members("cmr") to emptyList()),
            "cmr" to (members("device", "print set get") to emptyList()),
            "cmr/device" to (members("", "print set get") to listOf("identity", "address", "board")),
        ),
    )

    private val devices = "{name:'Devices',title:'Devices',type:'map',path:[167,1],nameval:{type:'concat',first:'Identity',second:'Address',sep:'@'},c:[" +
        "{name:'Identity',type:'string',id:'s2009',width:100},{name:'Address',type:'string',id:'s200a',ro:1,width:100},{name:'Board',type:'string',id:'s6',ro:1,width:120}," +
        "{name:'Pairing Status',type:'string',id:'s211c',ro:1,width:80},{name:'Connected Time',type:'interval',id:'u2118',ro:1,width:80}," +
        "{name:'Channel',type:'enm',id:'u2503',ro:1,width:70,values:{type:'static',map:{'2':'long term','3':'stable'}}},{name:'Comment Template',type:'addr',id:'m2510',ro:1}," +
        "{name:'Active Alerts',type:'multinumber',id:'U2200',ro:1,c:[{type:'enm',values:{type:'dynamic',path:[167,5]}}]}]}"

    private val resources = "{name:'Resources',title:'Resources',type:'map',path:[167,44],ro:1,c:[" +
        "{name:'Device',type:'enm',id:'ufe0001',ro:1,width:150,values:{type:'dynamic',path:[167,1]}},{name:'CPU',type:'number',id:'u202',ro:1,width:60,postfix:'%'}," +
        "{name:'Memory',type:'bignumber',id:'q204',ro:1,width:60,postfix:'%'},{name:'HDD',type:'bignumber',id:'q206',ro:1,width:60,postfix:'%'}]}"

    private fun controllerEntry(devices: String = this.devices, resources: String = this.resources) =
        entries("[{name:'CMR Dashboard',dname:'Dashboard',section:'Controller',c:[{name:'Dashboard',title:'Dashboard',type:'item',path:[167,1],ro:1,c:[]}]}," +
            "{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices,$resources]}," +
            "{name:'CMR Alerts',dname:'Alerts',section:'Controller',c:[{name:'Rules',title:'Rules',type:'map',path:[167,5],maxobjs:50,nameval:'Name',c:[{name:'Name',type:'string',id:'sfe0010',width:100}]}]}]")

    @Test
    fun theFieldsOfAListTheConsoleDoesNotPrintAreReadByTheNativeChannelAndNamedAfterTheirTitles() = runTest {
        val pack = uiPacks(controllerEntry(), controller, emptyMap(), natively = true).single()
        val path = pack.paths.getValue("cmr/device")
        val native = assertNotNull(path.native)
        assertEquals(listOf(167L, 1L), native.handler)
        assertEquals(listOf("pairing-status", "connected-time", "channel", "comment-template", "active-alerts"), native.fields.map { it.name })
        assertEquals(listOf(0x211c, 0x2118, 0x2503, 0x2510, 0x2200), native.fields.map { it.key })
        assertEquals(listOf("string", "interval", "enm", "addr", "multinumber"), native.fields.map { it.kind })
        assertEquals(PackNativeRef(listOf(20, 0), listOf(0x10006)), native.fields[3].refers)
        assertEquals(mapOf(2L to "long term", 3L to "stable"), native.fields[2].names)
        assertEquals(PackNativeRef(listOf(167, 5), listOf(0xFE0010)), native.fields.last().refers)
        assertEquals(listOf("identity", "address", "board", "pairing-status", "connected-time", "channel"), path.columns)
        assertEquals("Pairing Status", path.labels["pairing-status"])
        assertTrue("pairing-status" in path.readonly)
    }

    @Test
    fun aListTheConsoleHasNoMenuForIsATabOfItsOwnWithTheDevicesNamedByTheirIdentity() = runTest {
        val pack = uiPacks(controllerEntry(), controller, emptyMap(), natively = true).single()
        assertEquals(listOf("Devices" to "cmr/device", "Resources" to "native/167/44"), pack.window.tabs.map { it.title to it.path })
        val native = assertNotNull(pack.paths.getValue("native/167/44").native)
        assertEquals(listOf("device", "cpu", "memory", "hdd"), native.fields.map { it.name })
        assertEquals(listOf(0xFE0001, 0x202, 0x204, 0x206), native.fields.map { it.key })
        assertEquals(PackNativeRef(listOf(167, 1), listOf(0x2009, 0x200a), "@"), native.fields.first().refers)
        assertEquals(listOf("%", "%", "%"), native.fields.drop(1).map { it.postfix })
        assertEquals(listOf("device", "cpu", "memory", "hdd"), pack.paths.getValue("native/167/44").columns)
    }

    private val ports = "{name:'CMR Ports',dname:'Ports',section:'Controller',c:[{name:'Ports',title:'Ports',type:'map',path:[167,32],ro:1,c:[" +
        "{name:'running',type:'flag',id:'b8102',hint:'R'},{name:'Device',type:'enm',id:'u8110',width:200,values:{type:'dynamic',path:[167,1]}}," +
        "{name:'Interface',type:'string',id:'s8100',width:100},{name:'Rate',type:'enm',id:'u8107',width:100,values:{type:'static',map:['Unknown','10Mbps','100Mbps']}}," +
        "{name:'Bandwidth',type:'tuple',separate:1,c:[{name:'Tx',type:'bigbitrate',id:'q8104',width:100},{name:'Rx',type:'bigbitrate',id:'q8103',width:100}]}," +
        "{name:'Last Hour (In/Out)',type:'tuple',width:160,c:[{name:'',type:'bigbytes',id:'q83'},{name:'',type:'bigbytes',id:'q82'}]}]}]}"

    private fun portsEntry(ports: String = this.ports) = entries("[$ports,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]")

    @Test
    fun anEntryWithoutAMenuOfItsOwnIsReadFromTheObjectModelWhereTheRouterDoesNotHideItBehindACondition() = runTest {
        val pack = uiPacks(portsEntry(), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Ports") }
        assertEquals(listOf("Ports" to "native/167/32"), pack.window.tabs.map { it.title to it.path })
        val path = pack.paths.getValue("native/167/32")
        assertEquals(listOf("device", "interface", "rate", "bandwidth-tx", "bandwidth-rx", "last-hour-in", "last-hour-out"), path.columns)
        assertEquals(listOf("bandwidth-tx", "bandwidth-rx"), path.bits)
        assertEquals(listOf("last-hour-in", "last-hour-out"), path.bytes)
        assertEquals(listOf("running" to "R"), path.flags.map { it.property to it.letter })
        val native = assertNotNull(path.native)
        assertEquals(listOf(167L, 32L), native.handler)
        assertEquals(listOf(0x8110, 0x8100, 0x8107, 0x8104, 0x8103, 0x83, 0x82, 0x8102), native.fields.map { it.key })
        assertEquals(PackNativeRef(listOf(167, 1), listOf(0x2009, 0x200a), "@"), native.fields.first().refers)
        assertEquals("Bandwidth Tx", path.labels["bandwidth-tx"])
        assertTrue(uiPacks(portsEntry(), controller, emptyMap(), natively = false).none { it.menu.last() == "Ports" })
        val hidden = ports.replace("section:'Controller',", "section:'Controller',pred:{type:'syscap',value:34},")
        assertTrue(uiPacks(portsEntry(hidden), controller, emptyMap(), natively = true).none { it.menu.last() == "Ports" })
    }

    private val rules = "{name:'CMR Rules',dname:'Rules',section:'Controller',c:[{name:'Rules',title:'Rules',type:'map',path:[167,60],ro:1,c:[" +
        "{name:'Src. Address',type:'opt',id:'b1a2',c:[{type:'not',id:'bc8',c:[{type:'network',id:'u32',maskid:'u33',range:1}]}]}," +
        "{name:'Address',type:'network',id:'u1',maskid:'u3'},{name:'Prefix',type:'network6',id:'a5',maskid:'u6'}," +
        "{name:'States',type:'set',id:'u9',values:{type:'static',map:{'0':'new','1':'related'}}}," +
        "{name:'Mode',type:'enm',id:'bd',values:{type:'enumfilter',filters:[{id:0}],values:{type:'defenum',defid:0,defname:'none',values:{type:'static',map:{'1':'on'}}}}}," +
        "{name:'Counters',type:'multibignumber',id:'Q7',c:[{type:'bignumber'}]}," +
        "{name:'Code',type:'numberrange',id:'u40',highid:'u41',deflow:0,defhigh:255},{name:'Rate',type:'bigunit',id:'q50',postfix:'bps'}," +
        "{name:'Speed (Tx/Rx)',type:'tuple',postfix:'bits/s',c:[{name:'',type:'bigunit',id:'q51'},{name:'',type:'bigunit',id:'q52'}]}," +
        "{name:'Server',type:'ipaddrandport',id:'u60',portid:'u61'},{name:'Owner',type:'hyperlink',id:'u70',path:[167,1]}," +
        "{name:'Hardware',type:'macnetwork',id:'r80',maskid:'r81'},{name:'Tcp Flags',type:'multitristate',id:'u90',maskid:'u91',values:{type:'static',map:{'1':'syn'}}}," +
        "{name:'Target',type:'union',single:1,c:[{type:'network',id:'u3',maskid:'u4',range:1},{type:'string',id:'s8'}]}," +
        "{name:'Old File',type:'string',id:'s1',values:{type:'dynamic',path:[72]}}]}]}"

    @Test
    fun anOptionalFieldIsReadAtTheKeyOfItsValueWithTheFlagOfItsWrapperAndTheMaskOfANetworkIsKept() = runTest {
        val pack = uiPacks(entries("[$rules,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Rules") }
        val fields = assertNotNull(pack.paths.getValue("native/167/60").native).fields.associateBy { it.name }
        val source = fields.getValue("src-address")
        assertEquals(listOf(0x32, 0x1a2, 0x33, true, "u", 0xc8), listOf(source.key, source.flag, source.pair, source.range, source.letter, source.negation))
        assertEquals("network", source.kind)
        val address = fields.getValue("address")
        assertEquals(listOf(1, null, 3, false), listOf(address.key, address.flag, address.pair, address.range))
        assertEquals(listOf("network6", "a", 6), fields.getValue("prefix").let { listOf(it.kind, it.letter, it.pair) })
        assertEquals(mapOf(0L to "new", 1L to "related"), fields.getValue("states").names)
        assertEquals(mapOf(1L to "on", 0L to "none"), fields.getValue("mode").names)
        assertEquals(listOf("multibignumber", "Q", 7), fields.getValue("counters").let { listOf(it.kind, it.letter, it.key) })
        assertEquals(listOf(0x40, 0x41), fields.getValue("code").let { listOf(it.key, it.pair) })
        assertEquals("bps", fields.getValue("rate").postfix)
        assertEquals(listOf("bits/s", "bits/s"), listOf(fields.getValue("speed-tx").postfix, fields.getValue("speed-rx").postfix))
        assertEquals(0x61, fields.getValue("server").pair)
        assertEquals(PackNativeRef(listOf(167, 1), listOf(0x2009, 0x200a), "@"), fields.getValue("owner").refers)
        assertEquals(0x81, fields.getValue("hardware").pair)
        assertEquals(listOf(0x91, mapOf(1L to "syn")), fields.getValue("tcp-flags").let { listOf(it.pair, it.names) })
        val target = fields.getValue("target")
        assertEquals(listOf("union", 3), listOf(target.kind, target.key))
        assertEquals(listOf("network" to 3, "string" to 8), target.members.map { it.kind to it.key })
        assertEquals(listOf("string", 1, null), fields.getValue("old-file").let { listOf(it.kind, it.key, it.refers) })
    }

    @Test
    fun theUnitOfATupleDoesNotReachItsPartsThatCountInBits() = runTest {
        val bits = "{name:'CMR Rules',dname:'Rules',section:'Controller',c:[{name:'Rules',title:'Rules',type:'map',path:[167,60],ro:1,c:[" +
            "{name:'Rate (Tx/Rx)',type:'tuple',postfix:'bps',c:[{name:'',type:'bigbitrate',id:'q51'},{name:'',type:'bigbitrate',id:'q52'}]}]}]}"
        val pack = uiPacks(entries("[$bits]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Rules") }
        assertEquals(listOf(null, null), assertNotNull(pack.paths.getValue("native/167/60").native).fields.map { it.postfix })
    }

    @Test
    fun theColumnsOfSizesAndOfBigNumbersAreNamedSoThatATableWritesThemAsWinBoxDoes() = runTest {
        val sizes = "{name:'CMR Sizes',dname:'Sizes',section:'Controller',c:[{name:'Sizes',title:'Sizes',type:'map',path:[167,61],ro:1,c:[" +
            "{name:'Free',type:'kbytes',id:'u1'},{name:'Total',type:'bigbytes',id:'q2'},{name:'Packets',type:'bignumber',id:'q3'},{name:'Load',type:'decimal',id:'u4'},{name:'Count',type:'number',id:'u5'}]}]}"
        val path = uiPacks(entries("[$sizes]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Sizes") }.paths.getValue("native/167/61")
        assertEquals(listOf("total"), path.bytes)
        assertEquals(listOf("free"), path.kibi)
        assertEquals(listOf("packets", "load"), path.groups)
    }

    private val lists = "{name:'CMR Rules',dname:'Rules',section:'Controller',c:[{name:'Rules',title:'Rules',type:'map',path:[167,60],ro:1,c:[" +
        "{name:'Status',type:'numflag',id:'u5',c:{0:[ 'stopped','S' ],2:[ 'running','R' ]}},{name:'Source',type:'script',id:'s11'},{name:'Log',type:'multilinestring',id:'s12',lines:4}," +
        "{name:'Local AS',type:'as',id:'u13'},{name:'LBT',type:'group',id:'b14',c:[{name:'Listen Time',type:'number',id:'u15'}]}," +
        "{name:'Ranges',type:'multinumberrange',id:'U16',c:[{type:'numberrange',max:599,min:100}]},{name:'Communities',type:'multinumberrange',id:'U17',c:[{type:'community'}]}," +
        "{name:'Pools',type:'multinetwork',id:'U18',min:1,c:[{type:'network',range:1}]},{name:'DNS',type:'multi',id:'Mf',c:[{type:'addr'}]}," +
        "{name:'Mon',type:'multinumberrange',id:'U1b',c:[{type:'tuple',sep:'-',c:[{type:'interval'},{type:'interval'}]}]}," +
        "{name:'Routes',type:'multi',id:'M93',optid:'b94',c:[{type:'tuple',sep:'dist.',separate:1,c:[{type:'union',c:[{type:'enm',id:'u16',values:{type:'dynamic',path:[167,1]}}," +
        "{type:'enm',id:'u17',values:{type:'static',map:{4294967295:'default'}}}]},{type:'opt',c:[{type:'number',id:'uf'}]}]}]}," +
        "{name:'Secrets',type:'multi',id:'M2b',c:[{type:'tuple',sep:'=',c:[{type:'string',id:'s1'},{type:'secret',id:'s2'}]}]}]}]}"

    @Test
    fun theKindsThatHoldFlagsListsAndGroupsAreReadWithTheirElementsAndLetters() = runTest {
        val pack = uiPacks(entries("[$lists,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Rules") }
        val fields = assertNotNull(pack.paths.getValue("native/167/60").native).fields.associateBy { it.name }
        assertEquals(listOf("numflag", mapOf(0L to "S", 2L to "R")), fields.getValue("status").let { listOf(it.kind, it.names) })
        assertEquals(listOf("script", "multilinestring", "as"), listOf("source", "log", "local-as").map { fields.getValue(it).kind })
        assertEquals(listOf("group", 0x14, "number", 0x15), listOf(fields.getValue("lbt").kind, fields.getValue("lbt").key, fields.getValue("listen-time").kind, fields.getValue("listen-time").key))
        assertEquals(listOf("multinumberrange", "U"), fields.getValue("ranges").let { listOf(it.kind, it.letter) })
        assertEquals(listOf("multicommunity", "U", 0x17), fields.getValue("communities").let { listOf(it.kind, it.letter, it.key) })
        assertEquals(listOf("multiintervalrange", "-", 0x1b), fields.getValue("mon").let { listOf(it.kind, it.separator, it.key) })
        assertEquals(listOf("multinetwork", true), fields.getValue("pools").let { listOf(it.kind, it.range) })
        val dns = fields.getValue("dns")
        assertEquals(listOf("multi", 0xf, "M", "addr", NATIVE_NO_KEY), listOf(dns.kind, dns.key, dns.letter, dns.parts.single().kind, dns.parts.single().key))
        assertEquals(PackNativeRef(listOf(20, 0), listOf(0x10006)), dns.parts.single().refers)
        val routes = fields.getValue("routes")
        assertEquals(0x94, routes.flag)
        val tuple = routes.parts.single()
        assertEquals(listOf("tuple", "dist.", NATIVE_NO_KEY), listOf(tuple.kind, tuple.separator, tuple.key))
        assertEquals(listOf("union", "number"), tuple.parts.map { it.kind })
        assertEquals(listOf(0x16, 0x17), tuple.parts.first().members.map { it.key })
        assertEquals(PackNativeRef(listOf(167, 1), listOf(0x2009, 0x200a), "@"), tuple.parts.first().members.first().refers)
        assertNull(fields["secrets"])
    }

    private val about = "{name:'Patch About',title:'Patch About',c:[{title:'Patch About',type:'item',path:[24,2],ro:1,c:[{type:'gridcell'},{name:'Patch',type:'tab',inline:1}," +
        "{name:'Author',type:'string',def:'someone'},{name:'System',type:'tab',inline:1},{name:'Uptime',type:'interval',id:'u1'},{name:'Board Name',type:'string',id:'s2c'}," +
        "{name:'Version',type:'version',id:'s16'}]}]}"

    private fun aboutEntry(about: String = this.about) = entries("[$about,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]")

    @Test
    fun aReadOnlyWindowOfSettingsWithoutAMenuInTheConsoleIsOneRecordOfTheObjectModel() = runTest {
        val pack = uiPacks(aboutEntry(), controller, emptyMap(), natively = true).first { it.menu == listOf("Patch About") }
        assertEquals(listOf("Patch About" to "native/24/2"), pack.window.tabs.map { it.title to it.path })
        val path = pack.paths.getValue("native/24/2")
        assertEquals(listOf("uptime", "board-name", "version"), path.columns)
        val native = assertNotNull(path.native)
        assertEquals(listOf(24L, 2L), native.handler)
        assertTrue(native.single)
        assertEquals(listOf(0x1, 0x2c, 0x16), native.fields.map { it.key })
        assertEquals(listOf("interval", "string", "version"), native.fields.map { it.kind })
        assertEquals("Board Name", path.labels["board-name"])
        assertTrue(uiPacks(controllerEntry(), controller, emptyMap(), natively = true).single().paths.values.none { it.native?.single == true })
    }

    @Test
    fun aWindowOfTheObjectModelKeepsTheTabsOfItsDescriptionAsTheFormOfItsRecord() = runTest {
        val pack = uiPacks(aboutEntry(), controller, emptyMap(), natively = true).first { it.menu == listOf("Patch About") }
        assertEquals("native/24/2", pack.paths.getValue("native/24/2").form)
        assertEquals(listOf("System" to listOf("uptime", "board-name", "version")), pack.forms.getValue("native/24/2").tabs.map { it.title to it.fields })
    }

    @Test
    fun aWindowOfSettingsThatNamesACommandOfItsOwnOrAConditionOrIsHiddenIsNotRead() = runTest {
        suspend fun packs(about: String) = uiPacks(aboutEntry(about), controller, emptyMap(), natively = true).filter { it.menu == listOf("Patch About") }
        assertEquals(1, packs(about).size)
        assertEquals(0, packs(about.replace("path:[24,2],", "path:[24,2],getcmd:4,")).size)
        assertEquals(0, packs(about.replace("path:[24,2],", "path:[24,2],pred:{type:'syscap',value:3},")).size)
        assertEquals(0, packs(about.replace("path:[24,2],", "path:[24,2],hide:1,")).size)
        assertEquals(0, uiPacks(aboutEntry(), controller, emptyMap(), natively = false).count { it.menu == listOf("Patch About") })
    }

    @Test
    fun aWindowOfSettingsThatIsNotReadOnlyIsWrittenThroughTheChannelInTheFieldsItsKindsAllow() = runTest {
        suspend fun packs(about: String) = uiPacks(aboutEntry(about), controller, emptyMap(), natively = true).filter { it.menu == listOf("Patch About") }
        val kept = packs(about).single().paths.getValue("native/24/2")
        assertEquals(false, assertNotNull(kept.native).edit)
        assertEquals(listOf("uptime", "board-name", "version"), kept.readonly)
        val path = packs(about.replace("path:[24,2],ro:1,", "path:[24,2],")).single().paths.getValue("native/24/2")
        val native = assertNotNull(path.native)
        assertTrue(native.single && native.edit && !native.add && !native.remove)
        assertEquals(listOf("uptime" to true, "board-name" to true, "version" to false), native.fields.map { it.name to it.writable })
        assertEquals(listOf("version"), path.readonly)
    }

    private val quickSet = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'}," +
        "{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}}," +
        "{type:'cond',name:'router',c:[{on:'Mode',pred:{type:'number',value:0}}]},{type:'cond',name:'bridge',c:[{on:'Mode',pred:{type:'number',value:1}}]}," +
        "{type:'object',on:'router',c:[{name:'IP Address',type:'ipaddr',id:'u2'}]},{type:'object',on:'bridge',c:[{name:'IP Address',type:'ipaddr',id:'u3'}]}]}]}"

    @Test
    fun fieldsOfDifferentModesThatShareATitleAreToldApartAndStandWhereTheirModeIsChosen() = runTest {
        val pack = uiPacks(entries("[$quickSet,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }
        val path = pack.paths.getValue("native/120/6")
        val native = assertNotNull(path.native)
        assertEquals(listOf("mode", "ip-address", "ip-address-2"), native.fields.map { it.name })
        assertEquals(listOf(0x1, 0x2, 0x3), native.fields.map { it.key })
        assertEquals(listOf(emptyList(), listOf(mapOf("mode" to "Router")), listOf(mapOf("mode" to "Bridge"))), native.fields.map { it.depends })
        assertEquals(listOf("Mode", "IP Address", "IP Address"), native.fields.map { path.labels.getValue(it.name) })
        assertEquals(listOf("mode", "ip-address", "ip-address-2"), pack.forms.getValue("native/120/6").tabs.single().fields)
    }

    @Test
    fun aFieldThatAConditionAsksAboutIsReadThoughNoFormShowsIt() = runTest {
        val hidden = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'}," +
            "{name:'spare',type:'bool',id:'b51',nonpublic:1,ro:1},{name:'Channel',type:'enm',id:'u52',on:'hasspare',values:{type:'static',map:['Eth1','SFP1']}}," +
            "{type:'cond',name:'hasspare',c:[{on:'spare',pred:{type:'bool',value:1}}]}]}]}"
        val pack = uiPacks(entries("[$hidden,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }
        val native = assertNotNull(pack.paths.getValue("native/120/6").native)
        assertEquals(listOf("channel", ".spare"), native.fields.map { it.name })
        assertEquals(listOf(listOf(mapOf(".spare" to "true")), emptyList()), native.fields.map { it.depends })
        assertEquals(listOf("channel"), pack.forms.getValue("native/120/6").tabs.single().fields)
    }

    @Test
    fun aColumnThatNamesAFieldByItsTitleStaysWithTheFirstFieldOfThatTitle() = runTest {
        val rules = "{name:'CMR Rules',dname:'Rules',section:'Controller',c:[{name:'Rules',title:'Rules',type:'map',path:[167,62],ro:1,c:[" +
            "{name:'General',type:'tab'},{name:'Action',type:'enm',id:'u1',values:{type:'static',map:['accept','drop']}},{name:'Time',type:'tab'}," +
            "{name:'Action',type:'enm',id:'u2',values:{type:'static',map:['accept','drop']}},{type:'columnalias',name:'Action'}]}]}"
        val path = uiPacks(entries("[$rules]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Controller", "Rules") }.paths.getValue("native/167/62")
        val native = assertNotNull(path.native)
        assertEquals(listOf("action"), path.columns)
        assertEquals(listOf("action", "action-2"), native.fields.map { it.name })
        assertEquals(listOf("Action", "Action"), native.fields.map { path.labels.getValue(it.name) })
    }

    @Test
    fun theButtonsOfAWindowOfTheObjectModelAreActionsThatStandWhereTheirFieldsDo() = runTest {
        val buttons = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'}," +
            "{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}},{type:'cond',name:'router',c:[{on:'Mode',pred:{type:'number',value:0}}]}," +
            "{name:'Address',type:'tuple',on:'router',c:[{type:'ipaddr',id:'u30',ro:1},{type:'toolbar',c:[{name:'Renew',type:'doit',cmd:11}]}]}," +
            "{type:'toolbar',c:[{name:'Reconnect',type:'doit',cmd:13,confirm:'Sure?'}]}]}]}"
        val path = uiPacks(entries("[$buttons,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6")
        assertEquals(listOf(11, 13), assertNotNull(path.native).doits)
        assertEquals(listOf("Renew" to "11", "Reconnect" to "13"), path.actions.map { it.title to it.command })
        assertEquals(listOf(listOf(mapOf("mode" to "Router")), emptyList()), path.actions.map { it.depends })
        assertEquals(listOf(false, true), path.actions.map { it.confirm })
        assertEquals(listOf(listOf("General"), listOf("General")), path.actions.map { it.tabs })
        assertEquals(listOf("address" to null, null to "address"), path.actions.map { it.beside to it.below })
    }

    @Test
    fun aCommandThatStandsInTwoPlacesIsOneButtonInEachOfThem() = runTest {
        val twice = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'}," +
            "{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}}," +
            "{type:'cond',name:'router',c:[{on:'Mode',pred:{type:'number',value:0}}]},{type:'cond',name:'bridge',c:[{on:'Mode',pred:{type:'number',value:1}}]}," +
            "{name:'Internet',type:'tab',inline:1,on:'router'},{type:'object',on:'router',c:[{name:'IP Address',type:'tuple',c:[{type:'ipaddr',id:'u30',ro:1},{type:'toolbar',c:[{name:'Renew',type:'doit',cmd:11}]}]}]}," +
            "{name:'Bridge',type:'tab',inline:1,on:'bridge'},{type:'object',on:'bridge',c:[{name:'IP Address',type:'tuple',c:[{type:'ipaddr',id:'u31',ro:1},{type:'toolbar',c:[{name:'Renew',type:'doit',cmd:11}]}]}]}]}]}"
        val path = uiPacks(entries("[$twice,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6")
        assertEquals(listOf("ip-address", "ip-address-2"), path.actions.map { it.beside })
        assertEquals(listOf(listOf("Internet"), listOf("Bridge")), path.actions.map { it.tabs })
    }

    @Test
    fun theSectionsOfAWindowAreTheInlineTabsOfItsDescriptionAndItsButtonsUnderAFieldKeepThatField() = runTest {
        val settings = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'},{name:'Configuration',type:'tab',inline:1}," +
            "{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}},{name:'System',type:'tab',inline:1},{name:'Identity',type:'string',id:'s5'}," +
            "{type:'toolbar',c:[{name:'Reboot',type:'contextmenu',group:'System',open:'Reboot',tab:'Reboot'}]}]}]}"
        val known = mapOf("system/reboot" to "system/reboot")
        val pack = uiPacks(entries("[$settings,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, known, natively = true).first { it.menu == listOf("Quick Set") }
        assertEquals(listOf("Configuration" to true, "System" to true), pack.forms.getValue("native/120/6").tabs.map { it.title to it.inline })
        assertEquals(listOf(null to "identity"), pack.paths.getValue("native/120/6").links.map { it.beside to it.below })
    }

    @Test
    fun aWindowAnotherWindowOwnsIsAHiddenPackAndTheButtonThatNamesItOpensItWhereItsConditionsHold() = runTest {
        val owned = "{name:'Mapping',nonpublic:1,owner:{title:'Quick Set'},c:[{name:'Mapping',title:'Mapping',type:'map',path:[120,105],nameval:'Description'," +
            "c:[{name:'Description',type:'string',id:'sfe0009',width:150},{name:'Port',type:'number',id:'u2',width:80}]}]}"
        val settings = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'},{name:'NAT',type:'bool',id:'b9'}," +
            "{type:'cond',name:'nat',c:[{on:'NAT',pred:{type:'bool',value:1}}]},{type:'toolbar',on:'nat',c:[{name:'Mapping',type:'contextmenu',open:'Mapping',tab:'Mapping'}]}]}]}"
        val packs = uiPacks(entries("[$settings,$owned,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true)
        val hidden = packs.first { it.menu == listOf("Mapping") }
        assertTrue(hidden.hidden)
        assertEquals(listOf("Mapping" to "native/120/105"), hidden.window.tabs.map { it.title to it.path })
        val link = packs.first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6").links.single()
        assertEquals(listOf("Mapping", "native/120/105", "nat"), listOf(link.title, link.path, link.below))
        assertEquals(listOf(mapOf("nat" to "true")), link.depends)
        assertEquals(emptyList(), packs.filter { !it.hidden && it.menu == listOf("Mapping") })
    }

    @Test
    fun theActiveLabelOfAWindowReadsTheFlagOfItsRecordUnderAHiddenName() = runTest {
        val labelled = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'},{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}},{type:'active'}]}]}"
        val native = assertNotNull(uiPacks(entries("[$labelled,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6").native)
        assertEquals(listOf("mode", ".inactive"), native.fields.map { it.name })
        assertEquals(listOf(0x1, 0xFE000A), native.fields.map { it.key })
        assertEquals(listOf("enm", "bool"), native.fields.map { it.kind })
        assertEquals(listOf("u", "b"), native.fields.map { it.letter })
    }

    @Test
    fun theWindowsOfASelectorAreTheModesOfOneWindowAndTheOnesTheRouterHasAreChoices() = runTest {
        val modes = "{name:'Modes',title:'Modes',selector:1,c:[" +
            "{title:'Ethernet',type:'item',path:[120,6],pred:{type:'quickset',bit:6},c:[{type:'gridcell'},{name:'Uptime',type:'interval',id:'u1'}]}," +
            "{title:'CPE',type:'item',path:[120,1],pred:{type:'quickset',bit:1},c:[{type:'gridcell'},{name:'Uptime',type:'interval',id:'u1'}]}]}"
        suspend fun window(word: Long) = uiPacks(entries("[$modes,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true, facts = RouterFacts(quickSet = word))
            .first { it.menu == listOf("Modes") }.window
        val both = window(0x42)
        assertTrue(both.selector)
        assertEquals(listOf("Ethernet" to "native/120/6", "CPE" to "native/120/1"), both.tabs.map { it.title to it.path })
        val one = window(0x40)
        assertTrue(one.selector)
        assertEquals(listOf("Ethernet"), one.tabs.map { it.title })
    }

    @Test
    fun theButtonsOfAWindowOfTheObjectModelThatOpenOtherEntriesLinkToTheWindowsTheMenuKnows() = runTest {
        val settings = "{name:'Quick Set',title:'Quick Set',c:[{title:'Quick Set',type:'item',path:[120,6],c:[{type:'gridcell'},{name:'Mode',type:'enm',id:'u1',values:{type:'static',map:['Router','Bridge']}}," +
            "{type:'toolbar',c:[{name:'Reboot',type:'contextmenu',group:'System',open:'Reboot',tab:'Reboot'},{name:'Password...',type:'contextmenu',group:'System',open:'Password',tab:'Change Now'}," +
            "{name:'Port Mapping',type:'contextmenu',open:'Port Mapping'}]}]}]}"
        val known = mapOf("system/reboot" to "system/reboot", "system/password" to "password")
        val path = uiPacks(entries("[$settings,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, known, natively = true).first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6")
        assertEquals(listOf("Reboot" to "system/reboot", "Password..." to "password"), path.links.map { it.title to it.path })
    }

    @Test
    fun aRuleThatTheFormCannotSayInTheTextsOfItsFieldsAsksNothing() = runTest {
        val range = quickSet.replace("value:0}", "value:[0,7]}")
        val native = assertNotNull(uiPacks(entries("[$range,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), controller, emptyMap(), natively = true).first { it.menu == listOf("Quick Set") }.paths.getValue("native/120/6").native)
        assertEquals(listOf(emptyList(), emptyList(), listOf(mapOf("mode" to "Bridge"))), native.fields.map { it.depends })
    }

    @Test
    fun anEntryWithoutAMenuStandsWhereTheConditionsOnTheRouterHoldAndKeepsOnlyTheWindowsThatHold() = runTest {
        val modes = "{name:'Modes',title:'Modes',pred:{type:'syscap',value:52},c:[" +
            "{title:'Ethernet',type:'item',path:[120,6],pred:{type:'quickset',bit:6},c:[{type:'gridcell'},{name:'Uptime',type:'interval',id:'u1'}]}," +
            "{title:'CPE',type:'item',path:[120,1],pred:{type:'quickset',bit:1},c:[{type:'gridcell'},{name:'Uptime',type:'interval',id:'u1'}]}]}"
        val host = "{name:'Host',group:'Switch',pred:{type:'syscap',value:52},c:[{name:'Switch Host',title:'Host',type:'map',path:[20,52],nameval:'MAC Address'," +
            "c:[{name:'MAC Address',type:'macaddr',id:'r2',width:100},{name:'Ports',type:'string',id:'s3',width:100}]}]}"
        val text = "[$modes,$host,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"
        val facts = RouterFacts(capabilities = listOf(0, 0x00100000, 0, 0), quickSet = 0x40)
        suspend fun menus(facts: RouterFacts?) = uiPacks(entries(text), controller, emptyMap(), natively = true, facts = facts).filter { it.menu == listOf("Modes") || it.menu == listOf("Switch", "Host") }
        val packs = menus(facts)
        assertEquals(listOf(listOf("Modes"), listOf("Switch", "Host")), packs.map { it.menu })
        assertEquals(listOf("Ethernet" to "native/120/6"), packs[0].window.tabs.map { it.title to it.path })
        assertEquals(listOf(120L, 6L), assertNotNull(packs[0].paths.getValue("native/120/6").native).handler)
        assertEquals(listOf("Host" to "native/20/52"), packs[1].window.tabs.map { it.title to it.path })
        assertEquals(listOf("mac-address", "ports"), assertNotNull(packs[1].paths.getValue("native/20/52").native).fields.map { it.name })
        assertEquals(emptyList(), menus(null))
        assertEquals(emptyList(), menus(facts.copy(capabilities = emptyList(), quickSet = null)))
        assertEquals(listOf(listOf("Switch", "Host")), menus(facts.copy(quickSet = null)).map { it.menu })
        assertEquals(listOf("CPE" to "native/120/1"), menus(facts.copy(quickSet = 0x2)).first { it.menu == listOf("Modes") }.window.tabs.map { it.title to it.path })
    }

    @Test
    fun anEntryOfTheTopLevelIsNotTakenForACommandOfTheRootOrOfAnInterfaceMenu() = runTest {
        val commands = Router(controller.menus + mapOf("" to (members("cmr", "set export") to emptyList()), "interface" to (members("ethernet", "set add print") to emptyList())))
        val entry = "{name:'Quick Set',c:[{title:'Ethernet',type:'item',path:[120,6],ro:1,c:[{type:'gridcell'},{name:'Uptime',type:'interval',id:'u1'}]}]}"
        val packs = uiPacks(entries("[$entry,{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]"), commands, emptyMap(), natively = true)
        assertEquals(listOf("Ethernet" to "native/120/6"), packs.first { it.menu == listOf("Quick Set") }.window.tabs.map { it.title to it.path })
    }

    @Test
    fun aReadOnlyTableTheMenuNamesFewFieldsOfIsTheRoutersOwnTableAndOneItNamesMostOfIsTheMenusWithTheRest() = runTest {
        val wifi = Router(controller.menus + mapOf("cmr" to (members("device wifi", "print set get") to emptyList()), "cmr/wifi" to (members("", "print set get") to listOf("mode", "ssid"))))
        fun interfaces(extra: String) = entries(
            "[{name:'CMR WiFi',dname:'WiFi',section:'Controller',c:[{name:'Interfaces',title:'Interfaces',type:'map',path:[167,34],ro:1,c:[" +
                "{name:'Device',type:'enm',id:'u8110',width:200,values:{type:'dynamic',path:[167,1]}},{name:'Interface',type:'string',id:'s8100',width:100}," +
                "{name:'MAC Address',type:'macaddr',id:'r2b04',width:200},{name:'Mode',type:'enm',id:'u8111',width:100,values:{type:'static',map:['ap','station']}}," +
                "{name:'SSID',type:'string',id:'s810d',width:100}$extra]}]},{name:'CMR Devices',dname:'Devices',section:'Controller',c:[$devices]}]",
        )
        val more = ",{name:'Channel',type:'string',id:'s8109',width:100},{name:'Clients',type:'number',id:'u810a',width:100},{name:'Master',type:'string',id:'s810e',width:100},{name:'CAP',type:'string',id:'s8113',width:100}"
        val own = uiPacks(interfaces(more), wifi, emptyMap(), natively = true).first { it.menu == listOf("Controller", "WiFi") }
        assertEquals(listOf("Interfaces" to "native/167/34"), own.window.tabs.map { it.title to it.path })
        assertEquals(listOf("device", "interface", "mac-address", "mode", "ssid", "channel", "clients", "master", "cap"), own.paths.getValue("native/167/34").columns)
        val joined = uiPacks(interfaces(more), wifi, emptyMap(), natively = false).first { it.menu == listOf("Controller", "WiFi") }
        assertEquals(listOf("Interfaces" to "cmr/wifi"), joined.window.tabs.map { it.title to it.path })
        assertEquals(listOf("mode", "ssid"), joined.paths.getValue("cmr/wifi").columns)
        val mostly = Router(wifi.menus + mapOf("cmr/wifi" to (members("", "print set get") to listOf("device", "interface", "mac-address", "mode", "ssid", "channel", "clients"))))
        assertEquals("cmr/wifi", uiPacks(interfaces(more), mostly, emptyMap(), natively = true).first { it.menu == listOf("Controller", "WiFi") }.window.tabs.single().path)
    }

    @Test
    fun aListWithoutATitleIsNotAWindowOfTheNativeChannelAndOneThatIsEditableCanBeAddedToAndRemovedFrom() = runTest {
        val untitled = resources.replace("name:'Resources',title:'Resources',", "name:'',")
        assertEquals(listOf("Devices" to "cmr/device"), uiPacks(controllerEntry(resources = untitled), controller, emptyMap(), natively = true).single().window.tabs.map { it.title to it.path })
        val editable = resources.replace("ro:1,c:[", "c:[")
        val pack = uiPacks(controllerEntry(resources = editable), controller, emptyMap(), natively = true).single()
        assertEquals(listOf("Devices" to "cmr/device", "Resources" to "native/167/44"), pack.window.tabs.map { it.title to it.path })
        val native = assertNotNull(pack.paths.getValue("native/167/44").native)
        assertTrue(native.add && native.remove && !native.single)
    }

    @Test
    fun aListThatFitsAMenuOfTheConsoleIsThatMenuUnderAnotherNameAndNotAWindowOfTheNativeChannel() = runTest {
        val usage = Router(controller.menus + mapOf("cmr" to (members("device usage", "print set get") to emptyList()), "cmr/usage" to (members("", "print") to listOf("device", "cpu", "memory", "hdd"))))
        val pack = uiPacks(controllerEntry(), usage, emptyMap(), natively = true).single()
        assertEquals(listOf("Devices" to "cmr/device"), pack.window.tabs.map { it.title to it.path })
    }

    @Test
    fun withoutTheNativeChannelTheWindowsStayAsTheConsoleHasThem() = runTest {
        val pack = uiPacks(controllerEntry(), controller, emptyMap()).single()
        assertEquals(listOf("Devices" to "cmr/device"), pack.window.tabs.map { it.title to it.path })
        assertEquals(null, pack.paths.getValue("cmr/device").native)
        assertEquals(listOf("identity", "address", "board"), pack.paths.getValue("cmr/device").columns)
    }

    @Test
    fun aWindowOfOneObjectOrOneThatWinBoxDoesNotShowWholeIsNotReadByTheNativeChannel() = runTest {
        val many = devices.replace("type:'map',", "type:'map',maxobjs:50,")
        assertEquals(null, uiPacks(controllerEntry(many), controller, emptyMap(), natively = true).single().paths.getValue("cmr/device").native)
        val settings = devices.replace("type:'map',", "type:'item',")
        assertEquals(null, uiPacks(controllerEntry(settings), controller, emptyMap(), natively = true).single().paths.getValue("cmr/device").native)
    }

    @Test
    fun theNativeFieldsAreKeptWithThePacks() = runTest {
        val packs = uiPacks(controllerEntry(), controller, emptyMap(), natively = true)
        val again = assertNotNull(decodeUiPacks(encodeUiPacks(packs)))
        assertEquals(packs.single().paths.getValue("cmr/device").native?.fields?.map { it.name to it.names }, again.single().paths.getValue("cmr/device").native?.fields?.map { it.name to it.names })
        assertEquals(packs.single().paths.getValue("native/167/44").native?.fields?.first()?.refers, again.single().paths.getValue("native/167/44").native?.fields?.first()?.refers)
    }

    @Test
    fun theKeptPacksReadBackAsTheyWere() = runTest {
        val packs = uiPacks(entries(gadgets), router, emptyMap())
        val again = assertNotNull(decodeUiPacks(encodeUiPacks(packs)))
        assertEquals(packs.map { it.menu }, again.map { it.menu })
        assertEquals(packs.single().forms.getValue("ip/gadget").tabs.map { it.fields }, again.single().forms.getValue("ip/gadget").tabs.map { it.fields })
        assertEquals(null, decodeUiPacks("not json"))
    }

    @Test
    fun theListOfFilesIsReadWithItsCommas() {
        val files = parseUiList(
            "{ crc: 1125591025, size: 84936, name: \"wave2.jg\", unique: \"wave2-ccd306e5479afab7.jg\", version: \"7.99\" },\n" +
                "{ crc: 105621509, size: 20319, name: \"hotspot.jg\", unique: \"hotspot-fc3ae9805bbb8acc.jg\",version: \"7.99\" },\n",
        )
        assertEquals(listOf("wave2.jg", "hotspot.jg"), files.map { it.name })
        assertEquals("hotspot-fc3ae9805bbb8acc.jg", files[1].unique)
        assertEquals(84936, files[0].size)
    }

    @Test
    fun theRoutesOfIpv4AreTheMenuOfTheirEntryThoughTheHiddenWindowOfAllRoutesStandsFirst() = runTest {
        val routes = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("route") to emptyList()),
                "ip/route" to (members("", "print set add get remove") to listOf("dst-address", "gateway", "distance", "disabled", "comment")),
            ),
        )
        val text = "[{name:'Routes',group:'IP',c:[{name:'Route',title:'All Routes',type:'map',path:[44,21],generic:'route',hide:1,maxobjs:10000,nonpublic:1,typeon:'rtype',c:[" +
            "{name:'rtype',type:'number',id:'u81',nonpublic:1,ro:1},{name:'General',type:'tab'},{name:'Dst. Address',type:'ipaddr',id:'u105',width:100}," +
            "{name:'Gateway',type:'ipaddr',id:'u10d',width:100},{name:'Distance',type:'number',id:'u108',width:40}]}," +
            "{title:'Routes',type:'map',inherit:'route',typevalue:2,c:[{name:'Distance',type:'number',id:'u108',width:40}," +
            "{name:'BGP',type:'tab'},{name:'AS Path',type:'string',id:'s139'},{name:'Communities',type:'string',id:'s13a'},{name:'Local Pref.',type:'number',id:'u13b'}]}]}]"
        val route = uiInheritedMirrors(entries(text), routes, mapOf("ip/routes" to "ip/route")).getValue("ip/route")
        assertEquals(listOf(44L, 21L), route.handler)
        assertEquals(mapOf(".kind" to "2"), route.only)
        assertEquals(10000, route.most)
    }

    @Test
    fun theWindowsOfTheKindsOfInterfacesReadTheTableOfAllOfThemEachTheRowsOfItsKind() = runTest {
        val interfaces = Router(
            mapOf(
                "" to (members("interface") to emptyList()),
                "interface" to (members("wireguard", "print set get") to listOf("name", "type", "actual-mtu", "running", "disabled", "comment")),
                "interface/wireguard" to (members("peers", "print set add get") to listOf("name", "mtu", "listen-port", "private-key", "public-key", "running", "disabled", "comment")),
            ),
        )
        val text = "[{name:'Interfaces',c:[{name:'Interface',title:'Interface',type:'map',path:[20,0],generic:'iface',typeon:'type',c:[" +
            "{name:'type',type:'number',id:'u10001',nonpublic:1,ro:1},{name:'General',type:'tab'},{name:'Name',type:'string',id:'s10006',width:100}," +
            "{name:'Type',type:'objtype',width:120},{name:'Actual MTU',type:'number',id:'u10069',ro:1,width:70},{name:'Running',type:'flag',id:'b10004',hint:'R'}]}]}," +
            "{name:'WireGuard',c:[{name:'Interface',title:'WireGuard',type:'map',inherit:'iface',typevalue:70,c:[{name:'General',type:'tab',combine:1}," +
            "{name:'MTU',type:'number',id:'u10064',override:1,width:50},{name:'Listen Port',type:'number',id:'u3eb',width:55}," +
            "{name:'Private Key',type:'secret',id:'s3e9',width:160},{name:'Public Key',type:'string',id:'s3ea',ro:1,width:160}]}]}]"
        val mirrors = uiInheritedMirrors(entries(text), interfaces, mapOf("/interfaces" to "interface", "/wireguard" to "interface/wireguard"))
        val all = mirrors.getValue("interface")
        assertEquals(listOf(20L, 0L), all.handler)
        assertEquals(0x10001, all.fields.single { it.name == "type" }.key)
        assertTrue(all.only.isEmpty())
        val wireguard = mirrors.getValue("interface/wireguard")
        assertEquals(listOf(20L, 0L), wireguard.handler)
        assertEquals(mapOf(".kind" to "70"), wireguard.only)
        assertEquals(0x3E9, wireguard.fields.single { it.name == "private-key" }.key)
        assertTrue(listOf("name", "mtu", "listen-port", "public-key", "running", "comment", "disabled").all { name -> wireguard.fields.any { it.name == name } })
    }

    private val nat = Router(
        mapOf(
            "" to (members("ip") to emptyList()),
            "ip" to (members("firewall") to emptyList()),
            "ip/firewall" to (members("nat") to emptyList()),
            "ip/firewall/nat" to (members("", "print set add get remove") to listOf("chain", "action", "to-addresses", "to-ports", "same-not-by-dst", "managed", "comment", "disabled")),
        ),
    )

    private val natText = "[{name:'NAT',group:'IP',c:[{name:'NAT',title:'NAT',type:'map',path:[20,5],c:[" +
        "{name:'General',type:'tab'},{name:'Chain',type:'string',id:'s27',width:60},{name:'Action',type:'tab'}," +
        "{name:'Action',type:'enm',id:'u28',values:{type:'static',map:{20:'src-nat',22:'dst-nat'}},width:80}," +
        "{type:'deck',panes:[{vals:[20,22],c:[{name:'To Addresses',type:'opt',id:'b3ee',c:[{type:'network',id:'u3e8',maskid:'u3e9',range:1}]}," +
        "{name:'To Ports',type:'opt',id:'b3ef',c:[{type:'numberrange',id:'u3ea',highid:'u3eb'}]},{name:'Not by Dst.',type:'bool',id:'b3ed'}]}],selon:'Action'}," +
        "{name:'managed',type:'flag',hasid:'uff001e',hint:'Y'}]}]}]"

    private suspend fun natMirror(text: String = natText) =
        assertNotNull(uiPacks(entries(text), nat, mapOf("ip/nat" to "ip/firewall/nat"), natively = true).single().paths.getValue("ip/firewall/nat").mirror)

    @Test
    fun theMirrorOfATableTakesEachPartOfATupleThatNamesItsPartsBeforeTheWordTheyShare() = runTest {
        val connections = Router(
            mapOf(
                "" to (members("ip") to emptyList()),
                "ip" to (members("firewall") to emptyList()),
                "ip/firewall" to (members("connection") to emptyList()),
                "ip/firewall/connection" to (members("", "print get remove") to listOf("protocol", "orig-bytes", "repl-bytes", "limit-at")),
            ),
        )
        val text = "[{name:'Connections',group:'IP',c:[{name:'Connection',title:'Connections',type:'map',path:[20,32],maxobjs:10000,ro:1,c:[" +
            "{name:'Protocol',type:'enm',id:'u1',values:{type:'static',map:{6:'tcp',17:'udp'}},width:60}," +
            "{name:'Orig./Repl. Bytes',type:'tuple',width:100,c:[{type:'bigbytes',id:'q20'},{type:'bigbytes',id:'q24'}]}," +
            "{name:'Limit At',type:'tuple',sep:'/',c:[{type:'bitrate',id:'u30'},{type:'bitrate',id:'u31'}]}]}]}]"
        val mirror = assertNotNull(uiPacks(entries(text), connections, mapOf("ip/connections" to "ip/firewall/connection"), natively = true).single().paths.getValue("ip/firewall/connection").mirror)
        assertEquals(mapOf("orig-bytes" to 0x20, "repl-bytes" to 0x24), mirror.fields.filter { it.name.endsWith("-bytes") }.associate { it.name to it.key })
        // a tuple the console prints as one property stays one, which the window does not read, so the form of a row is left to the console
        assertTrue(mirror.fields.none { it.name.startsWith("limit-at") })
        assertTrue(mirror.partial)
        assertEquals(10000, mirror.most)
    }

    @Test
    fun theMirrorOfATableWhoseWindowShowsSoManyRowsAtMostReadsNoMoreOfThem() = runTest {
        assertEquals(null, natMirror().most)
        assertEquals(10000, natMirror(natText.replace("path:[20,5],", "path:[20,5],maxobjs:10000,")).most)
    }

    @Test
    fun theMirrorOfATableTakesTheFieldsOfThePanesOfADeckAndAFlagOfAFieldTheRecordHolds() = runTest {
        val mirror = natMirror()
        assertTrue(listOf("chain", "action", "to-addresses", "to-ports", "managed").all { name -> mirror.fields.any { it.name == name } })
        assertEquals(PRESENCE, mirror.fields.single { it.name == "managed" }.kind)
        assertEquals(0xFF001E, mirror.fields.single { it.name == "managed" }.key)
        // the console calls Not by Dst. same-not-by-dst, so the window of a rule is left to the console
        assertTrue(mirror.partial)
    }
}
