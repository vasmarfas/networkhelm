package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackDerived
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UiOverlayTest {

    private fun pack(menu: List<String>, path: PackPath, vararg tabs: PackFormTab, window: List<PackTab> = listOf(PackTab("Gadgets", "ip/gadget"))) = Pack(
        menu = menu,
        window = PackWindow("Gadgets", window),
        paths = mapOf("ip/gadget" to path),
        forms = mapOf("ip/gadget" to PackForm(tabs = tabs.toList())),
    )

    private val bundled = pack(
        listOf("IP", "Gadgets"),
        PackPath(columns = listOf("name", "use-peer-dns", "rate"), form = "ip/gadget", quick = "name", labels = mapOf("name" to "Gadget Name"), actions = listOf(PackAction("Renew", "renew"))),
        PackFormTab("General", listOf("name", "legacy", "use-peer-dns", "allow-reconfigure")),
        PackFormTab("Status", listOf("address")),
        PackFormTab("Traffic", listOf("tx-rate"), chart = true),
        window = listOf(PackTab("Gadget", "ip/gadget", since = "7.1"), PackTab("Options", "ip/gadget/option")),
    )

    private val made = pack(
        listOf("IP", "Gadgets"),
        PackPath(
            columns = listOf("state", "name", "address"),
            readonly = listOf("state"),
            form = "ip/gadget",
            labels = mapOf("name" to "Other Name", "allow-reconfigure" to "Allow Reconfigure Messages"),
            actions = listOf(PackAction("Release", "release"), PackAction("Renew Lease", "renew")),
        ),
        PackFormTab("General", listOf("name", "interface", "use-peer-dns", GROUP_LINE, "allow-reconfigure")),
        PackFormTab("status", listOf("address", "state")),
        PackFormTab("Advanced", listOf("dscp")),
    )

    private val merged get() = routerFirst(listOf(bundled), listOf(made)).single()

    @Test
    fun theTabsAndTheFieldsComeInTheOrderOfTheRouterAndAFieldOnlyTheBundledFormHasStaysBehindItsPredecessor() {
        val tabs = merged.forms.getValue("ip/gadget").tabs
        assertEquals(listOf("General", "status", "Advanced", "Traffic"), tabs.map { it.title })
        assertEquals(listOf("name", "legacy", "interface", "use-peer-dns", GROUP_LINE, "allow-reconfigure"), tabs[0].fields)
        assertEquals(listOf("address", "state"), tabs[1].fields)
        assertEquals(listOf("dscp"), tabs[2].fields)
        assertEquals(true, tabs[3].chart)
    }

    @Test
    fun theFirstTabsOfTwoFormsWithNoTitleInCommonAreOneTabAndNoGroupLineIsLeftAtAnEdgeOrDoubled() {
        val one = routerFirst(
            listOf(pack(listOf("A"), PackPath(form = "ip/gadget"), PackFormTab("Resources", listOf("uptime", GROUP_LINE, "free-memory", "total-memory", GROUP_LINE, "cpu")))),
            listOf(pack(listOf("A"), PackPath(form = "ip/gadget"), PackFormTab("General", listOf("uptime", GROUP_LINE, "cpu", "load")))),
        ).single().forms.getValue("ip/gadget")
        assertEquals(listOf("General" to listOf("uptime", "free-memory", "total-memory", GROUP_LINE, "cpu", "load")), one.tabs.map { it.title to it.fields })
        val tidy = routerFirst(
            listOf(pack(listOf("A"), PackPath(form = "ip/gadget"), PackFormTab("General", listOf("a")), PackFormTab("Memory", listOf("x", GROUP_LINE, "b", "c", GROUP_LINE, "y")))),
            listOf(pack(listOf("A"), PackPath(form = "ip/gadget"), PackFormTab("General", listOf("a", "x", "y")))),
        ).single().forms.getValue("ip/gadget")
        assertEquals(listOf("General" to listOf("a", "x", "y"), "Memory" to listOf("b", "c")), tidy.tabs.map { it.title to it.fields })
    }

    @Test
    fun theFieldsThatGoToTheRouterAtOnceAreThoseOfBothAndWhatOnlyTheBundledPackKnowsStays() {
        val have = pack(
            listOf("A"),
            PackPath(form = "ip/gadget", autoset = listOf("channel", "mode"), derived = mapOf(".state" to PackDerived("status", mapOf("1" to listOf("new version"))))),
            PackFormTab("General", listOf("channel", "mode")),
        )
        val said = pack(listOf("A"), PackPath(form = "ip/gadget", autoset = listOf("mode", "ip-version")), PackFormTab("General", listOf("channel", "mode", "ip-version")))
        val path = routerFirst(listOf(have), listOf(said)).single().paths.getValue("ip/gadget")
        assertEquals(listOf("channel", "mode", "ip-version"), path.autoset)
        assertEquals(listOf(".state"), path.derived.keys.toList())
    }

    @Test
    fun theTableOfABundledPackIsReadAgainAsOftenAsTheWindowOfTheRouterSays() {
        val said = pack(listOf("A"), PackPath(refresh = 5000))
        assertEquals(5000L, routerFirst(listOf(pack(listOf("A"), PackPath())), listOf(said)).single().paths.getValue("ip/gadget").refresh)
    }

    @Test
    fun theWindowTakesTheTabsOfTheRouterWithTheVersionsOfTheBundledOnesAndKeepsTheOthersLast() {
        val tabs = merged.window.tabs
        assertEquals(listOf("Gadgets" to "ip/gadget", "Options" to "ip/gadget/option"), tabs.map { it.title to it.path })
        assertEquals("7.1", tabs.first().since)
        assertEquals("Gadgets", merged.window.title)
    }

    private fun tabs(vararg titles: Pair<String, String>) = titles.map { (title, path) -> PackTab(title, path) }

    private fun merged(have: List<PackTab>, said: List<PackTab>) = routerFirst(listOf(pack(listOf("A"), PackPath(), window = have)), listOf(pack(listOf("A"), PackPath(), window = said))).single().window.tabs

    @Test
    fun aTabOnlyTheBundledPackHasStaysBehindItsPredecessorAndOneThatCameFirstStaysFirst() {
        val result = merged(tabs("Gadget" to "a", "Options" to "b", "Extras" to "c"), tabs("Options" to "b", "Settings" to "d"))
        assertEquals(listOf("Gadget", "Options", "Extras", "Settings"), result.map { it.title })
    }

    @Test
    fun aTitleOfTheRouterThatTwoTabsWouldShareOrThatIsEmptyIsNotTaken() {
        val shared = merged(tabs("Instances" to "a", "Connections" to "b"), tabs("Instances" to "b"))
        assertEquals(listOf("Instances" to "a", "Connections" to "b"), shared.map { it.title to it.path })
        val empty = merged(tabs("Users" to "a", "Groups" to "b"), tabs("" to "a", "Groups" to "b"))
        assertEquals(listOf("Users", "Groups"), empty.map { it.title })
    }

    @Test
    fun theColumnsOfTheRouterComeFirstAndTheBundledOnesItLacksStayBehindTheirPredecessors() {
        assertEquals(listOf("state", "name", "use-peer-dns", "rate", "address"), merged.paths.getValue("ip/gadget").columns)
    }

    @Test
    fun theLabelsOfTheRouterWinAndWhatOnlyThePackKnowsStays() {
        val path = merged.paths.getValue("ip/gadget")
        assertEquals(mapOf("name" to "Other Name", "allow-reconfigure" to "Allow Reconfigure Messages"), path.labels)
        assertEquals("name", path.quick)
        assertEquals(listOf("state"), path.readonly)
        assertEquals(listOf("renew", "release"), path.actions.map { it.command })
    }

    @Test
    fun aTabWithAPathOfItsOwnIsTheBundledOneAndAPathOfTheRouterAloneIsTaken() {
        val own = pack(listOf("A"), PackPath(form = "ip/gadget"), PackFormTab("Ethernet", listOf("name"), path = "interface/ethernet"))
        val other = Pack(
            menu = listOf("A"),
            window = PackWindow("A", listOf(PackTab("A", "ip/gadget"), PackTab("More", "ip/more"))),
            paths = mapOf("ip/gadget" to PackPath(form = "ip/gadget"), "ip/more" to PackPath(columns = listOf("name"))),
            forms = mapOf("ip/gadget" to PackForm(tabs = listOf(PackFormTab("Ethernet", listOf("name", "speed"))))),
        )
        val result = routerFirst(listOf(own), listOf(other)).single()
        assertEquals(listOf("name"), result.forms.getValue("ip/gadget").tabs.single().fields)
        assertEquals(listOf("name"), result.paths.getValue("ip/more").columns)
    }

    @Test
    fun aWindowNoBundledPackStandsForComesAsMadeAndABundledPackTheRouterDoesNotDescribeIsNotReturned() {
        val elsewhere = Pack(menu = listOf("IP", "Other"), window = PackWindow("Other", listOf(PackTab("Other", "ip/other"))), paths = mapOf("ip/other" to PackPath()))
        val result = routerFirst(listOf(bundled), listOf(elsewhere))
        assertSame(elsewhere, result.single())
        assertEquals(emptyList(), routerFirst(listOf(bundled), emptyList()))
    }

    @Test
    fun aPackThatSharesItsFirstPathWithABundledOneIsMergedIntoItAndTheMenuOfTheBundledOneStays() {
        val renamed = pack(listOf("IP", "Gadgets and more"), PackPath(columns = listOf("state")), PackFormTab("General", listOf("state")))
        val result = routerFirst(listOf(bundled), listOf(renamed)).single()
        assertEquals(listOf("IP", "Gadgets"), result.menu)
        assertEquals(listOf("Gadget", "Options"), result.window.tabs.map { it.title })
        assertEquals(listOf("name", "use-peer-dns", "rate", "state"), result.paths.getValue("ip/gadget").columns)
    }
}
