package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConsoleCallTest {
    private fun call(vararg words: String, yesNo: Set<String> = emptySet(), refused: Map<String, Set<String>> = emptyMap()) = consoleCall(words.asList(), yesNo, refused)

    private fun rows(command: String) = ":put [:serialize to=json options=json.no-string-conversion [$command]]"

    private fun whole(rest: String) = listOf("", " detail show-sensitive", " stats-detail").joinToString("; ") { rows("/ip/address/print as-value$it$rest") }

    @Test
    fun printAsksForRowsAndTakesTheQueryAsOneExpression() {
        val call = call("/ip/address/print", "=.proplist=.id,address,interface", "?disabled=false", "?interface=ether1")
        assertEquals(whole(" proplist=address,interface where disabled=\"no\" && interface=\"ether1\""), call.line)
        assertEquals(Reply.Rows, call.reply)
        assertEquals(whole(" where ((a=\"1\" && b=\"2\") || c) && !d"), call("/ip/address/print", "?a=1", "?b=2", "?#&", "?c", "?#|", "?-d").line)
        assertEquals(whole(" where !(a=\"1\") && b<\"5\" && c>\"7\""), call("/ip/address/print", "?a=1", "?#!", "?<b=5", "?>c=7").line)
        assertEquals(whole(" where .id=\"*4A\""), call("/ip/address/print", "?.id=*4A").line)
    }

    @Test
    fun aPortionPicksItsRowsOutOfFindAndReadsThemInEveryView() {
        val call = consoleCall(listOf("/ip/address/print", "=.proplist=.id,address", "?disabled=false"), emptySet(), emptyMap(), portion = 2_000 until 4_000)
        val views = listOf("", " detail show-sensitive", " stats-detail").joinToString("; ") { rows("/ip/address/print as-value$it proplist=address from=\$part") }
        assertEquals(":local part [:pick [/ip/address/find where disabled=\"no\"] 2000 4000]; :if ([:len \$part] > 0) do={ $views } else={ :put \"[]\" }", call.line)
        assertEquals(setOf("detail", "show-sensitive", "stats-detail", "proplist", "find", "from"), call.offered)
    }

    @Test
    fun aMenuWithoutFindOrFromIsAskedWholeEvenForAPortion() {
        val whole = call("/ip/address/print").line
        assertEquals(whole, consoleCall(listOf("/ip/address/print"), emptySet(), mapOf("/ip/address" to setOf("from")), portion = 0 until 2_000).line)
        assertEquals(whole, consoleCall(listOf("/ip/address/print"), emptySet(), mapOf("/ip/address" to setOf("find")), portion = 0 until 2_000).line)
    }

    @Test
    fun aMenuIsAskedInThreeViewsAndWithoutTheWordsItHasRefused() {
        fun dns(vararg refused: String) = call("/ip/dns/print", refused = mapOf("/ip/dns" to refused.toSet()))
        fun views(vararg flags: String) = flags.joinToString("; ") { rows("/ip/dns/print as-value$it") }
        val first = dns()
        assertEquals(views("", " detail show-sensitive", " stats-detail"), first.line)
        assertEquals("/ip/dns", first.menu)
        assertEquals(setOf("detail", "show-sensitive", "stats-detail"), first.offered)
        assertEquals(views("", " detail", " stats"), dns("show-sensitive", "stats-detail").line)
        assertEquals(views(" show-sensitive", " stats-detail"), dns("detail").line)
        assertEquals(views(""), dns("detail", "show-sensitive", "stats-detail", "stats").line)
        assertEquals(views(" detail show-sensitive", " stats-detail"), dns("plain").line)
        assertTrue(first.plain)
        assertFalse(dns("plain").plain)
        assertFalse(dns("detail").plain)
        assertEquals(first.line, call("/ip/dns/print", refused = mapOf("/ip/address" to setOf("detail"))).line)
    }

    @Test
    fun aMenuThatRefusesProplistIsAskedWithoutItAndStillKeepsWhatWasAskedFor() {
        fun resource(vararg refused: String) = call("/system/resource/print", "=.proplist=.id,uptime,cpu-load", refused = mapOf("/system/resource" to refused.toSet()))
        val first = resource()
        assertTrue(first.line.contains("as-value proplist=uptime,cpu-load"))
        assertTrue("proplist" in first.offered)
        val plain = resource("proplist")
        assertFalse(plain.line.contains("proplist"))
        assertFalse("proplist" in plain.offered)
        assertEquals(listOf("uptime", "cpu-load"), plain.proplist)
    }

    @Test
    fun aCountCarriesTheWordsOfTheViewsTheMenuHasNotRefused() {
        val call = call("/ip/address/print", "=count-only=", "?dynamic=true")
        assertEquals("/ip/address/print count-only detail show-sensitive stats-detail where dynamic=\"yes\"", call.line)
        assertEquals(Reply.Count, call.reply)
        assertEquals("/ip/address", call.menu)
        assertEquals(setOf("detail", "show-sensitive", "stats-detail"), call.offered)
        val learnt = call("/ip/address/print", "=count-only=", refused = mapOf("/ip/address" to setOf("show-sensitive", "stats-detail", "stats")))
        assertEquals("/ip/address/print count-only detail", learnt.line)
    }

    @Test
    fun aValueIsABareWordOrAStringThatCannotBeTakenForSyntax() {
        assertEquals(":put [/ip/firewall/address-list/add list=rd-test address=10.0.0.1/24 timeout=1h comment=a,b]", call("/ip/firewall/address-list/add", "=list=rd-test", "=address=10.0.0.1/24", "=timeout=1h", "=comment=a,b").line)
        assertEquals(":put [/ip/address/add comment=\"a\\3Bb \\22q\\22 \\24x \\3F \\C3\\A9\" interface=\"\"]", call("/ip/address/add", "=comment=a;b \"q\" \$x ? é", "=interface=").line)
        assertEquals(":put [/ip/address/add comment=\"true\"]", call("/ip/address/add", "=comment=true").line)
        assertEquals(Reply.Id, call("/ip/address/add").reply)
    }

    @Test
    fun aValueGoesByTheCodesOfTheEncodingOfTheRouter() {
        val comment = listOf("/ip/address/add", "=comment=Привет")
        assertEquals(":put [/ip/address/add comment=\"\\D0\\9F\\D1\\80\\D0\\B8\\D0\\B2\\D0\\B5\\D1\\82\"]", consoleCall(comment, emptySet()).line)
        assertEquals(":put [/ip/address/add comment=\"\\CF\\F0\\E8\\E2\\E5\\F2\"]", consoleCall(comment, emptySet(), encoding = ApiEncoding.Windows1251).line)
        assertEquals(":put [/ip address add comment=\"\\CF\\F0\\E8\\E2\\E5\\F2\"]", classicCall(comment, emptySet(), emptyMap(), ApiEncoding.Windows1251).line)
        assertTrue(consoleCall(listOf("/ip/address/print", "?comment=Привет"), emptySet(), encoding = ApiEncoding.Windows1251).line.contains("comment=\"\\CF\\F0\\E8\\E2\\E5\\F2\""))
        assertFailsWith<IllegalArgumentException> { consoleCall(listOf("/ip/address/add", "=comment=😀"), emptySet(), encoding = ApiEncoding.Windows1251) }
    }

    @Test
    fun aValueCannotBreakOutOfItsString() {
        val line = call("/ip/address/set", "=.id=*1", "=comment=\"; /system reset-configuration \\").line
        assertEquals("/ip/address/set numbers=*1 comment=\"\\22\\3B /system reset-configuration \\5C\"", line)
        assertTrue(call("/ip/address/print", "?comment=\"; /x").line.contains("comment=\"\\22\\3B /x\""))
    }

    @Test
    fun trueAndFalseGoAsYesAndNoOnlyWhereTheRouterWantsThem() {
        assertEquals("/ip/address/set numbers=*1 disabled=\"true\" comment=\"false\"", call("/ip/address/set", "=.id=*1", "=disabled=true", "=comment=false").line)
        assertEquals("/ip/address/set numbers=*1 disabled=yes comment=\"false\"", call("/ip/address/set", "=.id=*1", "=disabled=true", "=comment=false", yesNo = setOf("disabled")).line)
        assertEquals("/ip/address/set numbers=*1,*2 disabled=no", call("/ip/address/set", "=.id=*1,*2", "=disabled=false", yesNo = setOf("disabled")).line)
    }

    @Test
    fun changesTakeTheNumbersOfTheirElements() {
        assertEquals("/ip/address/remove numbers=*1,*2", call("/ip/address/remove", "=.id=*1,*2").line)
        assertEquals("/ip/address/move numbers=*1 destination=*5", call("/ip/address/move", "=numbers=*1", "=destination=*5").line)
        assertEquals("/ip/address/enable numbers=*3", call("/ip/address/enable", "=.id=*3").line)
        assertEquals(Reply.Nothing, call("/ip/address/disable", "=.id=*3").reply)
        assertEquals("/ip/dhcp-server/set numbers=*1 comment=\"\"", call("/ip/dhcp-server/unset", "=.id=*1", "=value-name=comment").line)
        assertEquals("/ip/dns/set servers=\"\"", call("/ip/dns/unset", "=value-name=servers").line)
    }

    @Test
    fun aScriptRunsAsItIsAndAnythingElseAsksForRowsFirst() {
        val script = call("/execute", "=script=/ip firewall export", "=as-string=")
        assertEquals("/ip firewall export", script.line)
        assertEquals(Reply.Text, script.reply)
        val torch = call("/tool/torch", "=interface=ether1", "=duration=1")
        assertEquals(rows("/tool/torch interface=ether1 duration=1 as-value"), torch.line)
        assertEquals("/tool/torch interface=ether1 duration=1", assertNotNull(torch.fallback).line)
        assertEquals(rows("/interface/monitor-traffic interface=ether1 once as-value"), call("/interface/monitor-traffic", "=interface=ether1", "=once=").line)
        assertEquals(rows("/console/inspect request=child path=ip,address as-value"), call("/console/inspect", "=request=child", "=path=ip,address").line)
        assertEquals("/ip/address/export compact", call("/ip/address/export", "=compact=").line)
    }

    @Test
    fun aProfileIsAReadAndAPingIsPutAsTextForItsMicroseconds() {
        val profile = call("/tool/profile", "=duration=3")
        assertEquals(rows("/tool/profile duration=3 as-value"), profile.line)
        assertFalse(profile.changes)
        val ping = call("/tool/ping", "=address=127.0.0.1", "=count=1")
        assertEquals(":put [/tool/ping address=127.0.0.1 count=1 as-value]", ping.line)
        assertEquals(Reply.Pairs, ping.reply)
        assertFalse(ping.changes)
    }

    @Test
    fun whatIsNotAMenuIsRefused() {
        assertEquals(0, assertFailsWith<ApiTrapException> { call("/ip/addr ess/print") }.category)
        assertFailsWith<ApiTrapException> { call("/ip/address/print", "?a b=1") }
        assertFailsWith<ApiTrapException> { call("ip/address/print") }
    }
}
