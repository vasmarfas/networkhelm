package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.session.RouterFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiQueryTest {

    private fun entries(text: String) = uiNodes(parseJg(text))

    private val echo = "[{name:'Echo',group:'Tools',c:[{title:'Echo',type:'query',path:[7,1],autorefresh:500,autolimit:200,autoscroll:1,startcmd:16646159,cancelcmd:16646161," +
        "request:[{name:'General',type:'tab'},{name:'Echo To',type:'addr',id:'m16',req:1},{name:'Count',type:'number',id:'u11',opt:1}," +
        "{name:'Interval',type:'number',id:'u2',def:1000,min:10,max:5000,postfix:'ms'},{name:'Mode',type:'enm',id:'u4',def:0,values:{type:'static',map:['fast','slow']}}," +
        "{name:'Advanced',type:'tab'},{name:'Port',type:'number',id:'u5',on:'slow'},{name:'slow',type:'cond',c:[{on:'Mode',pred:{type:'number',value:[1]}}]}," +
        "{name:'Key',type:'opt',c:[{type:'secret',id:'s6'}]},{name:'Unknown',type:'multimacnetwork',id:'M9'}]," +
        "c:[{name:'Seq',type:'number',id:'uf',width:45}," +
        "{name:'Host',type:'tuple',sep:' - ',width:150,c:[{type:'ipaddr',id:'u1'},{type:'macaddr',id:'r8',opt:1}]}," +
        "{name:'Time',type:'enm',id:'u7',postfix:'ms',values:{type:'static',map:{'4294967295':'timeout'}},width:50,c:[{type:'fixedpoint',scale:1000}]}," +
        "{type:'statusbar',c:[{type:'string',id:'s68'},{name:'Loss',type:'integer',id:'u67',postfix:'%'}]}]}]}]"

    private val disks = "[{name:'Disks',group:'System',c:[{title:'Disks',type:'map',path:[79,1],c:[{name:'Name',type:'string',id:'sfe0010',width:80}]}," +
        "{title:'Format Drive',type:'query',path:[79,1],startcmd:16646159,cancelcmd:16646161,getallcmd:13,confirm:'All data will be lost, are you sure?'," +
        "request:[{name:'File System',type:'enm',id:'u2',req:1,values:{type:'static',map:['ext4','fat32']}}],c:[{name:'Progress',type:'number',id:'u1',width:50}]}," +
        "{title:'Copy',type:'query',path:[79,1],startcmd:10,cancelcmd:11,getallcmd:12,request:[],c:[{name:'Done',type:'number',id:'u1',width:50}]}," +
        "{title:'Speed',type:'action',path:[79,1],startcmd:2,pollcmd:1,cancelcmd:3,uptime:'Uptime',pred:{type:'board',value:'x86'}," +
        "request:[{name:'Direction',type:'enm',id:'u20',def:256,values:{type:'static',map:{'256':'receive','512':'transmit'}}}]," +
        "c:[{name:'Lost',type:'number',id:'u48'},{name:'Uptime',type:'age',id:'uffffffff'}," +
        "{name:'Tx/Rx Current',type:'tuple',c:[{type:'bigbitrate',id:'Q1'},{type:'bigbitrate',id:'Qb'}]}," +
        "{type:'statusbar',c:[{type:'enm',id:'u14',values:{type:'static',map:['connecting...','running...']}}]}]}]}]"

    private fun one(text: String, facts: RouterFacts? = null) = uiQueries(entries(text), facts).single()

    @Test
    fun anEntryThatHoldsOnlyAQueryBecomesAWindowOfItsOwnWithTheTableOfTheQuery() {
        val pack = one(echo)
        assertEquals(listOf("Tools", "Echo"), pack.menu)
        assertFalse(pack.hidden)
        assertEquals(listOf("Echo" to "native/7/1/q16646159"), pack.window.tabs.map { it.title to it.path })
        val path = pack.paths.getValue("native/7/1/q16646159")
        assertEquals(listOf("seq", "host", "time"), path.columns)
        assertEquals("Time (ms)", path.labels["time"])
        assertEquals("Interval (ms)", path.labels["interval"])
        val native = assertNotNull(path.native)
        assertEquals(listOf(7L, 1L), native.handler)
        assertEquals(listOf("seq", "host", "time", "status", "loss"), native.fields.map { it.name })
    }

    @Test
    fun aLimitOfRowsThatIsNoNumberOfRowsIsNoLimit() {
        for (given in listOf("0", "4294967295")) {
            val query = assertNotNull(one(echo.replace("autolimit:200", "autolimit:$given")).paths.values.single().native?.query)
            assertNull(query.limit)
        }
    }

    @Test
    fun theCommandsTheRefreshAndTheLimitComeFromTheDescriptionAndWhatItLeavesOutFromTheUsualNumbers() {
        val query = assertNotNull(one(echo).paths.values.single().native?.query)
        assertFalse(query.action)
        assertEquals(listOf(0xFE000FL, 0xFE0011L, 0xFE0004L), listOf(query.start, query.cancel, query.poll))
        assertEquals(500L, query.refresh)
        assertEquals(200, query.limit)
        val plain = uiQueries(entries(disks.replace("getallcmd:13,", "")), null).first()
        assertEquals(0xFE0004L, plain.paths.values.single().native?.query?.poll)
    }

    @Test
    fun theRequestHasTheFieldsTheClientCanSendInTheOrderOfTheTabsAndNoOtherField() {
        val pack = one(echo)
        val path = pack.paths.values.single()
        val query = assertNotNull(path.native?.query)
        assertEquals(listOf("echo-to", "count", "interval", "mode", "port", "key"), query.request.map { it.name })
        assertEquals(listOf("addr", "number", "number", "enm", "number", "string"), query.request.map { it.kind })
        assertTrue(query.request.all { it.writable })
        val form = pack.forms.getValue(path.form.orEmpty())
        assertEquals(listOf("General", "Advanced"), form.tabs.map { it.title })
        assertEquals(listOf("echo-to", "count", "interval", "mode"), form.tabs[0].fields)
        assertEquals(listOf("port", "key"), form.tabs[1].fields)
    }

    @Test
    fun aFieldThatIsRequiredOrOptionalIsSaidSoAndADefaultIsTheTextTheFormShows() {
        val path = one(echo).paths.values.single()
        val query = assertNotNull(path.native?.query)
        assertEquals(listOf("echo-to"), query.required)
        assertEquals(listOf("count", "key"), query.optional)
        assertEquals(mapOf("interval" to "1000", "mode" to "fast"), path.defaults)
    }

    @Test
    fun aFieldThatStandsUnderAConditionIsHiddenUntilTheFieldItAsksAboutHoldsTheValue() {
        val pack = one(echo)
        val form = pack.forms.getValue(pack.paths.values.single().form.orEmpty())
        val gate = form.only.single()
        assertEquals(listOf("port"), gate.fields)
        assertEquals(mapOf("mode" to listOf("slow")), gate.where)
        assertTrue(gate.hide)
        assertEquals(listOf(mapOf("mode" to "slow")), assertNotNull(pack.paths.values.single().native?.query).request.first { it.name == "port" }.depends)
    }

    private val lists = "[{name:'Interfaces',c:[{title:'Interface',type:'map',path:[20,0],nameval:'Name',c:[{name:'Name',type:'string',id:'s10006',width:80}]}]}," +
        "{name:'Probe',group:'Tools',c:[{title:'Probe',type:'query',path:[8],autoclose:1,request:[" +
        "{name:'Interface',type:'enm',id:'u1',values:{type:'dynamic',path:[20,0]}}," +
        "{name:'Port',type:'enm',id:'u2',values:{type:'pair',c:[{type:'static',map:{'0':'any'}},{type:'dynamic',path:[20,0]}]}}," +
        "{name:'All',type:'enm',id:'u5',values:{type:'defenum',defid:0,defname:'all',values:{type:'dynamic',path:[20,0]}}}," +
        "{name:'Several',type:'multinumber',id:'U3',c:[{type:'enm',values:{type:'dynamic',path:[20,0]}}]}," +
        "{name:'Filters',type:'multi',id:'M4',c:[{type:'not',id:'b1',c:[{type:'network',id:'u2',maskid:'u3'}]}]}," +
        "{name:'Upgrade',type:'bool',id:'b6'},{name:'Do not turn it off.',type:'label',on:'upgr'}," +
        "{name:'upgr',type:'cond',c:[{on:'Upgrade',pred:{type:'bool',value:1}}]}],c:[{name:'Name',type:'string',id:'s1',width:50}]}]}]"

    @Test
    fun aFieldThatNamesRowsOfAnotherWindowReferToThemWhereverTheNamesStandInTheValues() {
        val request = assertNotNull(uiQueries(entries(lists), null).single().paths.values.single().native?.query).request.associateBy { it.name }
        assertEquals(listOf(20L, 0L), request.getValue("interface").refers?.handler)
        assertEquals(mapOf(0L to "any"), request.getValue("port").names)
        assertEquals(listOf(20L, 0L), request.getValue("port").refers?.handler)
        assertEquals(mapOf(0L to "all"), request.getValue("all").names)
        assertEquals(listOf(20L, 0L), request.getValue("all").refers?.handler)
        assertEquals("multinumber", request.getValue("several").kind)
        assertEquals(listOf(20L, 0L), request.getValue("several").refers?.handler)
    }

    @Test
    fun aListOfFiltersIsWritableWhereItsElementIsAndALabelIsALineOfTheFormThatStandsUnderItsCondition() {
        val pack = uiQueries(entries(lists), null).single()
        val path = pack.paths.values.single()
        val query = assertNotNull(path.native?.query)
        val filters = query.request.first { it.name == "filters" }
        assertEquals("multi", filters.kind)
        assertTrue(filters.writable)
        assertEquals(mapOf("do-not-turn-it-off" to "Do not turn it off."), query.notes)
        assertTrue(query.close)
        val form = pack.forms.getValue(path.form.orEmpty())
        assertEquals(listOf("interface", "port", "all", "several", "filters", "upgrade", "do-not-turn-it-off"), form.tabs.single().fields)
        assertEquals(mapOf("upgrade" to listOf("true")), form.only.single().where)
        assertEquals(listOf("do-not-turn-it-off"), form.only.single().fields)
    }

    @Test
    fun aTitledCellOfTheGridIsASectionOfTheFormThatTheFieldsAfterItStandUnder() {
        val text = "[{name:'Probe',group:'Tools',c:[{title:'Probe',type:'query',path:[8],request:[{name:'Basic',type:'gridcell',colspan:2}," +
            "{name:'Target',type:'string',id:'s1'},{name:'Filters',type:'gridcell',colspan:2},{name:'Port',type:'number',id:'u2'}],c:[{name:'Name',type:'string',id:'s1',width:50}]}]}]"
        val pack = one(text)
        val form = pack.forms.getValue(pack.paths.values.single().form.orEmpty())
        assertEquals(listOf("Basic" to true, "Filters" to true), form.tabs.map { it.title to it.inline })
        assertEquals(listOf(listOf("target"), listOf("port")), form.tabs.map { it.fields })
    }

    @Test
    fun aTableWhoseFieldsHaveNoWidthShowsThemAllAndAFlagIsALetterBesideTheRow() {
        val text = "[{name:'Probe',group:'Tools',c:[{title:'Probe',type:'query',path:[8],request:[],c:[{name:'Name',type:'string',id:'s1'},{name:'Seen',type:'flag',id:'b2',hint:'S'}]}]}]"
        val path = one(text).paths.values.single()
        assertEquals(listOf("name"), path.columns)
        assertEquals(listOf("seen" to "S"), path.flags.map { it.property to it.letter })
    }

    @Test
    fun aTupleIsOneColumnWithItsPartsJoinedAndTheUnitOfAColumnStandsInItsHeading() {
        val fields = one(echo).paths.values.single().native?.fields.orEmpty()
        val host = fields.first { it.name == "host" }
        assertEquals("tuple", host.kind)
        assertEquals(" - ", host.separator)
        assertEquals(listOf("ipaddr", "macaddr"), host.parts.map { it.kind })
        val time = fields.first { it.name == "time" }
        assertEquals("union", time.kind)
        assertTrue(time.members.all { it.postfix == null })
    }

    @Test
    fun theStatusBarOfTheWindowIsAListOfFieldsOfTheRowsAndTheOnesWithoutATitleCarryNoLabel() {
        val path = one(echo).paths.values.single()
        val query = assertNotNull(path.native?.query)
        assertEquals(listOf("status", "loss"), query.status)
        assertNull(path.labels["status"])
        assertEquals("Loss", path.labels["loss"])
        assertEquals("%", path.native.fields.first { it.name == "loss" }.postfix)
    }

    @Test
    fun theWindowsOfAnEntryThatHasWindowsOfItsOwnAreHiddenAndNamedAfterTheEntry() {
        val packs = uiQueries(entries(disks), RouterFacts(board = "x86"))
        assertEquals(listOf(listOf("System", "Disks", "Format Drive"), listOf("System", "Disks", "Copy"), listOf("System", "Disks", "Speed")), packs.map { it.menu })
        assertTrue(packs.all { it.hidden })
        assertEquals(listOf("native/79/1/q16646159", "native/79/1/q10", "native/79/1/a2"), packs.map { it.window.tabs.single().path })
    }

    @Test
    fun aWindowThatRisksDataNeedsTheConfirmationOfTheUserAndTheTextOfTheRouterIsKept() {
        val packs = uiQueries(entries(disks), RouterFacts(board = "x86")).associateBy { it.window.title }
        val format = packs.getValue("Format Drive").paths.values.single()
        assertTrue(format.confirm)
        assertEquals("All data will be lost, are you sure?", format.native?.query?.confirm)
        assertEquals(13L, format.native?.query?.poll)
        assertTrue(packs.getValue("Copy").paths.values.single().confirm)
        assertNull(packs.getValue("Copy").paths.values.single().native?.query?.confirm)
        assertFalse(packs.getValue("Speed").paths.values.single().confirm)
    }

    @Test
    fun anActionHasItsPollCommandItsResultFieldsTheUptimeAndTheStateInItsStatusBar() {
        val path = uiQueries(entries(disks), RouterFacts(board = "x86")).first { it.window.title == "Speed" }.paths.values.single()
        val query = assertNotNull(path.native?.query)
        assertTrue(query.action)
        assertEquals(listOf(2L, 3L, 1L), listOf(query.start, query.cancel, query.poll))
        assertEquals(listOf("lost", "uptime", "tx-rx-current"), query.results)
        assertEquals("uptime", query.uptime)
        assertEquals(listOf("status"), query.status)
        val tuple = path.native.fields.first { it.name == "tx-rx-current" }
        assertEquals("/", tuple.separator)
        assertEquals(listOf("tx-rx-current"), path.bits)
    }

    @Test
    fun aWindowThatTheConditionOfTheRouterHidesIsNotMade() {
        assertEquals(2, uiQueries(entries(disks), RouterFacts(board = "mmips")).size)
        assertEquals(2, uiQueries(entries(disks), null).size)
        assertEquals(3, uiQueries(entries(disks), RouterFacts(board = "x86")).size)
    }

    private fun bundledEcho(fasttrack: Boolean = false) = Pack(
        menu = listOf("Tools", "Echo"),
        window = PackWindow("Echo", listOf(PackTab("Echo", "tool/echo"))),
        paths = mapOf("tool/echo" to PackPath(columns = listOf("seq"), form = "tool", fasttrack = fasttrack)),
        forms = mapOf("tool" to PackForm(tabs = listOf(PackFormTab("General", listOf("address"))))),
    )

    @Test
    fun theWarningOfTheBundledWindowStaysWithTheQueryThatTakesItsPlace() {
        val merged = withQueries(listOf(one(echo)), listOf(bundledEcho(fasttrack = true))).single()
        assertTrue(merged.paths.getValue("tool/echo").fasttrack)
    }

    @Test
    fun theQueryTakesTheConsoleTabOfTheBundledWindowItsPathAndLeavesTheOtherPathsBe() {
        val bundled = bundledEcho()
        val merged = withQueries(listOf(one(echo)), listOf(bundled)).single()
        assertEquals(listOf("Tools", "Echo"), merged.menu)
        assertEquals(listOf("tool/echo"), merged.window.tabs.map { it.path })
        val path = merged.paths.getValue("tool/echo")
        assertNotNull(path.native?.query)
        assertEquals("tool/echo", path.form)
        assertEquals(listOf("General", "Advanced"), merged.forms.getValue("tool/echo").tabs.map { it.title })
        assertEquals(listOf("address"), bundled.forms.getValue("tool").tabs.single().fields)
        val console = assertNotNull(path.native.query.fallback)
        assertEquals(listOf("seq"), console.path.columns)
        assertEquals(listOf("address"), console.form?.tabs?.single()?.fields)
    }

    @Test
    fun aQueryThatHasNoWindowToTakeThePlaceOfStandsAlone() {
        val merged = withQueries(listOf(one(echo)), emptyList()).single()
        assertEquals(listOf("native/7/1/q16646159"), merged.window.tabs.map { it.path })
    }

    @Test
    fun theButtonsOfAnEntryOpenTheHiddenWindowsOfItsQueries() {
        val owner = Pack(
            menu = listOf("System", "Disks"),
            window = PackWindow("Disks", listOf(PackTab("Disks", "native/79/1"))),
            paths = mapOf("native/79/1" to PackPath(native = PackNative(listOf(79, 1), emptyList()))),
        )
        val made = withQueries(listOf(owner) + uiQueries(entries(disks), RouterFacts(board = "x86")), emptyList())
        val links = made.first { it.menu == listOf("System", "Disks") }.paths.getValue("native/79/1").links
        assertEquals(listOf("Format Drive" to "native/79/1/q16646159", "Copy" to "native/79/1/q10", "Speed" to "native/79/1/a2"), links.map { it.title to it.path })
        assertEquals(4, made.size)
        assertTrue(made.filter { it.menu.size == 3 }.all { it.hidden })
    }

    @Test
    fun aBundledWindowOfTheEntryIsTheOwnerWhereTheRouterHasNoWindowOfThatEntry() {
        val bundled = Pack(menu = listOf("System", "Disks"), window = PackWindow("Disks", listOf(PackTab("Disks", "system/disk"))), paths = mapOf("system/disk" to PackPath()))
        val made = withQueries(uiQueries(entries(disks), RouterFacts(board = "x86")), listOf(bundled))
        assertEquals(listOf("Format Drive", "Copy", "Speed"), made.first { it.menu == listOf("System", "Disks") }.paths.getValue("system/disk").links.map { it.title })
        assertEquals(3, made.count { it.hidden })
        assertTrue(bundled.paths.getValue("system/disk").links.isEmpty())
    }

    @Test
    fun aHiddenWindowWhoseEntryHasNoWindowOnTheListIsLeftOut() {
        val made = withQueries(uiQueries(entries(disks), RouterFacts(board = "x86")), emptyList())
        assertTrue(made.isEmpty())
    }
}
