package com.vasmarfas.networkhelm.packs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PackWordsTest {

    private val words = mapOf(
        "My Networks" to "Мои сети и Wi-Fi",
        "Segments" to "Сегменты",
        "Segment" to "Сегмент",
        "General" to "Общие",
        "IP Address" to "IP-адрес",
        "Description" to "Описание",
        "Disabled" to "Выключено",
        "Safely Remove" to "Безопасно извлечь",
    )

    @Test
    fun aPackTakesTheWordsOfTheRouterForItsTitlesButtonsAndLabelsAndForAFieldItLeavesToItsName() {
        val pack = parsePack(
            """
            {"platform": "keenetic", "menu": ["My Networks", "Segments"],
             "window": {"title": "Segments", "tabs": [{"title": "Segments", "path": "segments"}]},
             "paths": {"segments": {"columns": ["interface-name", "address", "disabled", "mac"], "form": "segment", "actions": [{"title": "Safely Remove", "command": "eject"}]}},
             "forms": {"segment": {"title": "Segment <{interface-name}>", "tabs": [{"title": "General", "fields": ["interface-name", "address", "description"]}]}},
             "labels": {"address": "IP Address", "interface-name": "Segment"}}
            """.trimIndent(),
        )
        val worded = pack.worded(words)
        assertEquals(listOf("Мои сети и Wi-Fi", "Сегменты"), worded.menu)
        assertEquals("Сегменты", worded.window.title)
        assertEquals("Сегменты", worded.window.tabs.single().title)
        assertEquals("Сегмент <{interface-name}>", worded.forms.getValue("segment").title)
        assertEquals("Общие", worded.forms.getValue("segment").tabs.single().title)
        assertEquals("Безопасно извлечь", worded.paths.getValue("segments").actions.single().title)
        assertEquals(mapOf("address" to "IP-адрес", "interface-name" to "Сегмент", "disabled" to "Выключено", "description" to "Описание"), worded.labels)
        assertSame(pack, pack.worded(emptyMap()))
    }

    @Test
    fun aMenuEntryKeepsTheTitleItsIconGoesBy() {
        val menu = parseMenu("""{"platform": "keenetic", "items": [{"title": "My Networks", "items": [{"title": "Segments", "path": "segments"}]}, {"title": "ARP", "path": "show/ip/arp"}]}""")
        val worded = menu.worded(words)
        assertEquals(listOf("Мои сети и Wi-Fi", "ARP"), worded.items.map { it.title })
        assertEquals(listOf("My Networks", "ARP"), worded.items.map { it.icon })
        assertEquals("Сегменты", worded.items.first().items.single().title)
    }
}
