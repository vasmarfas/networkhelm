package com.vasmarfas.networkhelm.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class TitlesTest {

    @Test
    fun aDialogNamesOneElementOfItsList() {
        val tabs = listOf("Secrets", "Policies", "Used Addresses", "Installed SAs", "MSTIs", "Aliases", "Queue Tree", "VRRP", "Wireless", "Status", "Radius", "Cookies")
        val one = listOf("Secret", "Policy", "Used Address", "Installed SA", "MSTI", "Alias", "Queue Tree", "VRRP", "Wireless", "Status", "Radius", "Cookie")
        assertEquals(one, tabs.map(::singular))
    }
}
